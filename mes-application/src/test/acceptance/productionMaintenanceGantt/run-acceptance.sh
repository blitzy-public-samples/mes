#!/usr/bin/env bash
#
# ***************************************************************************
# Copyright (c) 2010 Qcadoo Limited
# Project: Qcadoo MES
# Version: 1.4
#
# This file is part of Qcadoo.
#
# Qcadoo is free software; you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published
# by the Free Software Foundation; either version 3 of the License,
# or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty
# of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
# See the GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program; if not, write to the Free Software
# Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
# ***************************************************************************
#
# Acceptance-suite runner of the production and maintenance Gantt board (cmmsMachineParts/productionMaintenanceGantt).
#
# Usage:
#   run-acceptance.sh --dist <mes-application.zip> --user <login> --password <password>
#                     [--db-name <database>] [--http-port <port>] [--shutdown-port <port>] [--work-root <directory>]
#
#   --dist           Tomcat distribution built by `mvn -B -Ptomcat clean install` in mes/mes-application.
#   --user           Application login used by acceptance.test.js.
#   --password       Password of that login.
#   --db-name        Database to recreate and to configure in the unpacked distribution.
#                    Default: the database of dbJdbcUrl in mes/mes-application/conf/tomcat/db.properties.
#   --http-port      HTTP connector port of the unpacked distribution. Default: the port in its conf/server.xml.
#   --shutdown-port  Shutdown port of the unpacked distribution. Default: the port in its conf/server.xml.
#   --work-root      Existing directory outside the repository that holds the temporary work directory. Its physical
#                    path may hold only letters A-Z and a-z, digits and . _ + - /.
#                    Default: the directory mktemp -t uses, which has to meet the same conditions.
#
# Steps:
#   1. Checks the arguments, the repository layout, the work root and the tools on PATH, chooses the base day of
#      fixture.sql, creates the temporary work directory, checks the PostgreSQL server of conf/tomcat/db.properties
#      answers, and guards the database with an advisory lock that a psql session on the maintenance database holds
#      until the script exits.
#   2. Checks the members of the distribution, unpacks it into the work directory, checks its db.properties, sets
#      hotDeploy=false in its app.properties, applies --db-name, --http-port and --shutdown-port to the unpacked copy,
#      and checks the HTTP and shutdown ports are free.
#   3. Recreates the database marked with the comment in MARKER on the server of conf/tomcat/db.properties,
#      then loads mes_db_en.sql and fixture.sql into it and checks the required plugins are enabled.
#   4. Starts Tomcat and, once the login page answers, checks that the started Tomcat runs and holds every LISTEN socket
#      on the HTTP port. Runs acceptance.test.js with the TAP reporter, repeats the listener check, and checks the TAP
#      summary and every case line.
#   5. Stops Tomcat on every exit. Removes the work directory once Tomcat has stopped; keeps it and prints its path
#      while Tomcat still runs. The database is kept.
#
# Exit status: 0 for --help, and otherwise only when every acceptance case passed, Tomcat stopped and the work
# directory was removed; 2 on a usage error; the runner's status when the runner failed; 130 on SIGINT; 143 on SIGTERM;
# 1 on any other failure, including Tomcat still running after the stop, a work directory that cannot be removed,
# an HTTP port listener that is not the started Tomcat or a started Tomcat that is no longer running, and a tee
# failure. A failed stop or removal keeps a nonzero status unchanged.

set -euo pipefail
unset CDPATH

readonly PROGRAM='run-acceptance.sh'
readonly MARKER='qcadoo-acceptance:productionMaintenanceGantt'
readonly REQUIRED_TOOLS=(psql setsid node unzip curl chrome-headless-shell)
readonly REQUIRED_PLUGINS=(productionScheduling lineChangeoverNormsForOrders cmmsMachineParts)
readonly EXPECTED_CASES=(
    'http:rowMapping'
    'browser:acceptedCrossRowMove'
    'http:acceptedLaterSameRowMove'
    'browser:rejectRouting'
    'browser:rejectShutdown'
    'browser:rejectCalendar'
    'browser:rejectStaleBoard'
    'http:concurrentMovedPositionWrite'
    'http:concurrentNeighbourWrite'
    'http:concurrentMembershipChange'
    'http:concurrentMoves'
    'http:rollbackAfterPsSideEffect'
)
readonly MIN_NODE_MAJOR=22
readonly DEFAULT_DB_HOST='localhost'
readonly DEFAULT_DB_PORT='5432'
readonly DEFAULT_HTTP_PORT='8080'
readonly DEFAULT_SHUTDOWN_PORT='8005'
readonly STARTUP_TIMEOUT_SECONDS=600
readonly STARTUP_POLL_SECONDS=2
readonly STARTUP_REPORT_SECONDS=10
readonly STOP_WAIT_SECONDS=30
readonly KILL_WAIT_SECONDS=10
readonly LOG_TAIL_LINES=200
readonly PSQL_CONNECT_TIMEOUT_SECONDS=30
readonly PSQL_COMMAND_TIMEOUT_SECONDS=300
readonly PSQL_SEED_TIMEOUT_SECONDS=1800
readonly PSQL_FIXTURE_TIMEOUT_SECONDS=600
readonly PSQL_KILL_GRACE_SECONDS=10
readonly RUN_GUARD_APPLICATION='pmg-acceptance-guard'
readonly RUN_GUARD_WAIT_SECONDS=60
readonly BASE_DAY_FIRST_AHEAD=2
readonly BASE_DAY_LAST_AHEAD=13
readonly BASE_DAY_FOLLOWING_DAYS=8
readonly SAFE_PATH_RE='^/[ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._+/-]*$'
readonly SAFE_PATH_CHARACTERS='letters A-Z and a-z, digits and . _ + - /'
readonly WORK_ROOT_REQUIREMENT="an existing directory outside the repository whose physical path holds only $SAFE_PATH_CHARACTERS"

# State read by the cleanup trap.
WORK_DIR=''
TOMCAT_LOG=''
TOMCAT_PID=''
CATALINA_BASE=''

# Reads the port attributes of conf/server.xml outside XML comments, or rewrites them.
#   ports <server.xml>                           prints "<http port> <shutdown port>"; an absent value prints "-"
#   rewrite <server.xml> <http> <shutdown>       sets the port of the first non-AJP Connector and of the Server element
#   A port value in single or double quotes is printed as written, sign and leading zeros included; a value that is
#   not an optionally signed decimal number makes it exit with status 1.
# shellcheck disable=SC2016
readonly SERVER_XML_JS='
"use strict";
const fs = require("fs");
const [mode, file, httpPort, shutdownPort] = process.argv.slice(1);
const parts = fs.readFileSync(file, "utf8").split(/(<!--[\s\S]*?-->)/);
const PORT = /(\bport\s*=\s*)(["\x27])([^"\x27]*)\2/;
const NUMBER = /^[+-]?[0-9]+$/;
const isAjp = (element) => /\bprotocol\s*=\s*["\x27][^"\x27]*ajp/i.test(element);
const portOf = (element) => {
    const port = PORT.exec(element);
    if (port && !NUMBER.test(port[3])) {
        process.stderr.write("port " + JSON.stringify(port[3]) + " of " + element + " is not a decimal number\n");
        process.exit(1);
    }
    return port;
};
const withPort = (element, value) => element.replace(PORT, (match, prefix, quote) => prefix + quote + value + quote);
let http = null;
let shutdown = null;
for (let index = 0; index < parts.length; index += 2) {
    parts[index] = parts[index].replace(/<Server\b[^>]*>/g, (element) => {
        if (shutdown !== null) {
            return element;
        }
        const port = portOf(element);
        if (!port) {
            return element;
        }
        shutdown = port[3];
        return mode === "rewrite" ? withPort(element, shutdownPort) : element;
    });
    parts[index] = parts[index].replace(/<Connector\b[^>]*>/g, (element) => {
        if (http !== null || isAjp(element)) {
            return element;
        }
        const port = portOf(element);
        if (!port) {
            return element;
        }
        http = port[3];
        return mode === "rewrite" ? withPort(element, httpPort) : element;
    });
}
if (mode === "rewrite") {
    if (http === null || shutdown === null) {
        process.stderr.write("server.xml has no HTTP Connector port or no Server port\n");
        process.exit(1);
    }
    fs.writeFileSync(file, parts.join(""));
} else {
    process.stdout.write((http === null ? "-" : http) + " " + (shutdown === null ? "-" : shutdown));
}
'

# Checks the central directory of a ZIP archive and prints the number of its regular-file members.
#   Fails on a multi-disk archive and on any member that holds a control character or a backslash, is absolute, starts
#   with a drive letter, has an empty, . or .. component, is encrypted, carries a Unicode path extra field naming another
#   path, is a symbolic link or has a unix type other than regular file or directory, repeats another member's name, or
#   is a file where another member needs a directory. Prints at most 20 problems.
# shellcheck disable=SC2016
readonly ZIP_INSPECT_JS='
"use strict";
const fs = require("fs");
const file = process.argv[1];
const MAX_PROBLEMS = 20;
const EOCD_SIGNATURE = 0x06054b50;
const EOCD_LENGTH = 22;
const MAX_COMMENT_LENGTH = 65535;
const ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
const ZIP64_LOCATOR_LENGTH = 20;
const ZIP64_EOCD_SIGNATURE = 0x06064b50;
const ZIP64_EOCD_LENGTH = 56;
const CENTRAL_SIGNATURE = 0x02014b50;
const CENTRAL_LENGTH = 46;
const ENCRYPTION_FLAGS = 0x2001;
const UNICODE_PATH_EXTRA = 0x7075;
const TYPE_MASK = 0o170000;
const TYPE_FILE = 0o100000;
const TYPE_DIRECTORY = 0o040000;
const TYPE_SYMLINK = 0o120000;
const fail = (message) => {
    process.stderr.write(file + ": " + message + "\n");
    process.exit(1);
};
try {
    const descriptor = fs.openSync(file, "r");
    const size = fs.fstatSync(descriptor).size;
    const read = (position, length) => {
        if (position < 0 || length < 0 || position + length > size) {
            fail(length + " bytes at offset " + position + " lie outside the " + size + "-byte file");
        }
        const buffer = Buffer.alloc(length);
        let done = 0;
        while (done < length) {
            const count = fs.readSync(descriptor, buffer, done, length - done, position + done);
            if (count === 0) {
                fail("unexpected end of file at offset " + (position + done));
            }
            done += count;
        }
        return buffer;
    };
    const readUInt64 = (buffer, offset) => {
        const value = buffer.readBigUInt64LE(offset);
        if (value > BigInt(Number.MAX_SAFE_INTEGER)) {
            fail("ZIP64 value " + value + " exceeds " + Number.MAX_SAFE_INTEGER);
        }
        return Number(value);
    };
    const tailLength = Math.min(size, EOCD_LENGTH + MAX_COMMENT_LENGTH);
    const tail = read(size - tailLength, tailLength);
    let eocd = -1;
    for (let index = tailLength - EOCD_LENGTH; index >= 0; index -= 1) {
        if (tail.readUInt32LE(index) === EOCD_SIGNATURE
                && index + EOCD_LENGTH + tail.readUInt16LE(index + 20) === tailLength) {
            eocd = index;
            break;
        }
    }
    if (eocd < 0) {
        fail("no end of central directory record");
    }
    const eocdPosition = size - tailLength + eocd;
    let diskNumber = tail.readUInt16LE(eocd + 4);
    let centralDisk = tail.readUInt16LE(eocd + 6);
    let diskEntries = tail.readUInt16LE(eocd + 8);
    let totalEntries = tail.readUInt16LE(eocd + 10);
    let centralSize = tail.readUInt32LE(eocd + 12);
    let centralOffset = tail.readUInt32LE(eocd + 16);
    const locatorPosition = eocdPosition - ZIP64_LOCATOR_LENGTH;
    if (locatorPosition >= 0 && read(locatorPosition, 4).readUInt32LE(0) === ZIP64_LOCATOR_SIGNATURE) {
        const locator = read(locatorPosition, ZIP64_LOCATOR_LENGTH);
        if (locator.readUInt32LE(4) !== 0 || locator.readUInt32LE(16) > 1) {
            fail("multi-disk archives are not supported");
        }
        const record = read(readUInt64(locator, 8), ZIP64_EOCD_LENGTH);
        if (record.readUInt32LE(0) !== ZIP64_EOCD_SIGNATURE) {
            fail("no ZIP64 end of central directory record where its locator points");
        }
        diskNumber = record.readUInt32LE(16);
        centralDisk = record.readUInt32LE(20);
        diskEntries = readUInt64(record, 24);
        totalEntries = readUInt64(record, 32);
        centralSize = readUInt64(record, 40);
        centralOffset = readUInt64(record, 48);
    } else if (diskEntries === 0xffff || totalEntries === 0xffff || centralSize === 0xffffffff
            || centralOffset === 0xffffffff) {
        fail("ZIP64 values without a ZIP64 end of central directory locator");
    }
    if (diskNumber !== 0 || centralDisk !== 0 || diskEntries !== totalEntries) {
        fail("multi-disk archives are not supported");
    }
    const central = read(centralOffset, centralSize);
    const entries = [];
    let position = 0;
    for (let entry = 1; entry <= totalEntries; entry += 1) {
        if (position + CENTRAL_LENGTH > central.length || central.readUInt32LE(position) !== CENTRAL_SIGNATURE) {
            fail("central directory entry " + entry + " of " + totalEntries + " is missing or damaged");
        }
        const nameStart = position + CENTRAL_LENGTH;
        const extraStart = nameStart + central.readUInt16LE(position + 28);
        const commentStart = extraStart + central.readUInt16LE(position + 30);
        const end = commentStart + central.readUInt16LE(position + 32);
        if (end > central.length) {
            fail("central directory entry " + entry + " of " + totalEntries + " overruns the central directory");
        }
        entries.push({
            flags: central.readUInt16LE(position + 8),
            attributes: central.readUInt32LE(position + 38),
            rawName: central.subarray(nameStart, extraStart),
            name: central.toString("latin1", nameStart, extraStart),
            extra: central.subarray(extraStart, commentStart)
        });
        position = end;
    }
    if (position !== central.length) {
        fail("the central directory holds " + (central.length - position) + " bytes after its " + totalEntries
            + " entries");
    }
    const problems = [];
    const kinds = new Map();
    const directories = new Set();
    const files = [];
    for (const { flags, attributes, rawName, name, extra } of entries) {
        const report = (problem) => problems.push("member " + JSON.stringify(name) + " " + problem);
        const directory = name.endsWith("/");
        const path = directory ? name.slice(0, -1) : name;
        const components = path.split("/");
        const type = (attributes >>> 16) & TYPE_MASK;
        if (/[\x00-\x1f\x7f]/.test(name)) {
            report("holds a control character");
        }
        if (name.includes("\\")) {
            report("holds a backslash");
        }
        if (name.startsWith("/")) {
            report("is an absolute path");
        } else if (/^[A-Za-z]:/.test(name)) {
            report("starts with a drive letter");
        } else if (components.some((component) => component === "" || component === "." || component === "..")) {
            report("has an empty, . or .. path component");
        }
        if ((flags & ENCRYPTION_FLAGS) !== 0) {
            report("is encrypted");
        }
        for (let offset = 0; offset < extra.length;) {
            if (offset + 4 > extra.length || offset + 4 + extra.readUInt16LE(offset + 2) > extra.length) {
                report("has a malformed extra field");
                break;
            }
            const length = extra.readUInt16LE(offset + 2);
            if (extra.readUInt16LE(offset) === UNICODE_PATH_EXTRA
                    && (length < 5 || !extra.subarray(offset + 9, offset + 4 + length).equals(rawName))) {
                report("carries a Unicode path extra field that names another path");
            }
            offset += 4 + length;
        }
        if (type === TYPE_SYMLINK) {
            report("is a symbolic link");
        } else if (type !== 0 && type !== TYPE_FILE && type !== TYPE_DIRECTORY) {
            report("has the unix file type 0" + type.toString(8));
        } else if (type === TYPE_DIRECTORY && !directory) {
            report("is a directory without a trailing /");
        } else if (type === TYPE_FILE && directory) {
            report("is a regular file with a trailing /");
        }
        const kind = directory ? "directory" : "file";
        if (!kinds.has(path)) {
            kinds.set(path, kind);
        } else if (kinds.get(path) === kind) {
            report("is a duplicate");
        } else {
            report("is both a file and a directory");
        }
        for (let index = 1; index < components.length; index += 1) {
            directories.add(components.slice(0, index).join("/"));
        }
        if (!directory) {
            files.push({ name, path });
        }
    }
    for (const { name, path } of files) {
        if (directories.has(path)) {
            problems.push("member " + JSON.stringify(name) + " is a file and the directory of another member");
        }
    }
    if (problems.length > 0) {
        for (const problem of problems.slice(0, MAX_PROBLEMS)) {
            process.stderr.write(file + ": " + problem + "\n");
        }
        if (problems.length > MAX_PROBLEMS) {
            process.stderr.write(file + ": " + (problems.length - MAX_PROBLEMS) + " more problems\n");
        }
        process.exit(1);
    }
    process.stdout.write(String(files.length));
} catch (error) {
    fail(error.message);
}
'

# Prints the processes with a listening TCP socket on the given port as "pid <pid> (<process name>)", comma-separated,
# from /proc/net/tcp, /proc/net/tcp6, /proc/<pid>/fd and /proc/<pid>/comm; prints nothing when no listening socket is
# visible. Command-line arguments are never printed.
# shellcheck disable=SC2016
readonly PORT_OWNER_JS='
"use strict";
const fs = require("fs");
const port = Number(process.argv[1]);
const LISTEN = "0A";
const readLines = (file) => {
    try {
        return fs.readFileSync(file, "utf8").split("\n").slice(1);
    } catch (error) {
        return [];
    }
};
const readEntries = (directory) => {
    try {
        return fs.readdirSync(directory);
    } catch (error) {
        return [];
    }
};
const readLink = (link) => {
    try {
        return fs.readlinkSync(link);
    } catch (error) {
        return "";
    }
};
const readProcessName = (pid) => {
    try {
        return fs.readFileSync("/proc/" + pid + "/comm", "latin1").trim().replace(/[\x00-\x1f\x7f]/g, "?");
    } catch (error) {
        return "";
    }
};
const inodes = new Set();
for (const table of ["/proc/net/tcp", "/proc/net/tcp6"]) {
    for (const line of readLines(table)) {
        const fields = line.trim().split(/\s+/);
        if (fields.length > 9 && fields[3] === LISTEN
                && parseInt(fields[1].slice(fields[1].lastIndexOf(":") + 1), 16) === port) {
            inodes.add(fields[9]);
        }
    }
}
if (inodes.size > 0) {
    const owners = [];
    const found = new Set();
    for (const pid of readEntries("/proc").filter((name) => /^[0-9]+$/.test(name))) {
        let owner = false;
        for (const descriptor of readEntries("/proc/" + pid + "/fd")) {
            const match = /^socket:\[([0-9]+)\]$/.exec(readLink("/proc/" + pid + "/fd/" + descriptor));
            if (match && inodes.has(match[1])) {
                found.add(match[1]);
                owner = true;
            }
        }
        if (owner) {
            const name = readProcessName(pid);
            owners.push("pid " + pid + (name === "" ? "" : " (" + name + ")"));
        }
    }
    if (found.size < inodes.size) {
        owners.push("a process whose descriptors this user cannot read");
    }
    process.stdout.write(owners.join(", "));
}
'

# Prints "YYYY-MM-DD +HHMM" for the first local day <first> to <last> days after today whose UTC offset is the same at
# every minute from its midnight to the midnight <following> + 1 days later, or nothing when no day qualifies.
#   <first> <last> <following>
readonly BASE_DAY_JS='
"use strict";
const [first, last, following] = process.argv.slice(1).map(Number);
if (![first, last, following].every(Number.isInteger) || first > last || following < 0) {
    process.stderr.write("expected the arguments <first> <last> <following> as integers with first <= last\n");
    process.exit(2);
}
const MINUTE_MS = 60000;
const today = new Date();
const pad = (value) => String(value).padStart(2, "0");
const isMidnight = (date) => date.getHours() === 0 && date.getMinutes() === 0 && date.getSeconds() === 0
    && date.getMilliseconds() === 0;
const hasConstantOffset = (start, end) => {
    const offset = start.getTimezoneOffset();
    for (let time = start.getTime(); time <= end.getTime(); time += MINUTE_MS) {
        if (new Date(time).getTimezoneOffset() !== offset) {
            return false;
        }
    }
    return true;
};
for (let ahead = first; ahead <= last; ahead += 1) {
    const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() + ahead);
    const end = new Date(today.getFullYear(), today.getMonth(), today.getDate() + ahead + following + 1);
    if (!isMidnight(start) || !isMidnight(end) || !hasConstantOffset(start, end)) {
        continue;
    }
    const utcOffset = -start.getTimezoneOffset();
    const magnitude = Math.abs(utcOffset);
    process.stdout.write(start.getFullYear() + "-" + pad(start.getMonth() + 1) + "-" + pad(start.getDate()) + " "
        + (utcOffset < 0 ? "-" : "+") + pad(Math.floor(magnitude / 60)) + pad(magnitude % 60));
    break;
}
'

# Runs a command with inherited standard streams and forwards SIGINT, SIGTERM and SIGHUP to it. After <seconds> it sends
# SIGINT, then SIGTERM and SIGKILL <grace> seconds apart while the command runs, and exits with status 124; otherwise it
# exits with the command's status, 128 plus the number of the signal that ended the command, or 127 when the command
# cannot be started.
#   <program> <seconds> <grace> <command> [<argument>...]
readonly PSQL_DEADLINE_JS='
"use strict";
const childProcess = require("child_process");
const os = require("os");
const [program, secondsText, graceText, command, ...args] = process.argv.slice(1);
const seconds = Number(secondsText);
const grace = Number(graceText);
const MAX_TIMER_SECONDS = 2147483;
const isTimerSeconds = (value) => value > 0 && value <= MAX_TIMER_SECONDS;
if (!program || !command || !isTimerSeconds(seconds) || !isTimerSeconds(grace)) {
    process.stderr.write("expected the arguments <program> <seconds> <grace> <command> [<argument>...]\n");
    process.exit(2);
}
const DEADLINE_SIGNALS = ["SIGINT", "SIGTERM", "SIGKILL"];
let expired = false;
let escalationTimer = null;
const child = childProcess.spawn(command, args, { stdio: "inherit" });
const escalate = (index) => {
    child.kill(DEADLINE_SIGNALS[index]);
    if (index + 1 < DEADLINE_SIGNALS.length) {
        escalationTimer = setTimeout(() => escalate(index + 1), grace * 1000);
    }
};
const deadlineTimer = setTimeout(() => {
    expired = true;
    escalate(0);
}, seconds * 1000);
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
    process.on(signal, () => child.kill(signal));
}
child.on("error", (error) => {
    if (child.pid !== undefined) {
        process.stderr.write(program + ": error: " + command + ": " + error.message + "\n");
        return;
    }
    clearTimeout(deadlineTimer);
    process.stderr.write(program + ": error: cannot run " + command + ": " + error.message + "\n");
    process.exit(127);
});
child.on("exit", (code, signal) => {
    clearTimeout(deadlineTimer);
    clearTimeout(escalationTimer);
    if (expired) {
        process.stderr.write(program + ": error: " + command + " did not finish within " + seconds + " s\n");
        process.exit(124);
    }
    process.exit(code !== null ? code : 128 + (os.constants.signals[signal] || 0));
});
'

# Checks that a process holds every LISTEN socket on a TCP port of /proc/net/tcp and /proc/net/tcp6.
#   <pid> <port>    prints the addresses of those sockets; otherwise prints the failure to stderr and exits 1
# shellcheck disable=SC2016
readonly LISTENER_JS='
"use strict";
const fs = require("fs");
const [pid, port] = process.argv.slice(1);
const fail = (message) => {
    process.stderr.write(message + "\n");
    process.exit(1);
};
const address = (hex) => {
    const bytes = [];
    for (let word = 0; word < hex.length; word += 8) {
        for (let index = 6; index >= 0; index -= 2) {
            bytes.push(parseInt(hex.substr(word + index, 2), 16));
        }
    }
    if (bytes.length === 4) {
        return bytes.join(".");
    }
    const groups = [];
    for (let index = 0; index < bytes.length; index += 2) {
        groups.push(((bytes[index] << 8) | bytes[index + 1]).toString(16));
    }
    return "[" + groups.join(":") + "]";
};
const socketInodes = (processId, ignoreErrors) => {
    const inodes = new Set();
    const directory = "/proc/" + processId + "/fd";
    let descriptors = [];
    try {
        descriptors = fs.readdirSync(directory);
    } catch (error) {
        if (!ignoreErrors) {
            fail("cannot read " + directory + ": " + error.message);
        }
    }
    for (const descriptor of descriptors) {
        let target = "";
        try {
            target = fs.readlinkSync(directory + "/" + descriptor);
        } catch (error) {
            if (!ignoreErrors && error.code !== "ENOENT") {
                fail("cannot read " + directory + "/" + descriptor + ": " + error.message);
            }
        }
        const socket = /^socket:\[(\d+)\]$/.exec(target);
        if (socket) {
            inodes.add(socket[1]);
        }
    }
    return inodes;
};
if (!/^[1-9]\d*$/.test(pid || "") || !/^[1-9]\d*$/.test(port || "")) {
    fail("usage: <pid> <port>");
}
const listeners = new Map();
for (const table of ["/proc/net/tcp", "/proc/net/tcp6"]) {
    let rows = [];
    try {
        rows = fs.readFileSync(table, "utf8").split("\n").slice(1);
    } catch (error) {
        if (table !== "/proc/net/tcp6" || error.code !== "ENOENT") {
            fail("cannot read " + table + ": " + error.message);
        }
    }
    for (const row of rows) {
        const fields = row.trim().split(/\s+/);
        if (fields.length < 10 || fields[3] !== "0A") {
            continue;
        }
        const separator = fields[1].lastIndexOf(":");
        if (parseInt(fields[1].slice(separator + 1), 16) === Number(port)) {
            listeners.set(fields[9], address(fields[1].slice(0, separator)) + ":" + port);
        }
    }
}
if (listeners.size === 0) {
    fail("no LISTEN socket on TCP port " + port + " in /proc/net/tcp or /proc/net/tcp6");
}
const owned = socketInodes(pid, false);
const foreign = [...listeners.keys()].filter((inode) => !owned.has(inode));
if (foreign.length > 0) {
    const holders = new Map(foreign.map((inode) => [inode, []]));
    let processes = [];
    let unknownHolder = "no readable process";
    try {
        processes = fs.readdirSync("/proc");
    } catch (error) {
        unknownHolder = "unknown: cannot read /proc: " + error.message;
    }
    for (const entry of processes) {
        if (!/^\d+$/.test(entry) || entry === pid) {
            continue;
        }
        for (const inode of socketInodes(entry, true)) {
            if (holders.has(inode)) {
                let name = "?";
                try {
                    name = fs.readFileSync("/proc/" + entry + "/comm", "utf8").trim();
                } catch (error) {
                    name = "exited";
                }
                holders.get(inode).push("pid " + entry + " (" + name + ")");
            }
        }
    }
    fail("pid " + pid + " does not hold " + foreign.map((inode) => "LISTEN socket " + listeners.get(inode)
        + " (inode " + inode + ", held by " + (holders.get(inode).join(", ") || unknownHolder) + ")").join("; "));
}
process.stdout.write([...listeners.values()].join(", "));
'

# Prints a progress line.
log() {
    printf '%s: %s\n' "$PROGRAM" "$1"
}

# Prints an error line and exits with status 1.
die() {
    printf '%s: error: %s\n' "$PROGRAM" "$1" >&2
    exit 1
}

# Prints the usage line to the given stream (default stderr).
usage() {
    printf 'usage: %s --dist <mes-application.zip> --user <login> --password <password> [--db-name <database>] [--http-port <port>] [--shutdown-port <port>] [--work-root <directory>]\n' \
        "$PROGRAM" >&"${1:-2}"
}

# Prints a usage error with the usage line and exits with status 2.
usage_error() {
    printf '%s: %s\n' "$PROGRAM" "$1" >&2
    usage 2
    exit 2
}

# Prints the argument without leading and trailing whitespace.
trim() {
    local value="$1"

    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

# Prints the argument as an absolute path resolved against the current working directory.
absolute_path() {
    case "$1" in
        /*) printf '%s' "$1" ;;
        *) printf '%s/%s' "$(pwd)" "$1" ;;
    esac
}

# Succeeds when the argument is an absolute path of the characters in SAFE_PATH_CHARACTERS only.
is_safe_path() {
    [[ "$1" =~ $SAFE_PATH_RE ]]
}

# Prints the physical path of an existing directory, every symbolic link resolved; fails when it cannot resolve it.
physical_dir() {
    local resolved

    resolved="$(cd -P -- "$1" 2> /dev/null && pwd -P)" || return 1
    [ -n "$resolved" ] && [ "$resolved" -ef "$1" ] || return 1
    printf '%s' "$resolved"
}

# Succeeds when the first path is the second path or lies below it.
is_within() {
    [ "$1" = "$2" ] || [[ "$1" == "${2%/}/"* ]]
}

# Prints why a physical directory path cannot hold the work directory, or nothing when it can.
work_path_problem() {
    if ! is_safe_path "$1"; then
        printf 'its physical path %s holds characters other than %s' "$1" "$SAFE_PATH_CHARACTERS"
    elif is_within "$1" "$REPO_ROOT"; then
        printf 'its physical path %s lies inside the repository %s' "$1" "$REPO_ROOT"
    fi
}

# Exits unless the argument is a regular file of the unpacked distribution: not a symbolic link, its physical
# directory and name forming the path itself, and that directory inside the physical DIST_DIR.
require_distribution_file() {
    local file="$1" directory

    if [ ! -f "$file" ] || [ -L "$file" ]; then
        die "$file is not a regular file of the unpacked distribution; rebuild the distribution"
    fi

    directory="$(physical_dir "${file%/*}")" || die "cannot resolve the physical directory of $file"

    if [ "$directory/${file##*/}" != "$file" ]; then
        die "$file resolves to $directory/${file##*/} through a symbolic link or a . or .. component; rebuild the distribution"
    fi

    if ! is_within "$directory" "$DIST_DIR"; then
        die "$file lies outside the unpacked distribution $DIST_DIR"
    fi
}

# Sets the array named by the first argument to the paths below the directory in the second argument that match
# the find tests in the remaining arguments, read NUL-delimited; exits when find fails.
find_paths() {
    local -n find_paths_result="$1"
    local directory="$2" list

    shift 2
    list="$(mktemp -p "$WORK_DIR" find-paths.XXXXXX)" || die "cannot create a path list in $WORK_DIR"

    if ! find "$directory" \( "$@" \) -print0 > "$list"; then
        rm -f -- "$list"
        die "find $directory $* failed"
    fi

    mapfile -d '' -t find_paths_result < "$list"
    rm -f -- "$list"
}

# Succeeds when the argument is a TCP port number from 1 to 65535.
is_port() {
    [[ "$1" =~ ^[0-9]{1,5}$ ]] && [ "$((10#$1))" -ge 1 ] && [ "$((10#$1))" -le 65535 ]
}

# Prints a server.xml port value as a decimal number without a + sign or leading zeros, keeping the - of a negative
# value; prints any other value unchanged.
normalize_port_value() {
    local value="$1" sign=''

    case "$value" in
        -?*) sign='-'; value="${value#-}" ;;
        +?*) value="${value#+}" ;;
    esac

    if [[ "$value" =~ ^[0-9]+$ ]]; then
        while [[ "$value" == 0?* ]]; do
            value="${value#0}"
        done

        if [ "$value" = '0' ]; then
            sign=''
        fi
    fi

    printf '%s%s' "$sign" "$value"
}

# Succeeds when the argument is a database name of letters, digits and underscores, at most 63 characters long.
is_db_name() {
    [[ "$1" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] && [ "${#1}" -le 63 ]
}

# Prints the trimmed value of the last `key=value` line of a properties file; fails when the key is absent.
property_value() {
    local file="$1" key="$2" line name found=1 result=''

    while IFS= read -r line || [ -n "$line" ]; do
        line="$(trim "${line%$'\r'}")"

        case "$line" in
            '' | '#'* | '!'*) continue ;;
            *=*) ;;
            *) continue ;;
        esac

        name="$(trim "${line%%=*}")"

        if [ "$name" = "$key" ]; then
            result="$(trim "${line#*=}")"
            found=0
        fi
    done < "$file"

    printf '%s' "$result"
    return "$found"
}

# Rewrites the value of every `key=value` line of a properties file whose key is the given key.
set_property_value() {
    local file="$1" key="$2" value="$3" line name rewritten

    rewritten="$(mktemp "$file.XXXXXX")"

    while IFS= read -r line || [ -n "$line" ]; do
        name="$(trim "${line%%=*}")"

        if [[ "$line" == *=* ]] && [ "$name" = "$key" ]; then
            printf '%s=%s\n' "$key" "$value"
        else
            printf '%s\n' "$line"
        fi
    done < "$file" > "$rewritten"

    cat -- "$rewritten" > "$file"
    rm -f -- "$rewritten"
}

# Percent-encodes the argument for the userinfo part of a URI.
url_encode() {
    node -e 'process.stdout.write(encodeURIComponent(process.argv[1]))' -- "$1"
}

# Succeeds when a TCP connection to the given port opens on 127.0.0.1 or on ::1.
port_open() {
    (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null || (exec 3<> "/dev/tcp/::1/$1") 2> /dev/null
}

# Exits when a TCP port accepts a connection on 127.0.0.1 or ::1 or has a listening socket, naming the listening processes.
#   require_free_port <label> <port>
require_free_port() {
    local label="$1" port="$2" owners

    owners="$(node -e "$PORT_OWNER_JS" -- "$port" 2> /dev/null)" || owners=''

    if port_open "$port" || [ -n "$owners" ]; then
        die "$label $port is already in use${owners:+ by $owners}"
    fi
}

# Runs psql -X -w -v ON_ERROR_STOP=1 with the given arguments and fails with status 124 when it runs longer than the
# given number of seconds.
#   psql_with_deadline <seconds> <psql argument>...
psql_with_deadline() {
    local seconds="$1"

    shift
    node -e "$PSQL_DEADLINE_JS" -- "$PROGRAM" "$seconds" "$PSQL_KILL_GRACE_SECONDS" psql -X -w -v ON_ERROR_STOP=1 "$@"
}

# Runs psql against the maintenance database `postgres`, unaligned and tuples only, within PSQL_COMMAND_TIMEOUT_SECONDS.
psql_admin() {
    psql_with_deadline "$PSQL_COMMAND_TIMEOUT_SECONDS" -At -d "$ADMIN_URI" "$@"
}

# Runs psql against the acceptance database, unaligned and tuples only, within PSQL_COMMAND_TIMEOUT_SECONDS.
psql_db() {
    psql_with_deadline "$PSQL_COMMAND_TIMEOUT_SECONDS" -At -d "$DB_URI" "$@"
}

# Succeeds while the started Tomcat process runs under the unpacked CATALINA_BASE.
tomcat_alive() {
    local cmdline

    [ -n "$TOMCAT_PID" ] || return 1
    kill -0 "$TOMCAT_PID" 2> /dev/null || return 1

    if [ -r "/proc/$TOMCAT_PID/cmdline" ]; then
        cmdline="$(tr '\0' ' ' 2> /dev/null < "/proc/$TOMCAT_PID/cmdline")" || return 1

        case "$cmdline" in
            *"$CATALINA_BASE"*) return 0 ;;
            *) return 1 ;;
        esac
    fi

    return 0
}

# Exits with status 1 unless the started Tomcat runs and holds every LISTEN socket on HTTP_PORT.
#   verify_tomcat_listener <stage>    <stage> names the moment of the check in the messages
verify_tomcat_listener() {
    local stage="$1" listeners

    if ! tomcat_alive; then
        die "Tomcat (pid $TOMCAT_PID) under $CATALINA_BASE is not running $stage"
    fi

    if ! listeners="$(node -e "$LISTENER_JS" -- "$TOMCAT_PID" "$HTTP_PORT" 2>&1)"; then
        die "HTTP port $HTTP_PORT is not served by Tomcat (pid $TOMCAT_PID) $stage: $listeners"
    fi

    log "Tomcat (pid $TOMCAT_PID) holds every LISTEN socket on HTTP port $HTTP_PORT $stage: $listeners"
}

# Waits up to the given number of seconds for the started Tomcat process to exit.
# shellcheck disable=SC2317
wait_for_tomcat_exit() {
    local remaining="$1"

    while tomcat_alive; do
        if [ "$remaining" -le 0 ]; then
            return 1
        fi

        sleep 1
        remaining=$((remaining - 1))
    done

    return 0
}

# Stops the started Tomcat with catalina.sh stop, then SIGTERM, then SIGKILL, and reaps it.
# Returns 0 once the process has exited and been reaped, 1 while it still runs.
# shellcheck disable=SC2317
stop_tomcat() {
    if tomcat_alive; then
        log "stopping Tomcat (pid $TOMCAT_PID)"
        "$CATALINA_BASE/bin/catalina.sh" stop > /dev/null 2>&1 || true

        if ! wait_for_tomcat_exit "$STOP_WAIT_SECONDS"; then
            log "Tomcat still runs after ${STOP_WAIT_SECONDS} s; sending SIGTERM to pid $TOMCAT_PID"
            if tomcat_alive; then
                kill -TERM "$TOMCAT_PID" 2> /dev/null || true
            fi

            if ! wait_for_tomcat_exit "$KILL_WAIT_SECONDS"; then
                log "Tomcat still runs after SIGTERM; sending SIGKILL to pid $TOMCAT_PID"
                if tomcat_alive; then
                    kill -KILL "$TOMCAT_PID" 2> /dev/null || true
                fi
                wait_for_tomcat_exit "$KILL_WAIT_SECONDS" || true
            fi
        fi
    fi

    if tomcat_alive; then
        printf '%s: error: Tomcat (pid %s) is still running after catalina.sh stop, SIGTERM and SIGKILL\n' \
            "$PROGRAM" "$TOMCAT_PID" >&2
        return 1
    fi

    wait "$TOMCAT_PID" 2> /dev/null || true
    log 'Tomcat stopped'
    return 0
}

# Prints the Tomcat log tail on failure, stops Tomcat, and removes the work directory once Tomcat has stopped.
# Keeps the work directory while Tomcat still runs. Exits with the original status, or with 1 in place of 0 when
# Tomcat still runs or the work directory still exists.
# shellcheck disable=SC2317
cleanup() {
    local status=$? cleanup_failed=0 remove_status

    if [ "$#" -gt 0 ]; then
        status="$1"
    fi

    trap - EXIT INT TERM
    set +e

    if [ "$status" -ne 0 ] && [ -n "$TOMCAT_LOG" ] && [ -f "$TOMCAT_LOG" ]; then
        printf '%s: last %s lines of %s:\n' "$PROGRAM" "$LOG_TAIL_LINES" "$TOMCAT_LOG" >&2
        tail -n "$LOG_TAIL_LINES" "$TOMCAT_LOG" >&2
    fi

    if [ -n "$TOMCAT_PID" ] && ! stop_tomcat; then
        printf '%s: error: Tomcat (pid %s) is still running; kept work directory %s and Tomcat log %s\n' \
            "$PROGRAM" "$TOMCAT_PID" "$WORK_DIR" "$TOMCAT_LOG" >&2
        cleanup_failed=1
    elif [ -n "$WORK_DIR" ] && { [ -e "$WORK_DIR" ] || [ -L "$WORK_DIR" ]; }; then
        rm -rf -- "$WORK_DIR"
        remove_status=$?

        if [ "$remove_status" -ne 0 ]; then
            printf '%s: error: cannot remove work directory %s: rm -rf exited with status %s\n' \
                "$PROGRAM" "$WORK_DIR" "$remove_status" >&2
            cleanup_failed=1
        elif [ -e "$WORK_DIR" ] || [ -L "$WORK_DIR" ]; then
            printf '%s: error: work directory %s still exists after rm -rf\n' "$PROGRAM" "$WORK_DIR" >&2
            cleanup_failed=1
        fi
    fi

    if [ "$cleanup_failed" -ne 0 ] && [ "$status" -eq 0 ]; then
        status=1
    fi

    exit "$status"
}

trap cleanup EXIT
trap 'cleanup 130' INT
trap 'cleanup 143' TERM

# ---------------------------------------------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------------------------------------------

DIST_ARG=''
USER_ARG=''
PASSWORD_ARG=''
DB_NAME_ARG=''
HTTP_PORT_ARG=''
SHUTDOWN_PORT_ARG=''
WORK_ROOT_ARG=''

# Parses `--name value` and `--name=value` arguments.
while [ "$#" -gt 0 ]; do
    argument="$1"
    shift

    case "$argument" in
        -h | --help)
            usage 1
            exit 0
            ;;
        --*=*)
            name="${argument%%=*}"
            value="${argument#*=}"
            has_value=1
            ;;
        --*)
            name="$argument"
            value=''
            has_value=0
            ;;
        *)
            usage_error "unexpected argument $argument"
            ;;
    esac

    case "$name" in
        --dist | --user | --password | --db-name | --http-port | --shutdown-port | --work-root) ;;
        *) usage_error "unknown argument $name" ;;
    esac

    if [ "$has_value" -eq 0 ]; then
        if [ "$#" -eq 0 ] || [[ "$1" == --* ]]; then
            usage_error "missing value of $name"
        fi

        value="$1"
        shift
    fi

    if [ -z "$value" ]; then
        usage_error "missing value of $name"
    fi

    case "$name" in
        --dist) DIST_ARG="$value" ;;
        --user) USER_ARG="$value" ;;
        --password) PASSWORD_ARG="$value" ;;
        --db-name) DB_NAME_ARG="$value" ;;
        --http-port) HTTP_PORT_ARG="$value" ;;
        --shutdown-port) SHUTDOWN_PORT_ARG="$value" ;;
        --work-root) WORK_ROOT_ARG="$value" ;;
    esac
done

[ -n "$DIST_ARG" ] || usage_error 'missing argument --dist'
[ -n "$USER_ARG" ] || usage_error 'missing argument --user'
[ -n "$PASSWORD_ARG" ] || usage_error 'missing argument --password'

if [ -n "$DB_NAME_ARG" ] && ! is_db_name "$DB_NAME_ARG"; then
    usage_error "--db-name must match ^[A-Za-z_][A-Za-z0-9_]*\$ and hold at most 63 characters, got $DB_NAME_ARG"
fi

if [ -n "$HTTP_PORT_ARG" ]; then
    is_port "$HTTP_PORT_ARG" || usage_error "--http-port must be a port from 1 to 65535, got $HTTP_PORT_ARG"
    HTTP_PORT_ARG="$((10#$HTTP_PORT_ARG))"
fi

if [ -n "$SHUTDOWN_PORT_ARG" ]; then
    is_port "$SHUTDOWN_PORT_ARG" || usage_error "--shutdown-port must be a port from 1 to 65535, got $SHUTDOWN_PORT_ARG"
    SHUTDOWN_PORT_ARG="$((10#$SHUTDOWN_PORT_ARG))"
fi

if [ -n "$HTTP_PORT_ARG" ] && [ "$HTTP_PORT_ARG" = "$SHUTDOWN_PORT_ARG" ]; then
    usage_error "--http-port and --shutdown-port must differ, both are $HTTP_PORT_ARG"
fi

# Resolves the distribution against the caller's working directory.
DIST="$(absolute_path "$DIST_ARG")"

if [ -d "$(dirname "$DIST")" ]; then
    DIST="$(cd "$(dirname "$DIST")" && pwd)/$(basename "$DIST")"
fi

case "${DIST##*/}" in
    *[*?[]*) usage_error "--dist file name ${DIST##*/} holds * ? or [, which unzip matches as a pattern; rename the file" ;;
esac

[ -f "$DIST" ] || die "distribution $DIST does not exist; build it with (cd mes/mes-application && mvn -B -Ptomcat clean install)"
[ -r "$DIST" ] || die "distribution $DIST is not readable"

# ---------------------------------------------------------------------------------------------------------------
# Repository layout
# ---------------------------------------------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../../.." && pwd)"
REPO_DB_PROPERTIES="$APP_DIR/conf/tomcat/db.properties"
SEED_SQL="$APP_DIR/src/main/resources/schema/mes_db_en.sql"
FIXTURE_SQL="$SCRIPT_DIR/fixture.sql"
RUNNER_JS="$SCRIPT_DIR/acceptance.test.js"

[ -f "$APP_DIR/pom.xml" ] || die "$APP_DIR/pom.xml not found; run-acceptance.sh must stay in mes/mes-application/src/test/acceptance/productionMaintenanceGantt"
[ -f "$REPO_DB_PROPERTIES" ] || die "$REPO_DB_PROPERTIES not found"
[ "$REPO_ROOT/mes/mes-application" -ef "$APP_DIR" ] || die "$REPO_ROOT does not hold mes/mes-application at $APP_DIR"
[ -d "$REPO_ROOT/qcadoo/qcadoo-view" ] || die "$REPO_ROOT/qcadoo/qcadoo-view not found; run git submodule update --init --recursive"
[ -f "$SEED_SQL" ] || die "$SEED_SQL not found"
[ -f "$FIXTURE_SQL" ] || die "$FIXTURE_SQL not found"
[ -f "$RUNNER_JS" ] || die "$RUNNER_JS not found"

seed_first_line=''
IFS= read -r seed_first_line < "$SEED_SQL" || true

case "$seed_first_line" in
    'version https://git-lfs'*)
        die "$SEED_SQL is a Git LFS pointer; run git lfs pull -I mes-application/src/main/resources/schema/mes_db_en.sql in mes"
        ;;
esac

# Resolves the physical path of the repository root.
REPO_ROOT="$(physical_dir "$REPO_ROOT")" || die "cannot resolve the physical path of the repository root $REPO_ROOT"

# Checks --work-root is an existing directory outside the repository whose physical path holds only safe characters.
WORK_ROOT=''

if [ -n "$WORK_ROOT_ARG" ]; then
    [ -d "$WORK_ROOT_ARG" ] || usage_error "--work-root $WORK_ROOT_ARG is not an existing directory; pass $WORK_ROOT_REQUIREMENT"
    WORK_ROOT="$(physical_dir "$WORK_ROOT_ARG")" \
        || usage_error "cannot resolve the physical path of --work-root $WORK_ROOT_ARG; pass $WORK_ROOT_REQUIREMENT"
    work_root_problem="$(work_path_problem "$WORK_ROOT")"

    if [ -n "$work_root_problem" ]; then
        usage_error "--work-root $WORK_ROOT_ARG cannot hold the work directory: $work_root_problem; pass $WORK_ROOT_REQUIREMENT"
    fi
fi

# ---------------------------------------------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------------------------------------------

# Rewrites every relative or empty PATH entry as an absolute path below the current working directory.
caller_dir=''
canonical_path=''
path_separator=''
path_rest="$PATH"

while :; do
    path_entry="${path_rest%%:*}"

    if [[ "$path_entry" != /* ]]; then
        if [ -z "$caller_dir" ]; then
            caller_dir="$(pwd -P)" || die "cannot resolve the current working directory for the relative PATH entry '$path_entry'"
            [ "$caller_dir" -ef . ] || die "cannot resolve the current working directory for the relative PATH entry '$path_entry'"
        fi

        if [[ "$caller_dir" == *:* ]]; then
            die "PATH entry '$path_entry' is relative to the working directory $caller_dir, which holds ':'; make the entry absolute or run from another directory"
        fi

        path_entry="$caller_dir${path_entry:+/$path_entry}"
    fi

    canonical_path+="$path_separator$path_entry"
    path_separator=':'

    case "$path_rest" in
        *:*) path_rest="${path_rest#*:}" ;;
        *) break ;;
    esac
done

PATH="$canonical_path"
export PATH

missing_tools=()

for tool in "${REQUIRED_TOOLS[@]}"; do
    if ! command -v "$tool" > /dev/null 2>&1; then
        missing_tools+=("$tool")
    fi
done

if [ "${#missing_tools[@]}" -gt 0 ]; then
    die "required tools missing from PATH: ${missing_tools[*]}"
fi

NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"

if ! [[ "$NODE_MAJOR" =~ ^[0-9]+$ ]] || [ "$NODE_MAJOR" -lt "$MIN_NODE_MAJOR" ]; then
    die "node $MIN_NODE_MAJOR or later is required, found $(node --version)"
fi

# Chooses the first day 2 to 13 days ahead whose UTC offset is the same at every minute from its midnight to the
# midnight 9 days later.
BASE_DAY_WINDOW="every minute from its midnight to the midnight $((BASE_DAY_FOLLOWING_DAYS + 1)) days later"
base_day_line="$(node -e "$BASE_DAY_JS" -- "$BASE_DAY_FIRST_AHEAD" "$BASE_DAY_LAST_AHEAD" "$BASE_DAY_FOLLOWING_DAYS")" \
    || die 'cannot compute the base day with node'
read -r BASE_DAY BASE_DAY_ZONE <<< "$base_day_line"

if [ -z "$BASE_DAY" ]; then
    die "no day $BASE_DAY_FIRST_AHEAD to $BASE_DAY_LAST_AHEAD days ahead has a UTC offset that stays the same at $BASE_DAY_WINDOW"
fi

log "base day $BASE_DAY (UTC offset $BASE_DAY_ZONE at $BASE_DAY_WINDOW)"

# Resolves chrome-headless-shell on PATH to an absolute path in its physical directory.
CHROME="$(type -P chrome-headless-shell)" || die 'chrome-headless-shell is missing from PATH'
chrome_dir="$(physical_dir "${CHROME%/*}/")" || die "cannot resolve the physical directory of $CHROME"
CHROME="$chrome_dir/${CHROME##*/}"

if [ ! -f "$CHROME" ] || [ ! -x "$CHROME" ]; then
    die "chrome-headless-shell at $CHROME is not an executable regular file"
fi

log "node $(node --version), $(psql --version), chrome-headless-shell at $CHROME"

# Creates the temporary work directory and checks its physical path like --work-root.
if [ -n "$WORK_ROOT" ]; then
    WORK_DIR="$(mktemp -d -p "$WORK_ROOT" pmg-acceptance.XXXXXX)" || die "cannot create a work directory in $WORK_ROOT"
else
    WORK_DIR="$(mktemp -d -t pmg-acceptance.XXXXXX)" \
        || die "mktemp -d -t cannot create a work directory; pass --work-root <$WORK_ROOT_REQUIREMENT>"
fi

work_dir_physical="$(physical_dir "$WORK_DIR")" || die "cannot resolve the physical path of the work directory $WORK_DIR"
work_dir_problem="$(work_path_problem "$work_dir_physical")"

if [ -n "$work_dir_problem" ]; then
    die "the work directory $WORK_DIR cannot hold the distribution: $work_dir_problem; pass --work-root <$WORK_ROOT_REQUIREMENT>"
fi

WORK_DIR="$work_dir_physical"

log "work directory $WORK_DIR"


# ---------------------------------------------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------------------------------------------

# Reads the connection settings of conf/tomcat/db.properties.
REPO_DB_URL="$(property_value "$REPO_DB_PROPERTIES" dbJdbcUrl)" || die "dbJdbcUrl is missing in $REPO_DB_PROPERTIES"
REPO_DB_USER="$(property_value "$REPO_DB_PROPERTIES" dbUsername)" || die "dbUsername is missing in $REPO_DB_PROPERTIES"
REPO_DB_PASSWORD="$(property_value "$REPO_DB_PROPERTIES" dbPassword)" || die "dbPassword is missing in $REPO_DB_PROPERTIES"

[ -n "$REPO_DB_URL" ] || die "dbJdbcUrl is empty in $REPO_DB_PROPERTIES"
[ -n "$REPO_DB_USER" ] || die "dbUsername is empty in $REPO_DB_PROPERTIES"
[ -n "$REPO_DB_PASSWORD" ] || die "dbPassword is empty in $REPO_DB_PROPERTIES"

# Rejects a dbJdbcUrl with JDBC query options.
case "$REPO_DB_URL" in
    *'?'*)
        die "dbJdbcUrl in $REPO_DB_PROPERTIES carries JDBC query options; psql cannot apply them the same way, so use jdbc:postgresql:DB, jdbc:postgresql://HOST/DB or jdbc:postgresql://HOST:PORT/DB"
        ;;
esac

# Checks the db.properties entry of the distribution holds a dbPassword that is not empty, reading it from the archive.
dist_entries="$(unzip -Z1 "$DIST" < /dev/null)" || die "cannot list the entries of $DIST"
dist_db_properties_entries=()

while IFS= read -r dist_entry; do
    case "$dist_entry" in
        db.properties | */db.properties) dist_db_properties_entries+=("$dist_entry") ;;
    esac
done <<< "$dist_entries"

if [ "${#dist_db_properties_entries[@]}" -ne 1 ]; then
    die "expected exactly one db.properties in $DIST, found ${#dist_db_properties_entries[@]}"
fi

DIST_DB_PROPERTIES_ENTRY="${dist_db_properties_entries[0]}"

case "$DIST_DB_PROPERTIES_ENTRY" in
    *[][*?\\]*) die "the db.properties entry $DIST_DB_PROPERTIES_ENTRY of $DIST has a wildcard character in its name" ;;
esac

dist_db_properties_text="$(unzip -p "$DIST" "$DIST_DB_PROPERTIES_ENTRY" < /dev/null)" \
    || die "cannot read $DIST_DB_PROPERTIES_ENTRY from $DIST"
PACKAGED_DB_PASSWORD="$(property_value /dev/stdin dbPassword <<< "$dist_db_properties_text")" \
    || die "dbPassword is missing in $DIST_DB_PROPERTIES_ENTRY of $DIST"

[ -n "$PACKAGED_DB_PASSWORD" ] || die "dbPassword is empty in $DIST_DB_PROPERTIES_ENTRY of $DIST"

# Splits dbJdbcUrl into host, port and database.
readonly JDBC_HOST_URL_RE='^jdbc:postgresql://([^/:]+)(:([0-9]+))?/([^/]*)$'
readonly JDBC_LOCAL_URL_RE='^jdbc:postgresql:([^/].*)$'

if [[ "$REPO_DB_URL" =~ $JDBC_HOST_URL_RE ]]; then
    DB_HOST="${BASH_REMATCH[1]}"
    DB_PORT="${BASH_REMATCH[3]:-$DEFAULT_DB_PORT}"
    REPO_DB_NAME="${BASH_REMATCH[4]}"
elif [[ "$REPO_DB_URL" =~ $JDBC_LOCAL_URL_RE ]]; then
    DB_HOST="$DEFAULT_DB_HOST"
    DB_PORT="$DEFAULT_DB_PORT"
    REPO_DB_NAME="${BASH_REMATCH[1]}"
else
    die "dbJdbcUrl $REPO_DB_URL is not jdbc:postgresql:DB, jdbc:postgresql://HOST/DB or jdbc:postgresql://HOST:PORT/DB"
fi

is_port "$DB_PORT" || die "dbJdbcUrl $REPO_DB_URL has port $DB_PORT outside 1 to 65535"

DB_NAME="${DB_NAME_ARG:-$REPO_DB_NAME}"

is_db_name "$DB_NAME" || die "database name '$DB_NAME' does not match ^[A-Za-z_][A-Za-z0-9_]*\$ or is longer than 63 characters"

# Builds the psql connection URIs.
ENCODED_DB_USER="$(url_encode "$REPO_DB_USER")"
ENCODED_DB_PASSWORD="$(url_encode "$REPO_DB_PASSWORD")"
PSQL_URI_OPTIONS="connect_timeout=$PSQL_CONNECT_TIMEOUT_SECONDS"
DB_URI="postgresql://$ENCODED_DB_USER:$ENCODED_DB_PASSWORD@$DB_HOST:$DB_PORT/$DB_NAME?$PSQL_URI_OPTIONS"
ADMIN_URI="postgresql://$ENCODED_DB_USER:$ENCODED_DB_PASSWORD@$DB_HOST:$DB_PORT/postgres?$PSQL_URI_OPTIONS"

# Checks the PostgreSQL server answers.
log "checking PostgreSQL at $DB_HOST:$DB_PORT as $REPO_DB_USER"

if ! server_check="$(psql_admin -c 'SELECT 1' 2>&1)" || [ "$server_check" != '1' ]; then
    die "PostgreSQL at $DB_HOST:$DB_PORT is unreachable as $REPO_DB_USER: $server_check"
fi

# Takes the run guard of the acceptance database: a psql session on the maintenance database takes the advisory lock
# of RUN_GUARD_KEY and holds it until the script exits and closes the session's standard input.
readonly RUN_GUARD_KEY="$MARKER:$DB_NAME"
readonly RUN_GUARD_URI="$ADMIN_URI&application_name=$RUN_GUARD_APPLICATION"

log "taking the run guard of database $DB_NAME"

coproc RUN_GUARD {
    exec setsid --wait psql -X -w -q -At -v ON_ERROR_STOP=1 -d "$RUN_GUARD_URI" \
        -c 'SET idle_session_timeout = 0' \
        -c "SELECT pg_try_advisory_lock(hashtextextended('$RUN_GUARD_KEY', 0))" \
        -f - 2>&1
}

run_guard_output_fd=''
run_guard_answer=''
run_guard_status=1

if [ -n "${RUN_GUARD[0]:-}" ]; then
    exec {run_guard_output_fd}<&"${RUN_GUARD[0]}"
    run_guard_status=0
    IFS= read -r -t "$RUN_GUARD_WAIT_SECONDS" run_guard_answer <&"$run_guard_output_fd" || run_guard_status=$?
    exec {run_guard_output_fd}<&-
fi

case "$run_guard_answer" in
    t)
        log "holding the run guard of database $DB_NAME ($RUN_GUARD_APPLICATION on the maintenance database)"
        ;;
    f)
        run_guard_holders="$(psql_admin -c "SELECT string_agg('pid ' || a.pid || ' ('
                || coalesce(nullif(a.application_name, ''), 'no application name') || ')', ', ' ORDER BY a.pid)
            FROM pg_catalog.pg_locks l JOIN pg_catalog.pg_stat_activity a ON a.pid = l.pid
            WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 1
                AND l.database = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())
                AND ((l.classid::bigint << 32) | l.objid::bigint) = hashtextextended('$RUN_GUARD_KEY', 0)")" \
            || run_guard_holders=''
        die "database $DB_NAME is in use by another acceptance run: its run guard is held by ${run_guard_holders:-a session that has ended or cannot be read}"
        ;;
    *)
        if [ "$run_guard_status" -gt 128 ]; then
            die "the run guard session of database $DB_NAME gave no answer within $RUN_GUARD_WAIT_SECONDS s${run_guard_answer:+: $run_guard_answer}"
        fi

        die "cannot take the run guard of database $DB_NAME: ${run_guard_answer:-the guard session ended without an answer}"
        ;;
esac

# ---------------------------------------------------------------------------------------------------------------
# Distribution
# ---------------------------------------------------------------------------------------------------------------

# Checks the members of the distribution.
log "checking the members of $DIST"
DIST_FILE_COUNT="$(node -e "$ZIP_INSPECT_JS" -- "$DIST")" \
    || die "$DIST holds members that cannot be unpacked safely; rebuild the distribution"

[[ "$DIST_FILE_COUNT" =~ ^[0-9]+$ ]] || die "cannot count the regular files of $DIST, got '$DIST_FILE_COUNT'"

# Unpacks the distribution without overwriting and without reading standard input.
DIST_DIR="$WORK_DIR/dist"

log "unpacking $DIST into $DIST_DIR"
mkdir -- "$DIST_DIR"
unzip -q -n "$DIST" -d "$DIST_DIR" < /dev/null || die "cannot unpack $DIST into $DIST_DIR"

# Checks the unpacked tree holds only directories and the regular files the distribution lists.
unpacked_others=()
find_paths unpacked_others "$DIST_DIR" ! -type f ! -type d

if [ "${#unpacked_others[@]}" -gt 0 ]; then
    die "unpacked distribution holds ${#unpacked_others[@]} entries that are neither regular files nor directories, the first ${unpacked_others[0]}; rebuild the distribution"
fi

unpacked_files=()
find_paths unpacked_files "$DIST_DIR" -type f

if [ "${#unpacked_files[@]}" -ne "$DIST_FILE_COUNT" ]; then
    die "unpacked distribution holds ${#unpacked_files[@]} regular files, not the $DIST_FILE_COUNT regular files $DIST lists"
fi

# Finds the one catalina.sh of the distribution.
catalina_scripts=()
find_paths catalina_scripts "$DIST_DIR" -type f -path '*/bin/catalina.sh'

if [ "${#catalina_scripts[@]}" -ne 1 ]; then
    die "expected exactly one */bin/catalina.sh in $DIST, found ${#catalina_scripts[@]}"
fi

CATALINA_BASE="$(physical_dir "${catalina_scripts[0]%/bin/catalina.sh}")" \
    || die "cannot resolve the physical directory above ${catalina_scripts[0]}"

if ! is_safe_path "$CATALINA_BASE" || ! is_within "$CATALINA_BASE" "$DIST_DIR"; then
    die "CATALINA_BASE $CATALINA_BASE of the unpacked distribution is not a directory of $DIST_DIR whose path holds only $SAFE_PATH_CHARACTERS; rebuild the distribution"
fi

CATALINA_HOME="$CATALINA_BASE"
export CATALINA_BASE CATALINA_HOME

for bin_script in "$CATALINA_BASE"/bin/*.sh; do
    require_distribution_file "$bin_script"
    chmod +x -- "$bin_script"
done

# Checks the distribution's db.properties holds the connection settings of conf/tomcat/db.properties.
dist_db_properties=()
find_paths dist_db_properties "$DIST_DIR" -type f -name db.properties

if [ "${#dist_db_properties[@]}" -ne 1 ]; then
    die "expected exactly one db.properties in $DIST, found ${#dist_db_properties[@]}"
fi

DIST_DB_PROPERTIES="${dist_db_properties[0]}"
require_distribution_file "$DIST_DB_PROPERTIES"

DIST_DB_URL="$(property_value "$DIST_DB_PROPERTIES" dbJdbcUrl)" || die "dbJdbcUrl is missing in $DIST_DB_PROPERTIES"
DIST_DB_USER="$(property_value "$DIST_DB_PROPERTIES" dbUsername)" || die "dbUsername is missing in $DIST_DB_PROPERTIES"
DIST_DB_PASSWORD="$(property_value "$DIST_DB_PROPERTIES" dbPassword)" || die "dbPassword is missing in $DIST_DB_PROPERTIES"

if [ "$DIST_DB_URL" != "$REPO_DB_URL" ]; then
    die "dbJdbcUrl of the distribution is $DIST_DB_URL, not $REPO_DB_URL of $REPO_DB_PROPERTIES; rebuild the distribution"
fi

if [ "$DIST_DB_USER" != "$REPO_DB_USER" ]; then
    die "dbUsername of the distribution is $DIST_DB_USER, not $REPO_DB_USER of $REPO_DB_PROPERTIES; rebuild the distribution"
fi

if [ "$DIST_DB_PASSWORD" != "$REPO_DB_PASSWORD" ]; then
    die "dbPassword of the distribution differs from $REPO_DB_PROPERTIES; rebuild the distribution"
fi

# Points the unpacked distribution at the acceptance database when it is not the database of dbJdbcUrl.
if [ "$DB_NAME" != "$REPO_DB_NAME" ]; then
    ACCEPTANCE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
    set_property_value "$DIST_DB_PROPERTIES" dbJdbcUrl "$ACCEPTANCE_DB_URL"

    if [ "$(property_value "$DIST_DB_PROPERTIES" dbJdbcUrl)" != "$ACCEPTANCE_DB_URL" ]; then
        die "cannot set dbJdbcUrl=$ACCEPTANCE_DB_URL in $DIST_DB_PROPERTIES"
    fi

    log "unpacked distribution uses dbJdbcUrl=$ACCEPTANCE_DB_URL"
fi

# Serves static resources from the packaged jars: sets hotDeploy=false in the app.properties next to db.properties.
DIST_APP_PROPERTIES="$(dirname "$DIST_DB_PROPERTIES")/app.properties"

[ -f "$DIST_APP_PROPERTIES" ] || die "$DIST_APP_PROPERTIES not found"
require_distribution_file "$DIST_APP_PROPERTIES"

if property_value "$DIST_APP_PROPERTIES" hotDeploy > /dev/null; then
    set_property_value "$DIST_APP_PROPERTIES" hotDeploy false
else
    printf '\nhotDeploy=false\n' >> "$DIST_APP_PROPERTIES"
fi

if [ "$(property_value "$DIST_APP_PROPERTIES" hotDeploy)" != 'false' ]; then
    die "cannot set hotDeploy=false in $DIST_APP_PROPERTIES"
fi

log 'unpacked distribution uses hotDeploy=false'

# Reads the HTTP and shutdown ports of conf/server.xml.
SERVER_XML="$CATALINA_BASE/conf/server.xml"

[ -f "$SERVER_XML" ] || die "$SERVER_XML not found"
require_distribution_file "$SERVER_XML"

server_ports="$(node -e "$SERVER_XML_JS" -- ports "$SERVER_XML")" || die "cannot read the ports of $SERVER_XML"
read -r XML_HTTP_PORT XML_SHUTDOWN_PORT <<< "$server_ports"

if [ "$XML_HTTP_PORT" = '-' ]; then
    XML_HTTP_PORT="$DEFAULT_HTTP_PORT"
fi

if [ "$XML_SHUTDOWN_PORT" = '-' ]; then
    XML_SHUTDOWN_PORT="$DEFAULT_SHUTDOWN_PORT"
fi

# Compares the ports as decimal numbers.
XML_HTTP_PORT="$(normalize_port_value "$XML_HTTP_PORT")"
XML_SHUTDOWN_PORT="$(normalize_port_value "$XML_SHUTDOWN_PORT")"

HTTP_PORT="${HTTP_PORT_ARG:-$XML_HTTP_PORT}"
SHUTDOWN_PORT="${SHUTDOWN_PORT_ARG:-$XML_SHUTDOWN_PORT}"

is_port "$HTTP_PORT" || die "HTTP port $HTTP_PORT of $SERVER_XML is outside 1 to 65535"

if ! is_port "$SHUTDOWN_PORT" && ! [[ "$SHUTDOWN_PORT" =~ ^-[0-9]{1,5}$ ]]; then
    die "shutdown port $SHUTDOWN_PORT of $SERVER_XML is neither from 1 to 65535 nor a negative value that disables it"
fi

if [ "$HTTP_PORT" = "$SHUTDOWN_PORT" ]; then
    if [ -n "$HTTP_PORT_ARG" ]; then
        usage_error "--http-port $HTTP_PORT_ARG equals the shutdown port $XML_SHUTDOWN_PORT of the distribution's conf/server.xml; pass --shutdown-port with another port"
    elif [ -n "$SHUTDOWN_PORT_ARG" ]; then
        usage_error "--shutdown-port $SHUTDOWN_PORT_ARG equals the HTTP port $XML_HTTP_PORT of the distribution's conf/server.xml; pass --http-port with another port"
    fi

    die "HTTP port and shutdown port of $SERVER_XML are both $HTTP_PORT"
fi

# Writes the requested ports into the unpacked conf/server.xml.
if [ "$HTTP_PORT" != "$XML_HTTP_PORT" ] || [ "$SHUTDOWN_PORT" != "$XML_SHUTDOWN_PORT" ]; then
    node -e "$SERVER_XML_JS" -- rewrite "$SERVER_XML" "$HTTP_PORT" "$SHUTDOWN_PORT" \
        || die "cannot write HTTP port $HTTP_PORT and shutdown port $SHUTDOWN_PORT into $SERVER_XML"

    rewritten_ports="$(node -e "$SERVER_XML_JS" -- ports "$SERVER_XML")" || die "cannot read the ports of $SERVER_XML"

    if [ "$rewritten_ports" != "$HTTP_PORT $SHUTDOWN_PORT" ]; then
        die "$SERVER_XML holds ports '$rewritten_ports' after rewriting, not '$HTTP_PORT $SHUTDOWN_PORT'"
    fi

    log "unpacked distribution uses HTTP port $HTTP_PORT and shutdown port $SHUTDOWN_PORT"
fi

# Checks the HTTP and shutdown ports are free and names the processes listening on a busy one.
require_free_port 'HTTP port' "$HTTP_PORT"

if is_port "$SHUTDOWN_PORT"; then
    require_free_port 'shutdown port' "$SHUTDOWN_PORT"
    log "HTTP port $HTTP_PORT and shutdown port $SHUTDOWN_PORT are free"
else
    log "HTTP port $HTTP_PORT is free"
fi

# ---------------------------------------------------------------------------------------------------------------
# Acceptance database
# ---------------------------------------------------------------------------------------------------------------

# Recreates the marked acceptance database.
existing_database="$(psql_admin -c "SELECT 'present:' || coalesce(shobj_description(oid, 'pg_database'), '')
    FROM pg_catalog.pg_database WHERE datname = '$DB_NAME'")"

if [ -n "$existing_database" ]; then
    existing_comment="${existing_database#present:}"

    if [ "$existing_comment" != "$MARKER" ]; then
        die "refusing to drop database $DB_NAME: its comment is '$existing_comment', not the acceptance marker '$MARKER'"
    fi

    log "dropping acceptance database $DB_NAME"
    psql_admin -q -c "DROP DATABASE \"$DB_NAME\" WITH (FORCE)"
fi

log "creating acceptance database $DB_NAME"
psql_admin -q -c "CREATE DATABASE \"$DB_NAME\" ENCODING 'UTF8' TEMPLATE template0" \
    -c "COMMENT ON DATABASE \"$DB_NAME\" IS '$MARKER'"

# Loads the seed.
log "loading $SEED_SQL into $DB_NAME"
psql_with_deadline "$PSQL_SEED_TIMEOUT_SECONDS" -q -d "$DB_URI" -f "$SEED_SQL" > "$WORK_DIR/seed.log"
log 'seed loaded'

# Loads the fixture.
FIXTURE_LOG="$WORK_DIR/fixture.log"

log "loading $FIXTURE_SQL into $DB_NAME"
psql_with_deadline "$PSQL_FIXTURE_TIMEOUT_SECONDS" -v base_day="$BASE_DAY" -d "$DB_URI" -f "$FIXTURE_SQL" 2>&1 \
    | tee "$FIXTURE_LOG"
log 'fixture loaded'

# Checks the plugins the suite needs are enabled.
enabled_plugins="$(psql_db -c "SELECT count(*) FROM public.qcadooplugin_plugin WHERE state = 'ENABLED'
    AND identifier IN ('productionScheduling','lineChangeoverNormsForOrders','cmmsMachineParts')")"

if [ "$enabled_plugins" != "${#REQUIRED_PLUGINS[@]}" ]; then
    missing_plugins="$(psql_db -c "SELECT string_agg(required.identifier, ', ' ORDER BY required.identifier)
        FROM (VALUES ('productionScheduling'), ('lineChangeoverNormsForOrders'), ('cmmsMachineParts')) AS required (identifier)
        WHERE NOT EXISTS (SELECT 1 FROM public.qcadooplugin_plugin plugin
            WHERE plugin.identifier = required.identifier AND plugin.state = 'ENABLED')")"
    die "plugins not enabled in database $DB_NAME: $missing_plugins"
fi

log "plugins ${REQUIRED_PLUGINS[*]} are enabled"


# ---------------------------------------------------------------------------------------------------------------
# Tomcat
# ---------------------------------------------------------------------------------------------------------------

# Checks the ports are free.
if port_open "$HTTP_PORT"; then
    die "HTTP port $HTTP_PORT is already in use"
fi

if is_port "$SHUTDOWN_PORT" && port_open "$SHUTDOWN_PORT"; then
    die "shutdown port $SHUTDOWN_PORT is already in use"
fi

# Starts Tomcat in the foreground of a background job and records its pid in CATALINA_PID of bin/setenv.sh.
BASE_URL="http://localhost:$HTTP_PORT"
TOMCAT_LOG="$WORK_DIR/tomcat.log"

log "starting Tomcat from $CATALINA_BASE on $BASE_URL (log $TOMCAT_LOG)"
"$CATALINA_BASE/bin/catalina.sh" run > "$TOMCAT_LOG" 2>&1 &
TOMCAT_PID=$!
printf '%s\n' "$TOMCAT_PID" > "$CATALINA_BASE/catalina.pid"

# Waits for the login page.
startup_began=$SECONDS
next_report="$STARTUP_REPORT_SECONDS"

until curl -q -fsS -o /dev/null --max-time 10 --noproxy '*' "$BASE_URL/login.html" 2> /dev/null; do
    elapsed=$((SECONDS - startup_began))

    if ! tomcat_alive; then
        die "Tomcat exited after ${elapsed} s before $BASE_URL/login.html answered"
    fi

    if [ "$elapsed" -ge "$STARTUP_TIMEOUT_SECONDS" ]; then
        die "$BASE_URL/login.html did not answer within $STARTUP_TIMEOUT_SECONDS s"
    fi

    if [ "$elapsed" -ge "$next_report" ]; then
        log "waiting for $BASE_URL/login.html: ${elapsed} s"
        next_report=$(((elapsed / STARTUP_REPORT_SECONDS + 1) * STARTUP_REPORT_SECONDS))
    fi

    sleep "$STARTUP_POLL_SECONDS"
done

log "Tomcat answered $BASE_URL/login.html after $((SECONDS - startup_began)) s"

# Checks the started Tomcat holds the HTTP listener that answered.
verify_tomcat_listener "after $BASE_URL/login.html answered"

# ---------------------------------------------------------------------------------------------------------------
# Runner and TAP gate
# ---------------------------------------------------------------------------------------------------------------

TAP_LOG="$WORK_DIR/acceptance.tap"

log "running acceptance.test.js against $BASE_URL with base day $BASE_DAY"
cd "$SCRIPT_DIR"

set +e
node --test-reporter=tap acceptance.test.js --base-url="$BASE_URL" --user="$USER_ARG" --password="$PASSWORD_ARG" \
    --db-uri="$DB_URI" --chrome="$CHROME" --base-day="$BASE_DAY" | tee "$TAP_LOG"
pipe_status=("${PIPESTATUS[@]}")
set -e

RUNNER_STATUS="${pipe_status[0]}"
TEE_STATUS="${pipe_status[1]}"

if [ "$RUNNER_STATUS" -ne 0 ]; then
    printf '%s: error: acceptance.test.js exited with status %s\n' "$PROGRAM" "$RUNNER_STATUS" >&2
    exit "$RUNNER_STATUS"
fi

if [ "$TEE_STATUS" -ne 0 ]; then
    die "tee exited with status $TEE_STATUS while writing the TAP output to $TAP_LOG and to standard output"
fi

# Checks the started Tomcat still holds the HTTP listener.
verify_tomcat_listener 'after acceptance.test.js finished'

# Prints the value of the last `# <name> <number>` summary line of the TAP log, or nothing.
tap_summary() {
    awk -v name="$1" '$1 == "#" && $2 == name && NF == 3 && $3 ~ /^[0-9]+$/ { value = $3 } END { print value }' \
        "$TAP_LOG"
}

TAP_TESTS="$(tap_summary tests)"
TAP_PASS="$(tap_summary pass)"
TAP_FAIL="$(tap_summary fail)"
TAP_CANCELLED="$(tap_summary cancelled)"
TAP_SKIPPED="$(tap_summary skipped)"
TAP_TODO="$(tap_summary todo)"

gate_failures=()

if ! grep -q '^TAP version' "$TAP_LOG"; then
    gate_failures+=('the TAP log holds no "TAP version" line')
fi

for summary in "fail=$TAP_FAIL" "cancelled=$TAP_CANCELLED" "skipped=$TAP_SKIPPED" "todo=$TAP_TODO"; do
    summary_name="${summary%%=*}"
    summary_value="${summary#*=}"

    if [ -z "$summary_value" ]; then
        gate_failures+=("the TAP log holds no '# $summary_name' summary line")
    elif [ "$summary_value" -ne 0 ]; then
        gate_failures+=("# $summary_name is $summary_value, expected 0")
    fi
done

if [ -z "$TAP_TESTS" ]; then
    gate_failures+=("the TAP log holds no '# tests' summary line")
elif [ "$TAP_TESTS" -le 0 ]; then
    gate_failures+=('# tests is 0')
fi

if [ -z "$TAP_PASS" ]; then
    gate_failures+=("the TAP log holds no '# pass' summary line")
elif [ -n "$TAP_TESTS" ] && [ "$TAP_PASS" -ne "$TAP_TESTS" ]; then
    gate_failures+=("# pass is $TAP_PASS, not # tests $TAP_TESTS")
fi

for case_id in "${EXPECTED_CASES[@]}"; do
    if ! grep -Eq -- "^ok [0-9]+ - ${case_id}\$" "$TAP_LOG"; then
        gate_failures+=("the TAP log holds no top-level 'ok <n> - $case_id' line")
    fi
done

log "TAP summary: tests ${TAP_TESTS:-?}, pass ${TAP_PASS:-?}, fail ${TAP_FAIL:-?}, cancelled ${TAP_CANCELLED:-?}, skipped ${TAP_SKIPPED:-?}, todo ${TAP_TODO:-?}"

if [ "${#gate_failures[@]}" -gt 0 ]; then
    for gate_failure in "${gate_failures[@]}"; do
        printf '%s: error: %s\n' "$PROGRAM" "$gate_failure" >&2
    done

    exit 1
fi

log "all ${#EXPECTED_CASES[@]} acceptance cases passed"
exit 0
