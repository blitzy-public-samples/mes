#!/usr/bin/env bash
#
# ***************************************************************************
# Copyright (c) 2010 Qcadoo Limited
# Project: Qcadoo MES
# Version: 1.5-SNAPSHOT
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
# Worker: the script runs on a shared host as well as in a worker of its own. Processes outside the run can read the
# --password argument and reach the DevTools endpoint of Chrome; when a worker isolation check finds such processes,
# or cannot inspect some of the worker, the script prints one warning line to stderr and the run continues. In a
# worker of its own, a private process list and a private network namespace that hold only this run and its
# PostgreSQL server, such as a container of its own or unshare --pid --fork --mount-proc --net with the loopback
# interface up, the checks print no warning. A traced process of the run stops the run. The script turns command
# tracing off for itself; the worker isolation checks are described under "Worker isolation".
#
# Steps:
#   1. Checks the arguments, the repository layout, the work root and the tools on PATH, chooses the base day of
#      fixture.sql, creates the temporary work directory and reads the connection settings of
#      conf/tomcat/db.properties and of the distribution's db.properties. Requires the host of dbJdbcUrl in
#      conf/tomcat/db.properties to be localhost or a 127.0.0.0/8 address, unsets every exported variable whose name
#      starts with PG, and writes the libpq password file pgpass of that server into the work directory with mode 600.
#      Checks worker isolation, which prints a warning on co-tenancy and stops the run on an intrusion, and starts the
#      isolation watch, which checks for intrusions every 500 ms until the script exits and stops the run on one.
#      Then checks the PostgreSQL server answers, and guards the database with an advisory lock that a psql session on
#      the maintenance database holds until the script exits. psql gets connection URIs without a password, and every
#      psql session first raises an error unless its server address is a loopback address, its server port is the port
#      of dbJdbcUrl and its database is the one the session is for.
#   2. Checks the members of the distribution, unpacks it into the work directory, checks its db.properties, sets
#      hotDeploy=false in its app.properties, applies --db-name, --http-port and --shutdown-port to the unpacked copy,
#      and checks the HTTP and shutdown ports are free.
#   3. Recreates the database marked with the comment in MARKER on the server of conf/tomcat/db.properties,
#      then loads mes_db_en.sql and fixture.sql into it and checks the required plugins are enabled.
#   4. Starts Tomcat and, once the login page answers, checks that the started Tomcat runs and holds every LISTEN socket
#      on the HTTP port. Repeats the worker isolation check, runs acceptance.test.js with the TAP reporter, repeats the
#      listener check, and checks the TAP summary and every case line.
#   5. Stops the running step, the isolation watch and Tomcat on every exit. The steps are the unpacking, the psql
#      commands, the login page probes and the pauses between them, and acceptance.test.js; each runs as a background
#      job the script waits for, and SIGHUP, SIGINT or SIGTERM ends that wait at once, except for a psql command
#      inside a command substitution, which finishes first. SIGPIPE, which a write of the script to a closed standard
#      output or standard error raises, also stops the run. acceptance.test.js and the psql deadline wrapper get SIGINT
#      after a SIGINT and SIGTERM otherwise, any other step SIGTERM; SIGTERM and SIGKILL follow while it still runs.
#      While it stops the run, the script ignores SIGHUP, SIGINT, SIGTERM and SIGPIPE, and the commands it then starts
#      begin with them ignored; when a signal started the stop, it prints one line to stderr naming that signal.
#      Removes the work directory once Tomcat has stopped; keeps it and prints its path while Tomcat still runs. The
#      database is kept.
#
# Exit status: 0 for --help, and otherwise only when every acceptance case passed, Tomcat stopped and the work directory
# was removed; 2 on a usage error; the runner's status when the runner failed; 129 on SIGHUP, a hangup of the
# controlling terminal; 130 on SIGINT; 141 on SIGPIPE, a write to a closed standard output or standard error; 143 on
# SIGTERM, the signal the isolation watch sends on an intrusion, and in place of 0 when the isolation watch exits after
# an intrusion while the run stops; 1 on any other failure, including an intrusion found by a worker isolation check, a
# worker isolation check that cannot run, a failed or timed-out psql command, any other failed command, Tomcat still
# running after the stop, a work directory that cannot be removed, an HTTP port listener that is not the started Tomcat
# or a started Tomcat that is no longer running, and a tee failure. A worker isolation warning leaves the status
# unchanged. A signal received while the run stops leaves the status unchanged. A failed stop or removal keeps a nonzero
# status unchanged.

# Turns off command tracing (xtrace) for the whole run.
set +x
set -euo pipefail
unset CDPATH

readonly PROGRAM='run-acceptance.sh'
readonly MARKER='qcadoo-acceptance:productionMaintenanceGantt'
readonly REQUIRED_TOOLS=(psql setsid node unzip curl chrome-headless-shell java)
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
    'browser:ganttButtonUnsavedChangesGuard'
    'http:rejectMoveWithUnreadableHeader'
)
readonly MIN_NODE_MAJOR=22
readonly DEFAULT_DB_HOST='localhost'
readonly DEFAULT_DB_PORT='5432'
readonly JDBC_HOST_URL_RE='^jdbc:postgresql://([^/:]+)(:([0-9]+))?/([^/]*)$'
readonly JDBC_LOCAL_URL_RE='^jdbc:postgresql:([^/].*)$'
readonly DEFAULT_HTTP_PORT='8080'
readonly DEFAULT_SHUTDOWN_PORT='8005'
readonly STARTUP_TIMEOUT_SECONDS=600
readonly STARTUP_POLL_SECONDS=2
readonly STARTUP_REPORT_SECONDS=10
readonly STOP_WAIT_SECONDS=30
readonly KILL_WAIT_SECONDS=10
readonly STEP_STOP_WAIT_SECONDS=20
readonly STEP_TEE_WAIT_SECONDS=5
readonly TOMCAT_CMDLINE_READS=10
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
# Nonzero exit status the cleanup trap keeps: 2 after a usage error, the runner's status after a failed runner, else 0.
KEPT_STATUS=0
ISOLATION_WATCH_PID=''
# The step run_step or run_teed_step runs: how cleanup stops it (forward or terminate; empty while no step runs), the
# pid and name of its command, the pid of its tee and its FIFO, and the value of $! before its latest launch.
STEP_STOP=''
STEP_PID=''
STEP_NAME=''
STEP_TEE_PID=''
STEP_FIFO=''
STEP_PREVIOUS_JOB=''

# Reads the port attributes of conf/server.xml outside XML comments, or rewrites them.
#   ports <server.xml>                           prints "<http port> <shutdown port>"; an absent value prints "-"
#   rewrite <server.xml> <http> <shutdown>       sets the port of the first non-AJP Connector and of the Server element
#   A port value in single or double quotes is printed as written, sign and leading zeros included; a value that is
#   not an optionally signed decimal number makes it exit with status 1, its message quoting the value and the
#   element as JSON strings with DEL, the C1 controls, U+2028 and U+2029 also written as \uHHHH.
# shellcheck disable=SC2016
readonly SERVER_XML_JS='
"use strict";
const fs = require("fs");
const [mode, file, httpPort, shutdownPort] = process.argv.slice(1);
const parts = fs.readFileSync(file, "utf8").split(/(<!--[\s\S]*?-->)/);
const PORT = /(\bport\s*=\s*)(["\x27])([^"\x27]*)\2/;
const NUMBER = /^[+-]?[0-9]+$/;
const printable = (text) => JSON.stringify(text).replace(/[\u007f-\u009f\u2028\u2029]/g,
    (character) => "\\u" + character.charCodeAt(0).toString(16).toUpperCase().padStart(4, "0"));
const isAjp = (element) => /\bprotocol\s*=\s*["\x27][^"\x27]*ajp/i.test(element);
const portOf = (element) => {
    const port = PORT.exec(element);
    if (port && !NUMBER.test(port[3])) {
        process.stderr.write("port " + printable(port[3]) + " of " + printable(element) + " is not a decimal number\n");
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
#   is a file where another member needs a directory. Prints at most 20 problems, each member name as a JSON string
#   with DEL, the C1 controls, U+2028 and U+2029 also written as \uHHHH.
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
const printable = (text) => JSON.stringify(text).replace(/[\u007f-\u009f\u2028\u2029]/g,
    (character) => "\\u" + character.charCodeAt(0).toString(16).toUpperCase().padStart(4, "0"));
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
        const report = (problem) => problems.push("member " + printable(name) + " " + problem);
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
            problems.push("member " + printable(name) + " is a file and the directory of another member");
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

# Prints the last <lines> lines of a log file, each redacted, escaped and cut, one output line per log line.
#   <log file> <lines>    standard input holds the literal secrets, each ended by a NUL byte
#   Reads the last 1 MiB of the file plus as many bytes as the longest secret form, replaces with [redacted] in that
#   text every literal secret of standard input and its JSON-escaped, percent-encoded and form-encoded forms, longest
#   first, then drops the partial line at its start; prints a single notice line when that part holds no complete line.
#   In each of the last <lines> lines, its trailing carriage return removed, it replaces with [redacted], in this order:
#   the value of an Authorization, Proxy-Authorization, Cookie or Set-Cookie header up to the end of the line; the
#   userinfo of a URI; and, where the name holds password, passwd, pwd, secret, token, csrf, session id, api key,
#   authorization or cookie, the content of an XML element and the value of a pair in the forms k=v, k: v, "k":"v" and
#   "k": "v" whose name does not end in .java. It then writes C0 controls and DEL as \xHH, C1 controls, U+2028 and
#   U+2029 as \uHHHH, and cuts the line after 2000 characters with the number of characters cut. Exits with status 2 on
#   bad arguments and 1 when the log file is not a readable regular file or standard input cannot be read.
# shellcheck disable=SC2016
readonly LOG_TAIL_JS='
"use strict";
const fs = require("fs");
const [file, linesText] = process.argv.slice(1);
const lineLimit = Number(linesText);
const MAX_READ_BYTES = 1048576;
const MAX_LINE_LENGTH = 2000;
const REDACTED = "[redacted]";
const HEADER = /\b((?:proxy-)?authorization|(?:set-)?cookie2?)(\s*[:=]\s*)[^\r\n]*/gi;
const URI_USERINFO = /\b([A-Za-z][A-Za-z0-9+.\-]{0,31}:\/\/)[^\s\/?#]*@/g;
const XML_ELEMENT = /(<[\w.:\-]{0,64}?(?:passw(?:or)?d|pwd|secret|token|csrf|session[_-]?id|api[ _-]?key|authorization|cookie)[\w.\-]{0,64}>)[^<]*/gi;
const KEY_VALUE = /((?:passw(?:or)?d|pwd|secret|token|csrf|session[_-]?id|api[ _-]?key|authorization|cookie)[\w.\-]{0,64}(?<!\.java)["\x27]?)(\s*[=:]\s*)(?!\[redacted\])(?:"(?:[^"\\]|\\.)*"?|\x27(?:[^\x27\\]|\\.)*\x27?|[^\s,;&"\x27<>{}]+)/gi;
if (!file || !Number.isInteger(lineLimit) || lineLimit < 1) {
    process.stderr.write("expected the arguments <log file> <lines> with a positive integer <lines>\n");
    process.exit(2);
}
const secretForms = (secret) => [
    secret,
    JSON.stringify(secret).slice(1, -1),
    encodeURIComponent(secret),
    new URLSearchParams({ secret }).toString().slice("secret=".length)
];
const readTail = (extra) => {
    const descriptor = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NONBLOCK);
    try {
        const stats = fs.fstatSync(descriptor);
        if (!stats.isFile()) {
            throw new Error("not a regular file");
        }
        const start = stats.size > MAX_READ_BYTES ? Math.max(0, stats.size - MAX_READ_BYTES - 1 - extra) : 0;
        const buffer = Buffer.alloc(stats.size - start);
        let done = 0;
        while (done < buffer.length) {
            const count = fs.readSync(descriptor, buffer, done, buffer.length - done, start + done);
            if (count === 0) {
                break;
            }
            done += count;
        }
        return { text: buffer.subarray(0, done).toString("utf8"), partial: start > 0 };
    } finally {
        fs.closeSync(descriptor);
    }
};
const literalPattern = (secrets) => secrets.length === 0 ? null
    : new RegExp(secrets.map((secret) => secret.replace(/[\\^$.*+?()[\]{}|\/-]/g, "\\$&")).join("|"), "g");
const redact = (line) => {
    return line
        .replace(HEADER, (match, name, separator) => name + separator + REDACTED)
        .replace(URI_USERINFO, (match, scheme) => scheme + REDACTED + "@")
        .replace(XML_ELEMENT, (match, element) => element + REDACTED)
        .replace(KEY_VALUE, (match, name, separator) => name + separator + REDACTED);
};
const escapeCharacter = (character) => {
    const code = character.codePointAt(0);
    if (code < 0x20 || code === 0x7f) {
        return "\\x" + code.toString(16).toUpperCase().padStart(2, "0");
    }
    if ((code >= 0x80 && code <= 0x9f) || code === 0x2028 || code === 0x2029) {
        return "\\u" + code.toString(16).toUpperCase().padStart(4, "0");
    }
    return character;
};
const escapeAndCut = (text) => {
    let result = "";
    let consumed = 0;
    for (const character of text) {
        const piece = escapeCharacter(character);
        if (result.length + piece.length > MAX_LINE_LENGTH) {
            return result + " [" + (text.length - consumed) + " more characters cut]";
        }
        result += piece;
        consumed += character.length;
    }
    return result;
};
try {
    const secrets = [...new Set(fs.readFileSync(0, "utf8").split("\0").filter((secret) => secret !== "")
        .flatMap(secretForms))].sort((first, second) => second.length - first.length);
    const literals = literalPattern(secrets);
    const tail = readTail(secrets.reduce((longest, secret) => Math.max(longest, Buffer.byteLength(secret)), 0));
    const redacted = literals === null ? tail.text : tail.text.replace(literals, REDACTED);
    const newline = tail.partial ? redacted.indexOf("\n") : -1;
    if (tail.partial && newline < 0) {
        process.stdout.write("[no complete line in the last " + MAX_READ_BYTES + " bytes of the log]\n");
    } else {
        const lines = (tail.partial ? redacted.slice(newline + 1) : redacted).split("\n");
        if (lines[lines.length - 1] === "") {
            lines.pop();
        }
        process.stdout.write(lines.slice(-lineLimit)
            .map((line) => escapeAndCut(redact(line.replace(/\r$/, ""))) + "\n").join(""));
    }
} catch (error) {
    process.stderr.write("cannot print the tail of " + file + ": " + error.message + "\n");
    process.exit(1);
}
'

# Prints the argument with the bytes of every C0 control (0x01-0x1F), DEL (0x7F), UTF-8-encoded C1 control
# (0xC2 0x80-0x9F), U+2028 and U+2029 (0xE2 0x80 0xA8 and 0xA9) written as \xHH escapes; prints every other byte as is.
escape_controls() {
    local LC_ALL=C
    local text="$1" escaped='' code next width index=0 length

    length="${#text}"

    while [ "$index" -lt "$length" ]; do
        printf -v code '%d' "'${text:index:1}"
        width=0

        if [ "$code" -lt 32 ] || [ "$code" -eq 127 ]; then
            width=1
        elif [ "$code" -eq 194 ] && [ $((index + 1)) -lt "$length" ]; then
            printf -v next '%d' "'${text:index+1:1}"

            if [ "$next" -ge 128 ] && [ "$next" -le 159 ]; then
                width=2
            fi
        elif [ "$code" -eq 226 ]; then
            case "${text:index+1:2}" in
                $'\x80\xa8' | $'\x80\xa9') width=3 ;;
            esac
        fi

        if [ "$width" -eq 0 ]; then
            escaped+="${text:index:1}"
            index=$((index + 1))
        fi

        while [ "$width" -gt 0 ]; do
            printf -v code '%d' "'${text:index:1}"
            printf -v next '\\x%02X' "$code"
            escaped+="$next"
            index=$((index + 1))
            width=$((width - 1))
        done
    done

    printf '%s' "$escaped"
}

# Succeeds when the argument holds a character that escape_controls escapes.
holds_controls() {
    [ "$(escape_controls "$1")" != "$1" ]
}

# Prints a progress line, its message passed through escape_controls, and returns the status of printf. A write to a
# closed standard output prints no write-error diagnostic.
log() {
    printf '%s: %s\n' "$PROGRAM" "$(escape_controls "$1")" 2> /dev/null
}

# Prints a warning line to stderr, its message passed through escape_controls, and returns the status of printf. A
# write to a closed standard error prints no write-error diagnostic.
warn() {
    printf '%s: warning: %s\n' "$PROGRAM" "$(escape_controls "$1")" >&2 2> /dev/null
}

# Prints an error line to stderr, its message passed through escape_controls, and exits with status 1. A write to a
# closed standard error prints no write-error diagnostic.
die() {
    printf '%s: error: %s\n' "$PROGRAM" "$(escape_controls "$1")" >&2 2> /dev/null
    exit 1
}

# Prints the usage line to the given stream (default stderr).
usage() {
    printf 'usage: %s --dist <mes-application.zip> --user <login> --password <password> [--db-name <database>] [--http-port <port>] [--shutdown-port <port>] [--work-root <directory>]\n' \
        "$PROGRAM" >&"${1:-2}"
}

# Prints a usage error, its message passed through escape_controls, with the usage line and a pointer to --help, and
# exits with status 2.
usage_error() {
    printf '%s: %s\n' "$PROGRAM" "$(escape_controls "$1")" >&2
    usage 2
    printf 'Run %s --help for the options, their defaults, the requirements and the exit statuses.\n' "$PROGRAM" >&2
    KEPT_STATUS=2
    exit 2
}

# Prints the database of dbJdbcUrl in mes/mes-application/conf/tomcat/db.properties of the checkout that holds this
# script; fails when the file or the key is missing, or the value is not jdbc:postgresql:DB,
# jdbc:postgresql://HOST/DB or jdbc:postgresql://HOST:PORT/DB with a non-empty DB.
help_default_db_name() {
    local script_dir url

    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2> /dev/null && pwd)" || return 1
    url="$(property_value "$script_dir/../../../../conf/tomcat/db.properties" dbJdbcUrl 2> /dev/null)" || return 1

    if [[ "$url" =~ $JDBC_HOST_URL_RE ]] && [ -n "${BASH_REMATCH[4]}" ]; then
        printf '%s' "${BASH_REMATCH[4]}"
    elif [[ "$url" =~ $JDBC_LOCAL_URL_RE ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
    else
        return 1
    fi
}

# Prints the help to standard output: the usage lines, what the script does, every option with its default, the
# tools, environment, database and plugins it needs, the exit statuses and an example. The tool and plugin lists come
# from REQUIRED_TOOLS and REQUIRED_PLUGINS, and the default database from help_default_db_name when it resolves.
print_help() {
    local db_default tools plugins

    if db_default="$(help_default_db_name)" && [ -n "$db_default" ]; then
        db_default="$(escape_controls "$db_default") in this checkout"
    else
        db_default='mes as shipped'
    fi

    tools="$(printf '%s, ' "${REQUIRED_TOOLS[@]}")"
    plugins="$(printf '%s, ' "${REQUIRED_PLUGINS[@]}")"

    usage 1
    cat << EOF
       $PROGRAM --help | -h

Runs the acceptance suite of the production and maintenance Gantt board. Drops and recreates the acceptance
database only when its comment is $MARKER, creates it when it is absent
and refuses it with any other comment; loads mes_db_en.sql and fixture.sql into it; runs acceptance.test.js against
a Tomcat unpacked from --dist; stops that Tomcat and keeps the database.

Options, each also accepted as --name=value:
  --dist <mes-application.zip>  Required. The Tomcat distribution that (cd mes/mes-application && mvn -B -Ptomcat
                                clean install) builds as mes/mes-application/target/mes-application.zip. A relative
                                path is resolved against the current directory.
  --user <login>                Required. The application login acceptance.test.js signs in with.
  --password <password>         Required. The password of that login. On a shared host, processes outside this run
                                can read it.
  --db-name <database>          The acceptance database, also set in the unpacked distribution: a letter or _
                                followed by letters, digits and _, at most 63 characters.
                                Default: the database of dbJdbcUrl in mes/mes-application/conf/tomcat/db.properties
                                (${db_default}).
  --http-port <port>            HTTP connector port of the unpacked distribution, 1 to 65535.
                                Default: the port in the distribution's conf/server.xml, $DEFAULT_HTTP_PORT in the
                                distribution the tomcat profile builds.
  --shutdown-port <port>        Shutdown port of the unpacked distribution, 1 to 65535, other than the HTTP port.
                                Default: the port in the distribution's conf/server.xml, $DEFAULT_SHUTDOWN_PORT in the
                                distribution the tomcat profile builds.
  --work-root <directory>       Existing directory outside the repository that holds the temporary work directory;
                                its physical path may hold only $SAFE_PATH_CHARACTERS.
                                Default: the directory mktemp -t uses (TMPDIR, else /tmp), which has to meet the
                                same conditions.
  -h, --help                    Prints this help and exits with status 0.

Requirements:
  Tools on PATH  ${tools%, }; node $MIN_NODE_MAJOR or later.
  Environment    The script reads no environment variable besides PATH, the source of its tools; mktemp -t reads
                 TMPDIR for the default work root, and every exported variable whose name starts with PG is unset
                 before psql runs.
  PostgreSQL     Version 14 or later, at the host and port of dbJdbcUrl in
                 mes/mes-application/conf/tomcat/db.properties; that host has to be localhost or a 127.0.0.0/8
                 address. psql connects as its dbUsername with its dbPassword, an account that may create and drop
                 databases, and the db.properties of the distribution has to hold the same dbJdbcUrl, dbUsername
                 and dbPassword.
  Plugins        mes_db_en.sql has to enable ${plugins%, }.

Exit status:
  0         --help, or every acceptance case passed, Tomcat stopped and the work directory was removed
  2         a usage error
  <status>  the status of acceptance.test.js when it failed
  129       SIGHUP, a hangup of the controlling terminal
  130       SIGINT
  141       SIGPIPE, a write to a closed standard output or standard error
  143       SIGTERM, also the signal the worker isolation watch sends on an intrusion
  1         any other failure

Example, run from the repository root:
  mes/mes-application/src/test/acceptance/productionMaintenanceGantt/$PROGRAM \\
      --dist mes/mes-application/target/mes-application.zip --user admin --password admin \\
      --db-name mes_accept_c4 --http-port 20042 --shutdown-port 20043
EOF
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
        *) printf '%s/%s' "$PWD" "$1" ;;
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

# Succeeds when the first path equals the second, or starts with the second, less one trailing '/', followed by '/'.
# Compares the strings only and expects both to be normalized physical paths as physical_dir prints them; a path
# with a . or .. component or a symbolic link can pass without lying below the second.
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

# Succeeds when the argument is a 127.0.0.0/8 dotted quad whose last three octets are decimal numbers from 0 to 255.
is_loopback_ipv4() {
    [[ "$1" =~ ^127\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] \
        && [ "$((10#${BASH_REMATCH[1]}))" -le 255 ] \
        && [ "$((10#${BASH_REMATCH[2]}))" -le 255 ] \
        && [ "$((10#${BASH_REMATCH[3]}))" -le 255 ]
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

# Succeeds when the argument starts with a letter or underscore, continues with letters, digits and underscores only,
# and is at most 63 characters long.
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

# Percent-encodes the argument as one component of a URI with encodeURIComponent.
url_encode() {
    node -e 'process.stdout.write(encodeURIComponent(process.argv[1]))' -- "$1"
}

# Prints the argument as a field of a libpq password file: every \ written as \\ and every : as \:. Runs no external
# command.
pgpass_field() {
    local value="${1//\\/\\\\}"

    printf '%s' "${value//:/\\:}"
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

# Exits unless the argument is forward or terminate, the ways cleanup can stop a step.
require_step_stop() {
    case "$1" in
        forward | terminate) ;;
        *) die "internal error: a step is stopped with forward or terminate, not '$1'" ;;
    esac
}

# Runs an external command as a step: a background job with standard input from /dev/null whose pid STEP_PID holds
# while the script waits for it. Returns the command's exit status. <stop> is what cleanup sends the command when the
# script ends while it runs: forward sends the signal the script received (SIGINT for exit status 130, else SIGTERM),
# terminate sends SIGTERM. --stdout and --stderr write the command's standard output or standard error to a file. A
# redirection of the run_step call itself also applies to cleanup when cleanup runs during the step. Inside a command
# substitution the step runs in the subshell, which records it for itself.
#   run_step forward|terminate [--stdout <file>] [--stderr <file>] <command> [<argument>...]
#   Example: run_step terminate --stderr /dev/null sleep 2
run_step() {
    local stop="$1" stdout='' stderr='' status=0

    require_step_stop "$stop"
    shift

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --stdout | --stderr)
                [ "$#" -ge 3 ] || die "internal error: run_step $1 needs a file and a command"

                if [ "$1" = '--stdout' ]; then
                    stdout="$2"
                else
                    stderr="$2"
                fi

                shift 2
                ;;
            *) break ;;
        esac
    done

    [ "$#" -gt 0 ] || die 'internal error: run_step needs a command'
    STEP_PREVIOUS_JOB="${!:-}"
    STEP_NAME="${1##*/}"
    STEP_STOP="$stop"

    if [ -n "$stdout" ] && [ -n "$stderr" ]; then
        "$@" < /dev/null > "$stdout" 2> "$stderr" &
    elif [ -n "$stdout" ]; then
        "$@" < /dev/null > "$stdout" &
    elif [ -n "$stderr" ]; then
        "$@" < /dev/null 2> "$stderr" &
    else
        "$@" < /dev/null &
    fi

    STEP_PID=$!
    wait "$STEP_PID" || status=$?

    STEP_STOP=''
    STEP_PID=''
    STEP_NAME=''
    STEP_PREVIOUS_JOB=''
    return "$status"
}

# Runs an external command as a step like run_step, with tee copying its standard output (<streams> stdout), or its
# standard output and standard error (<streams> both), to a log file and to the script's standard output through the
# FIFO step.fifo of WORK_DIR; STEP_TEE_PID and STEP_FIFO hold tee and the FIFO while the step runs. Sets
# STEP_COMMAND_STATUS and STEP_TEE_STATUS to the exit statuses of the command and of tee, removes the FIFO, and returns
# the status of tee when it is not 0, else the status of the command.
#   run_teed_step forward|terminate stdout|both <log file> <command> [<argument>...]
#   Example: run_teed_step forward stdout "$WORK_DIR/out.log" node script.js
run_teed_step() {
    local stop="$1" streams="$2" log_file="$3" fifo="$WORK_DIR/step.fifo"

    require_step_stop "$stop"

    case "$streams" in
        stdout | both) ;;
        *) die "internal error: a teed step copies stdout or both, not '$streams'" ;;
    esac

    shift 3
    STEP_COMMAND_STATUS=0
    STEP_TEE_STATUS=0

    mkfifo -m 600 -- "$fifo" || die "cannot create the FIFO $fifo"
    STEP_FIFO="$fifo"
    STEP_PREVIOUS_JOB="${!:-}"
    STEP_NAME="${1##*/}"
    STEP_STOP="$stop"

    tee -- "$log_file" < "$fifo" &
    STEP_TEE_PID=$!
    STEP_PREVIOUS_JOB="$STEP_TEE_PID"

    if [ "$streams" = 'both' ]; then
        "$@" < /dev/null > "$fifo" 2>&1 &
    else
        "$@" < /dev/null > "$fifo" &
    fi

    STEP_PID=$!
    wait "$STEP_PID" || STEP_COMMAND_STATUS=$?
    STEP_PREVIOUS_JOB="$STEP_PID"
    STEP_PID=''

    wait "$STEP_TEE_PID" || STEP_TEE_STATUS=$?
    STEP_TEE_PID=''

    STEP_STOP=''
    STEP_NAME=''
    STEP_PREVIOUS_JOB=''
    rm -f -- "$fifo"
    STEP_FIFO=''

    if [ "$STEP_TEE_STATUS" -ne 0 ]; then
        return "$STEP_TEE_STATUS"
    fi

    return "$STEP_COMMAND_STATUS"
}

# Sets the array named by the first argument to the command that runs psql -X -w -v ON_ERROR_STOP=1 with the remaining
# arguments under PSQL_DEADLINE_JS, which exits with status 124 when psql runs longer than <seconds>.
#   psql_deadline_command <array name> <seconds> <psql argument>...
psql_deadline_command() {
    local -n psql_deadline_command_result="$1"
    local seconds="$2"

    shift 2
    psql_deadline_command_result=(node -e "$PSQL_DEADLINE_JS" -- "$PROGRAM" "$seconds" "$PSQL_KILL_GRACE_SECONDS"
        psql -X -w -v ON_ERROR_STOP=1 "$@")
}

# Runs psql -X -w -v ON_ERROR_STOP=1 with the given arguments as a forward step of run_step and fails with status 124
# when it runs longer than the given number of seconds. --stdout writes the standard output of psql to a file.
#   psql_with_deadline [--stdout <file>] <seconds> <psql argument>...
psql_with_deadline() {
    local psql_command=() output=()

    if [ "${1:-}" = '--stdout' ]; then
        [ "$#" -ge 3 ] || die 'internal error: psql_with_deadline --stdout needs a file and a deadline'
        output=(--stdout "$2")
        shift 2
    fi

    psql_deadline_command psql_command "$@"
    run_step forward "${output[@]}" "${psql_command[@]}"
}

# Prints a PL/pgSQL DO block that raises an exception naming the database, server address and server port the session
# reached, unless its server address is a loopback address (127.0.0.0/8, ::1 or ::ffff:127.0.0.0/104; a Unix-socket
# session has none), its server port is DB_PORT and its database is the given database.
#   server_guard_sql <database>    <database> matches is_db_name
server_guard_sql() {
    local database="$1"

    printf '%s' "DO \$guard\$
BEGIN
    IF inet_server_addr() IS NULL
            OR NOT (inet_server_addr() <<= inet '127.0.0.0/8' OR inet_server_addr() = inet '::1'
                OR inet_server_addr() <<= inet '::ffff:127.0.0.0/104')
            OR inet_server_port() IS DISTINCT FROM $DB_PORT
            OR current_database() <> '$database' THEN
        RAISE EXCEPTION 'refusing to run: psql reached database % at % port %, not database $database at a loopback address on port $DB_PORT',
            current_database(), coalesce(host(inet_server_addr()), 'a Unix socket'), coalesce(inet_server_port()::text, '-');
    END IF;
END
\$guard\$"
}

# Runs psql quietly against the maintenance database `postgres`, unaligned and tuples only, within
# PSQL_COMMAND_TIMEOUT_SECONDS: first the server guard ADMIN_GUARD_SQL, then the given arguments.
psql_admin() {
    psql_with_deadline "$PSQL_COMMAND_TIMEOUT_SECONDS" -At -q -d "$ADMIN_URI" -c "$ADMIN_GUARD_SQL" "$@"
}

# Runs psql quietly against the acceptance database, unaligned and tuples only, within PSQL_COMMAND_TIMEOUT_SECONDS:
# first the server guard DB_GUARD_SQL, then the given arguments.
psql_db() {
    psql_with_deadline "$PSQL_COMMAND_TIMEOUT_SECONDS" -At -q -d "$DB_URI" -c "$DB_GUARD_SQL" "$@"
}

# Succeeds while the started Tomcat process runs under the unpacked CATALINA_BASE, or while TOMCAT_PID is still the
# copy of this runner that bash forked to run catalina.sh, whose non-empty command line equals the runner's own. Any
# other command line, such as the one of a process in the middle of an exec, is read again every 0.1 s, up to
# TOMCAT_CMDLINE_READS times in all.
tomcat_alive() {
    local cmdline own_cmdline reads=0

    [ -n "$TOMCAT_PID" ] || return 1
    own_cmdline="$(tr '\0' ' ' 2> /dev/null < "/proc/$$/cmdline")" || return 1

    while :; do
        kill -0 "$TOMCAT_PID" 2> /dev/null || return 1
        [ -r "/proc/$TOMCAT_PID/cmdline" ] || return 0
        cmdline="$(tr '\0' ' ' 2> /dev/null < "/proc/$TOMCAT_PID/cmdline")" || return 1
        reads=$((reads + 1))

        case "$cmdline" in
            *"$CATALINA_BASE"*) return 0 ;;
        esac

        if [ -n "$cmdline" ] && [ "$cmdline" = "$own_cmdline" ]; then
            return 0
        fi

        [ "$reads" -lt "$TOMCAT_CMDLINE_READS" ] || return 1
        sleep 0.1
    done
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

# Succeeds while the process of the given pid exists and is not a zombie.
# shellcheck disable=SC2317
process_running() {
    local stat=''

    kill -0 "$1" 2> /dev/null || return 1
    IFS= read -r stat 2> /dev/null < "/proc/$1/stat" || return 0
    stat="${stat##*) }"
    [ "${stat:0:1}" != 'Z' ]
}

# Stops a background job of the script in stages and reaps it. Each stage <signal>:<seconds> sends the signal while
# the job runs, or no signal for -, and then waits up to <seconds> for the job to exit, checking every 0.1 s. Logs each
# signal it sends. Returns 0 once the job has exited and been reaped, 1 while it still runs.
#   stop_job <name> <pid> <signal>:<seconds>...
#   Example: stop_job tee 1234 -:5 TERM:10 KILL:10
# shellcheck disable=SC2317
stop_job() {
    local name="$1" pid="$2" stage signal seconds polls previous=''

    shift 2

    for stage in "$@"; do
        process_running "$pid" || break
        signal="${stage%%:*}"
        seconds="${stage#*:}"

        if [ "$signal" != '-' ]; then
            if [ -n "$previous" ]; then
                log "$name (pid $pid) still runs $previous; sending SIG$signal"
            else
                log "stopping $name (pid $pid) with SIG$signal"
            fi

            kill -s "$signal" "$pid" 2> /dev/null
            previous="$seconds s after SIG$signal"
        else
            previous="after $seconds s"
        fi

        polls=$((seconds * 10))

        while [ "$polls" -gt 0 ] && process_running "$pid"; do
            sleep 0.1
            polls=$((polls - 1))
        done
    done

    if process_running "$pid"; then
        return 1
    fi

    wait "$pid" 2> /dev/null
    return 0
}

# Stops the step that run_step or run_teed_step runs and reaps it, for the exit status the script ends with. A command
# or tee the step launched whose pid is not yet recorded is taken from $!. The command gets SIGINT when it is a forward
# step and the status is 130, else SIGTERM; then SIGTERM, unless that was the first signal, and SIGKILL. It has
# STEP_STOP_WAIT_SECONDS to exit after the first signal and KILL_WAIT_SECONDS after each other. Then tee has
# STEP_TEE_WAIT_SECONDS to exit before SIGTERM and SIGKILL, KILL_WAIT_SECONDS apart. Removes the FIFO. Returns 1 when
# the command or tee still runs, else 0.
#   stop_step <exit status>
# shellcheck disable=SC2317
stop_step() {
    local status="$1" last_job="${!:-}" signal='TERM' signals stages=() failed=0

    if [ -n "$STEP_STOP" ] && [ -n "$last_job" ] && [ "$last_job" != "$STEP_PREVIOUS_JOB" ] \
            && [ "$last_job" != "$STEP_PID" ] && [ "$last_job" != "$STEP_TEE_PID" ]; then
        if [ -n "$STEP_FIFO" ] && [ -z "$STEP_TEE_PID" ]; then
            STEP_TEE_PID="$last_job"
        elif [ -z "$STEP_PID" ]; then
            STEP_PID="$last_job"
        fi
    fi

    if [ -n "$STEP_PID" ]; then
        if [ "$STEP_STOP" = 'forward' ] && [ "$status" -eq 130 ]; then
            signal='INT'
        fi

        stages=("$signal:$STEP_STOP_WAIT_SECONDS")
        signals="SIG$signal"

        if [ "$signal" != 'TERM' ]; then
            stages+=("TERM:$KILL_WAIT_SECONDS")
            signals+=', SIGTERM'
        fi

        stages+=("KILL:$KILL_WAIT_SECONDS")

        if stop_job "the running step ${STEP_NAME:-command}" "$STEP_PID" "${stages[@]}"; then
            STEP_PID=''
        else
            printf '%s: error: the running step %s (pid %s) still runs after %s and SIGKILL\n' "$PROGRAM" \
                "$(escape_controls "${STEP_NAME:-command}")" "$STEP_PID" "$signals" >&2
            failed=1
        fi
    fi

    if [ -n "$STEP_TEE_PID" ]; then
        if stop_job tee "$STEP_TEE_PID" "-:$STEP_TEE_WAIT_SECONDS" "TERM:$KILL_WAIT_SECONDS" "KILL:$KILL_WAIT_SECONDS"
        then
            STEP_TEE_PID=''
        else
            printf '%s: error: tee (pid %s) still runs after SIGTERM and SIGKILL\n' "$PROGRAM" "$STEP_TEE_PID" >&2
            failed=1
        fi
    fi

    if [ -n "$STEP_FIFO" ]; then
        rm -f -- "$STEP_FIFO"
        STEP_FIFO=''
    fi

    STEP_STOP=''
    return "$failed"
}

# Stops the running step through stop_step, then the isolation watch. On failure prints the last LOG_TAIL_LINES lines of
# the Tomcat log through LOG_TAIL_JS, which reads the secrets of the run from its standard input, each ended by a NUL
# byte, or a one-line notice without the tail when the filter fails. Then stops Tomcat, and removes the work directory
# once Tomcat has stopped. Keeps the work directory while Tomcat still runs. Exits with the status given as its argument
# (129, 130, 141 or 143); without an argument, with the exit status that ran it when that status is 0 or KEPT_STATUS,
# and with 1 for any other status. Exits with 143 in place of 0 when the isolation watch exited with status 1, and with
# 1 in place of 0 when the step, tee or Tomcat still runs or the work directory still exists. From its first command on
# it has no EXIT trap, ignores SIGHUP, SIGINT, SIGTERM and SIGPIPE, which the commands it starts inherit as ignored, and
# runs without errexit; a signal it receives leaves the exit status unchanged. Given an argument, it prints one line to
# stderr naming the signal of that status (SIGHUP for 129, SIGINT for 130, SIGPIPE for 141, SIGTERM for 143) before it
# stops anything. A write to a closed standard output or standard error fails without ending it.
#   cleanup [<exit status>]
#   Example: trap 'cleanup 143' TERM
# shellcheck disable=SC2317
cleanup() {
    local status=$? cleanup_failed=0 remove_status log_tail filter_status signal_name

    trap - EXIT
    trap '' HUP INT PIPE TERM
    set +e

    if [ "$#" -gt 0 ]; then
        status="$1"
    elif [ "$status" -ne 0 ] && [ "$status" -ne "$KEPT_STATUS" ]; then
        status=1
    fi

    if [ "$#" -gt 0 ]; then
        case "$status" in
            129) signal_name='SIGHUP' ;;
            130) signal_name='SIGINT' ;;
            141) signal_name='SIGPIPE (a write to a closed standard output or standard error)' ;;
            143) signal_name='SIGTERM' ;;
            *) signal_name="the signal of exit status $status" ;;
        esac

        printf '%s: received %s; stopping the run and ignoring %s until it has stopped\n' "$PROGRAM" "$signal_name" \
            'SIGHUP, SIGINT, SIGTERM and SIGPIPE' >&2 2> /dev/null
    fi

    stop_step "$status" || cleanup_failed=1

    if [ -n "$ISOLATION_WATCH_PID" ] && ! stop_isolation_watch && [ "$status" -eq 0 ]; then
        status=143
    fi

    if [ "$status" -ne 0 ] && [ -n "$TOMCAT_LOG" ] && [ -f "$TOMCAT_LOG" ]; then
        if log_tail="$(printf '%s\0' "${PASSWORD_ARG:-}" "${REPO_DB_PASSWORD:-}" "${PACKAGED_DB_PASSWORD:-}" \
                "${DIST_DB_PASSWORD:-}" 2> /dev/null \
                | node -e "$LOG_TAIL_JS" -- "$TOMCAT_LOG" "$LOG_TAIL_LINES" 2> /dev/null && printf '.')"; then
            printf '%s: last %s lines of %s, credentials redacted and control characters escaped:\n' \
                "$PROGRAM" "$LOG_TAIL_LINES" "$(escape_controls "$TOMCAT_LOG")" >&2
            printf '%s' "${log_tail%.}" >&2
        else
            filter_status=$?
            printf '%s: the Tomcat log tail is not printed: its redacting filter exited with status %s\n' \
                "$PROGRAM" "$filter_status" >&2
        fi
    fi

    if [ -n "$TOMCAT_PID" ] && ! stop_tomcat; then
        printf '%s: error: Tomcat (pid %s) is still running; kept work directory %s and Tomcat log %s\n' \
            "$PROGRAM" "$TOMCAT_PID" "$(escape_controls "$WORK_DIR")" "$(escape_controls "$TOMCAT_LOG")" >&2
        cleanup_failed=1
    elif [ -n "$WORK_DIR" ] && { [ -e "$WORK_DIR" ] || [ -L "$WORK_DIR" ]; }; then
        rm -rf -- "$WORK_DIR"
        remove_status=$?

        if [ "$remove_status" -ne 0 ]; then
            printf '%s: error: cannot remove work directory %s: rm -rf exited with status %s\n' \
                "$PROGRAM" "$(escape_controls "$WORK_DIR")" "$remove_status" >&2
            cleanup_failed=1
        elif [ -e "$WORK_DIR" ] || [ -L "$WORK_DIR" ]; then
            printf '%s: error: work directory %s still exists after rm -rf\n' "$PROGRAM" \
                "$(escape_controls "$WORK_DIR")" >&2
            cleanup_failed=1
        fi
    fi

    if [ "$cleanup_failed" -ne 0 ] && [ "$status" -eq 0 ]; then
        status=1
    fi

    exit "$status"
}

trap cleanup EXIT
trap 'cleanup 129' HUP
trap 'cleanup 130' INT
trap 'cleanup 141' PIPE
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
            print_help
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

# Rejects a --dist value that holds a character escape_controls escapes.
readonly DIST_CONTROLS_PROBLEM='holds a control character or a Unicode line or paragraph separator; pass a path without them'

if holds_controls "$DIST_ARG"; then
    usage_error "--dist $DIST_ARG $DIST_CONTROLS_PROBLEM"
fi


# Worker isolation
#   ISOLATION_JS checks the worker of the run root, the script's pid, for intrusions and for co-tenancy:
#     node -e "$ISOLATION_JS" -- check <root pid> <database port>   exits 0 without output when it finds neither;
#                                                                    prints the intrusions and exits 1; prints the
#                                                                    co-tenancy text and exits 3 when it finds
#                                                                    co-tenancy only; prints the error and exits 4 when
#                                                                    the check cannot run;
#     node -e "$ISOLATION_JS" -- watch <root pid> <database port>   checks for intrusions only, every 500 ms while its
#                                                                    parent is the root; on an intrusion it writes the
#                                                                    error text to stderr, sends SIGTERM to the root and
#                                                                    then to the root's other descendants, and exits 1.
#   A process is admitted when it is the checker, one of its ancestors or a process admitted by an earlier check of the
#   same checker that found nothing; the root or a member of the checker's process group when that id is not 0, or a
#   descendant of one of these; pid 1; a kernel thread; or a server: a holder, or the non-root account, of a TCP socket
#   listening on the database port, with its descendants. The run is the admitted processes but pid 1,
#   kernel threads and servers; the watch admits only the run. An intrusion is a process of the run whose TracerPid is
#   not 0. Co-tenancy is a process that is not admitted, a socket of /proc/net/tcp, tcp6, udp and udp6 whose inode is
#   not 0 and that no admitted process holds, other than a socket of a server's non-root account or a TCP socket on
#   the database port with the uid of its listener, a process that cannot be inspected, a /proc, /proc/self/mountinfo
#   or /proc/net table that cannot be read or parsed, and, unless the checker runs as root, a /proc mounted with
#   hidepid. The watch reads only /proc/<pid>/stat and status, never the descriptors of the processes or the /proc/net
#   tables. A finding counts once an immediate second collection finds it again. The texts name at most 10 findings by
#   pid, uid and port, never a command line, an environment or an argument value. The co-tenancy text also gives the
#   number of findings and states that the run continues and that processes outside this run can read the --password
#   argument and reach the DevTools endpoint of Chrome.
# shellcheck disable=SC2016
readonly ISOLATION_JS='
"use strict";
const fs = require("fs");
const [mode, root, databasePort] = process.argv.slice(1);
const WATCH_INTERVAL_MS = 500;
const OFFENDER_LIMIT = 10;
const PF_KTHREAD = 0x00200000;
const EXPOSURE = "The run continues; processes outside this run can read the --password argument and reach the "
    + "DevTools endpoint of Chrome";
const trustedPorts = new Set([Number(databasePort)]);
const remembered = new Set();

function readProcesses(violations) {
    const processes = new Map();
    let names;
    try {
        names = fs.readdirSync("/proc");
    } catch (error) {
        violations.push("/proc cannot be listed");
        return processes;
    }
    for (const pid of names.filter((name) => /^\d+$/.test(name))) {
        let stat;
        let status;
        try {
            stat = fs.readFileSync(`/proc/${pid}/stat`, "latin1");
            status = fs.readFileSync(`/proc/${pid}/status`, "latin1");
        } catch (error) {
            if (error.code !== "ENOENT" && error.code !== "ESRCH") {
                violations.push(`process ${pid} cannot be inspected`);
            }
            continue;
        }
        const nameEnd = stat.lastIndexOf(")");
        const fields = stat.slice(nameEnd + 2).split(" ");
        const lines = status.split("\n").map((line) => line.split(/[ \t]+/).filter((field) => field !== ""));
        const uids = lines.find((line) => line[0] === "Uid:");
        const tracer = lines.find((line) => line[0] === "TracerPid:");
        if (nameEnd < 0 || fields.length < 20 || ![1, 2, 6, 19].every((index) => /^\d+$/.test(fields[index]))
            || !uids || uids.length !== 5 || !uids.slice(1).every((uid) => /^\d+$/.test(uid))
            || !tracer || tracer.length !== 2 || !/^\d+$/.test(tracer[1])) {
            violations.push(`process ${pid} cannot be inspected`);
            continue;
        }
        processes.set(pid, { ppid: fields[1], pgid: fields[2], start: fields[19],
            kernel: (Number(fields[6]) & PF_KTHREAD) !== 0, euid: uids[2], tracer: tracer[1] });
    }
    return processes;
}

function descendantsOf(roots, processes) {
    const children = new Map();
    for (const [pid, info] of processes) {
        if (!children.has(info.ppid)) {
            children.set(info.ppid, []);
        }
        children.get(info.ppid).push(pid);
    }
    const members = new Set();
    const pending = roots.filter((pid) => processes.has(pid));
    while (pending.length > 0) {
        const pid = pending.pop();
        if (!members.has(pid)) {
            members.add(pid);
            pending.push(...(children.get(pid) || []));
        }
    }
    return members;
}

function socketInodes(pid) {
    const inodes = new Set();
    let entries;
    try {
        entries = fs.readdirSync(`/proc/${pid}/fd`);
    } catch (error) {
        return inodes;
    }
    for (const entry of entries) {
        let link;
        try {
            link = fs.readlinkSync(`/proc/${pid}/fd/${entry}`);
        } catch (error) {
            continue;
        }
        const match = /^socket:\[(\d+)\]$/.exec(link);
        if (match) {
            inodes.add(match[1]);
        }
    }
    return inodes;
}

function socketTable() {
    const entries = [];
    for (const protocol of ["tcp", "tcp6", "udp", "udp6"]) {
        const table = `/proc/net/${protocol}`;
        let text;
        try {
            text = fs.readFileSync(table, "latin1");
        } catch (error) {
            if (error.code === "ENOENT") {
                continue;
            }
            throw new Error(`${table} cannot be read (${error.code})`);
        }
        for (const line of text.split("\n").slice(1)) {
            const fields = line.split(/[ \t]+/).filter((field) => field !== "");
            if (fields.length === 0) {
                continue;
            }
            const local = /^[0-9A-F]+:([0-9A-F]{4})$/i.exec(fields[1] || "");
            if (!local || !/^[0-9A-F]+:[0-9A-F]{4}$/i.test(fields[2] || "") || !/^[0-9A-F]{2}$/i.test(fields[3] || "")
                || !/^\d+$/.test(fields[7] || "") || !/^\d+$/.test(fields[9] || "")) {
                throw new Error(`${table} holds a line that is not a socket entry`);
            }
            entries.push({ protocol, state: fields[3].toUpperCase(), localPort: parseInt(local[1], 16),
                uid: fields[7], inode: fields[9] });
        }
    }
    return entries;
}

function collect(full) {
    const effectiveUid = String(process.geteuid());
    const coTenancy = [];
    const intrusion = [];
    if (full) {
        let mountinfo = "";
        try {
            mountinfo = fs.readFileSync("/proc/self/mountinfo", "utf8");
        } catch (error) {
            mountinfo = "";
        }
        if (mountinfo === "") {
            coTenancy.push("/proc/self/mountinfo cannot be read, so the process list cannot be checked");
        } else if (effectiveUid !== "0") {
            for (const line of mountinfo.split("\n")) {
                const fields = line.split(" ");
                if (fields[4] !== "/proc") {
                    continue;
                }
                for (const option of `${fields[5]},${fields[fields.length - 1]}`.split(",")) {
                    const value = option.startsWith("hidepid=") ? option.slice("hidepid=".length) : null;
                    if (value !== null && value !== "0" && value !== "off") {
                        coTenancy.push(`/proc is mounted with hidepid=${value}, so other accounts processes cannot be listed`);
                    }
                }
            }
        }
    }
    const processes = readProcesses(coTenancy);
    const self = String(process.pid);
    const chain = [];
    for (let current = self; processes.has(current) && !chain.includes(current); current = processes.get(current).ppid) {
        chain.push(current);
    }
    const group = processes.has(self) ? processes.get(self).pgid : "0";
    const key = (pid) => `${pid}:${processes.get(pid).start}`;
    const roots = [root];
    for (const [pid, info] of processes) {
        if (group !== "0" && info.pgid === group) {
            roots.push(pid);
        }
    }
    const run = descendantsOf(roots, processes);
    for (const pid of processes.keys()) {
        if (chain.includes(pid) || remembered.has(key(pid))) {
            run.add(pid);
        }
    }
    for (const pid of run) {
        if (processes.get(pid).tracer !== "0") {
            intrusion.push(`process ${pid} is traced by process ${processes.get(pid).tracer}`);
        }
    }
    if (!full) {
        return { coTenancy, intrusion, admitted: new Set([...run].map(key)) };
    }
    let table = [];
    try {
        table = socketTable();
    } catch (error) {
        coTenancy.push(error.message);
    }
    const isTcp = (entry) => entry.protocol === "tcp" || entry.protocol === "tcp6";
    const listening = table.filter((entry) => isTcp(entry) && entry.state === "0A" && trustedPorts.has(entry.localPort));
    const listeningInodes = new Set(listening.map((entry) => entry.inode));
    const serverAccounts = new Set(listening.map((entry) => entry.uid).filter((uid) => uid !== "0" && uid !== effectiveUid));
    const holders = new Map();
    const serverRoots = [];
    for (const [pid, info] of processes) {
        for (const inode of socketInodes(pid)) {
            if (!holders.has(inode)) {
                holders.set(inode, []);
            }
            holders.get(inode).push(pid);
            if (listeningInodes.has(inode)) {
                serverRoots.push(pid);
            }
        }
        if (serverAccounts.has(info.euid)) {
            serverRoots.push(pid);
        }
    }
    const servers = descendantsOf(serverRoots, processes);
    const isAdmitted = (pid) => run.has(pid) || servers.has(pid) || pid === "1" || processes.get(pid).kernel;
    const admitted = new Set();
    for (const [pid, info] of processes) {
        if (isAdmitted(pid)) {
            admitted.add(key(pid));
        } else {
            coTenancy.push(`process ${pid} (uid ${info.euid}) is not part of this run`);
        }
    }
    for (const entry of table) {
        if (entry.inode === "0" || serverAccounts.has(entry.uid) || (holders.get(entry.inode) || []).some(isAdmitted)) {
            continue;
        }
        if (isTcp(entry) && trustedPorts.has(entry.localPort)
            && listening.some((server) => server.localPort === entry.localPort && server.uid === entry.uid)) {
            continue;
        }
        coTenancy.push(`a ${entry.protocol} socket on local port ${entry.localPort} (uid ${entry.uid}) belongs to no `
            + "process of this run");
    }
    return { coTenancy, intrusion, admitted };
}

function isolation(full) {
    const first = collect(full);
    let coTenancy = full ? [...new Set(first.coTenancy)] : [];
    let intrusion = [...new Set(first.intrusion)];
    if (coTenancy.length > 0 || intrusion.length > 0) {
        const second = collect(full);
        const coTenancyAgain = new Set(second.coTenancy);
        const intrusionAgain = new Set(second.intrusion);
        coTenancy = coTenancy.filter((finding) => coTenancyAgain.has(finding));
        intrusion = intrusion.filter((finding) => intrusionAgain.has(finding));
    }
    if (coTenancy.length === 0 && intrusion.length === 0) {
        first.admitted.forEach((admittedKey) => remembered.add(admittedKey));
    }
    return { coTenancy, intrusion };
}

function report(findings) {
    const more = findings.length > OFFENDER_LIMIT ? `; and ${findings.length - OFFENDER_LIMIT} more` : "";
    return `${findings.slice(0, OFFENDER_LIMIT).join("; ")}${more}`;
}

function coTenancyReport(findings) {
    const count = `${findings.length} finding${findings.length === 1 ? "" : "s"}`;
    return `the worker is not private (${count}): ${report(findings)}. ${EXPOSURE}`;
}

function stopRun() {
    const pids = [root];
    try {
        const processes = readProcesses([]);
        for (const pid of descendantsOf([root], processes)) {
            if (pid !== root && pid !== String(process.pid)) {
                pids.push(pid);
            }
        }
    } catch (error) {
        process.stderr.write(`run-acceptance.sh: error: cannot list the processes of the run: ${error.message}\n`);
    }
    for (const pid of pids) {
        try {
            process.kill(Number(pid), "SIGTERM");
        } catch (error) {
            if (error.code !== "ESRCH") {
                process.stderr.write(`run-acceptance.sh: error: cannot send SIGTERM to process ${pid}: ${error.code}\n`);
            }
        }
    }
}

if (!/^\d+$/.test(root || "") || !/^\d+$/.test(databasePort || "") || (mode !== "check" && mode !== "watch")) {
    process.stderr.write("usage: node -e \"$ISOLATION_JS\" -- check|watch <root pid> <database port>\n");
    process.exit(2);
}

if (mode === "check") {
    let findings;
    try {
        findings = isolation(true);
    } catch (error) {
        process.stdout.write(error.message);
        process.exit(4);
    }
    if (findings.intrusion.length > 0) {
        process.stdout.write(report(findings.intrusion));
        process.exit(1);
    }
    if (findings.coTenancy.length > 0) {
        process.stdout.write(coTenancyReport(findings.coTenancy));
        process.exit(3);
    }
} else {
    const timer = setInterval(() => {
        if (String(process.ppid) !== root) {
            clearInterval(timer);
            return;
        }
        let intrusion;
        try {
            intrusion = isolation(false).intrusion;
        } catch (error) {
            intrusion = [error.message];
        }
        if (intrusion.length > 0) {
            clearInterval(timer);
            process.stderr.write("run-acceptance.sh: error: stopping the run: worker isolation check failed while it "
                + `ran: ${report(intrusion)}\n`);
            process.exitCode = 1;
            stopRun();
        }
    }, WATCH_INTERVAL_MS);
}
'

# Runs node -e "$ISOLATION_JS" -- check for the script's pid and DB_PORT; the argument names the step the check
# precedes. Exits with status 1, naming the step and the intrusions, when the check finds an intrusion (status 1 with
# a text), and naming the status and any text when the check cannot run (any other status than 0 and 3, or 3 without
# a text). Prints the co-tenancy text through warn, as one line on stderr, when the check finds co-tenancy only
# (status 3), and logs the passed check when it finds neither (status 0).
check_worker_isolation() {
    local step="$1" isolation_report='' isolation_status=0

    isolation_report="$(node -e "$ISOLATION_JS" -- check "$$" "$DB_PORT")" || isolation_status=$?

    if [ "$isolation_status" -eq 1 ] && [ -n "$isolation_report" ]; then
        die "worker isolation check failed $step: $isolation_report"
    elif [ "$isolation_status" -eq 3 ] && [ -n "$isolation_report" ]; then
        warn "worker isolation $step: $isolation_report"
    elif [ "$isolation_status" -ne 0 ]; then
        die "the worker isolation check $step could not run: node exited with status $isolation_status${isolation_report:+: $isolation_report}"
    else
        log "worker isolation $step: every process and socket belongs to this run or to the database server on port $DB_PORT"
    fi
}

# Starts node -e "$ISOLATION_JS" -- watch for the script's pid and DB_PORT in the background, which checks for
# intrusions until the script exits, and records its pid in ISOLATION_WATCH_PID.
start_isolation_watch() {
    node -e "$ISOLATION_JS" -- watch "$$" "$DB_PORT" < /dev/null &
    ISOLATION_WATCH_PID=$!
    log "worker isolation watch started (pid $ISOLATION_WATCH_PID)"
}

# Sends SIGTERM to the isolation watch when it runs and reaps it; clears ISOLATION_WATCH_PID. Returns 1 when the watch
# exited with status 1, as it does by itself after it reports an intrusion, and 0 for any other status, such as 130 or
# 143 when SIGINT or SIGTERM ended it.
# shellcheck disable=SC2317
stop_isolation_watch() {
    local watch_status=0

    if [ -n "$ISOLATION_WATCH_PID" ]; then
        kill -TERM "$ISOLATION_WATCH_PID" 2> /dev/null
        wait "$ISOLATION_WATCH_PID" 2> /dev/null || watch_status=$?
        ISOLATION_WATCH_PID=''
    fi

    if [ "$watch_status" -eq 1 ]; then
        return 1
    fi

    return 0
}

# Resolves the distribution against the caller's working directory, rejects an absolute path that holds a character
# escape_controls escapes, and normalizes its directory.
DIST="$(absolute_path "$DIST_ARG")"

if holds_controls "$DIST"; then
    usage_error "--dist $DIST_ARG resolves to $DIST, which $DIST_CONTROLS_PROBLEM"
fi

if [ -d "$(dirname "$DIST")" ]; then
    DIST="$(cd "$(dirname "$DIST")" && pwd)/$(basename "$DIST")"
fi

case "${DIST##*/}" in
    *[*?[]*) usage_error "--dist file name ${DIST##*/} holds * ? or [, which unzip matches as a pattern; rename the file" ;;
esac

# Requires the distribution to be an existing regular file, directly or through symbolic links, and readable; names a
# missing path, a symbolic link that resolves to no existing file, and a path of another file type apart.
if [ ! -e "$DIST" ]; then
    if [ -L "$DIST" ]; then
        dist_link_target="$(readlink -- "$DIST")" || dist_link_target=''
        die "distribution $DIST is a symbolic link to ${dist_link_target:-a target that cannot be read}, which does not resolve to an existing file; point it at the mes-application.zip that (cd mes/mes-application && mvn -B -Ptomcat clean install) builds, or pass that file"
    fi

    die "distribution $DIST does not exist; build it with (cd mes/mes-application && mvn -B -Ptomcat clean install)"
fi

if [ ! -f "$DIST" ]; then
    if [ -d "$DIST" ]; then
        dist_file_type='a directory'
    elif [ -p "$DIST" ]; then
        dist_file_type='a named pipe'
    elif [ -S "$DIST" ]; then
        dist_file_type='a socket'
    elif [ -b "$DIST" ] || [ -c "$DIST" ]; then
        dist_file_type='a device'
    else
        dist_file_type='a file of another type'
    fi

    die "distribution $DIST is not a regular file but $dist_file_type; pass the mes-application.zip file that (cd mes/mes-application && mvn -B -Ptomcat clean install) writes, mes/mes-application/target/mes-application.zip"
fi

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

# Rewrites every relative PATH entry as the physical current working directory, '/' and the entry, and every empty
# entry as that directory; entries are not normalized, so one holding .. can name a directory outside that directory.
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

# Runs java -version and keeps the first line of its output that is not a "Picked up" notice of the JVM as the version
# line; fails when java -version exits with a nonzero status.
java_status=0
java_output="$(java -version 2>&1 < /dev/null)" || java_status=$?
JAVA_VERSION_LINE=''

while IFS= read -r java_line; do
    case "$java_line" in
        'Picked up '* | 'NOTE: Picked up '*) ;;
        *)
            JAVA_VERSION_LINE="$java_line"
            break
            ;;
    esac
done <<< "$java_output"

if [ "$java_status" -ne 0 ]; then
    die "java on PATH ($(type -P java)) cannot run: java -version exited with status $java_status${JAVA_VERSION_LINE:+: $JAVA_VERSION_LINE}"
fi

if [ -z "$JAVA_VERSION_LINE" ]; then
    JAVA_VERSION_LINE="java at $(type -P java)"
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

log "node $(node --version), $(psql --version), $JAVA_VERSION_LINE, chrome-headless-shell at $CHROME"

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
DB_PORT="$((10#$DB_PORT))"

# Requires the database host to be localhost or a 127.0.0.0/8 address.
if [ "$DB_HOST" != 'localhost' ] && ! is_loopback_ipv4 "$DB_HOST"; then
    die "dbJdbcUrl $REPO_DB_URL names host $DB_HOST; the acceptance database has to be on localhost or 127.0.0.0/8"
fi

DB_NAME="${DB_NAME_ARG:-$REPO_DB_NAME}"

is_db_name "$DB_NAME" || die "database name '$DB_NAME' does not match ^[A-Za-z_][A-Za-z0-9_]*\$ or is longer than 63 characters"

# Unsets every exported variable whose name starts with PG; psql then takes its connection parameters only from DB_URI
# and ADMIN_URI.
while IFS= read -r exported_name; do
    case "$exported_name" in
        PG*) unset "$exported_name" || die "cannot unset the exported variable $exported_name" ;;
    esac
done < <(compgen -e)

# Writes the libpq password file PASSFILE, mode 600, with the one line <host>:<port>:*:<user>:<password> of
# conf/tomcat/db.properties, using only shell builtins.
PASSFILE="$WORK_DIR/pgpass"

(
    umask 077
    set -C
    printf '%s:%s:*:%s:%s\n' "$(pgpass_field "$DB_HOST")" "$DB_PORT" "$(pgpass_field "$REPO_DB_USER")" \
        "$(pgpass_field "$REPO_DB_PASSWORD")" > "$PASSFILE"
) || die "cannot write the password file $PASSFILE"

if [ ! -f "$PASSFILE" ] || [ -L "$PASSFILE" ] || [ "$(stat -c %a -- "$PASSFILE")" != '600' ]; then
    die "the password file $PASSFILE is not a regular file with mode 600"
fi

# Builds the psql connection URIs, without a password and with the password file PASSFILE, and the server guards of
# their databases.
ENCODED_DB_USER="$(url_encode "$REPO_DB_USER")"
PSQL_URI_OPTIONS="connect_timeout=$PSQL_CONNECT_TIMEOUT_SECONDS&passfile=$(url_encode "$PASSFILE")"
DB_URI="postgresql://$ENCODED_DB_USER@$DB_HOST:$DB_PORT/$DB_NAME?$PSQL_URI_OPTIONS"
ADMIN_URI="postgresql://$ENCODED_DB_USER@$DB_HOST:$DB_PORT/postgres?$PSQL_URI_OPTIONS"
DB_GUARD_SQL="$(server_guard_sql "$DB_NAME")"
ADMIN_GUARD_SQL="$(server_guard_sql postgres)"

# Checks worker isolation, then watches for intrusions until the script exits.
check_worker_isolation 'before the database and Tomcat steps'
start_isolation_watch

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
        -c "$ADMIN_GUARD_SQL" \
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

# Unpacks the distribution without overwriting and without reading standard input, as a step of run_step.
DIST_DIR="$WORK_DIR/dist"

log "unpacking $DIST into $DIST_DIR"
mkdir -- "$DIST_DIR"
run_step terminate unzip -q -n "$DIST" -d "$DIST_DIR" || die "cannot unpack $DIST into $DIST_DIR"

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
    FROM pg_catalog.pg_database WHERE datname = '$DB_NAME'")" \
    || die "reading the comment of database $DB_NAME failed: psql exited with status $?"

if [ -n "$existing_database" ]; then
    existing_comment="${existing_database#present:}"

    if [ "$existing_comment" != "$MARKER" ]; then
        die "refusing to drop database $DB_NAME: its comment is '$existing_comment', not the acceptance marker '$MARKER'"
    fi

    log "dropping acceptance database $DB_NAME"
    psql_admin -q -c "DROP DATABASE \"$DB_NAME\" WITH (FORCE)" \
        || die "dropping database $DB_NAME failed: psql exited with status $?"
fi

log "creating acceptance database $DB_NAME"
psql_admin -q -c "CREATE DATABASE \"$DB_NAME\" ENCODING 'UTF8' TEMPLATE template0" \
    -c "COMMENT ON DATABASE \"$DB_NAME\" IS '$MARKER'" \
    || die "creating database $DB_NAME with the comment $MARKER failed: psql exited with status $?"

# Loads the seed.
log "loading $SEED_SQL into $DB_NAME"
psql_with_deadline --stdout "$WORK_DIR/seed.log" "$PSQL_SEED_TIMEOUT_SECONDS" -q -d "$DB_URI" -c "$DB_GUARD_SQL" \
    -f "$SEED_SQL" || die "loading $SEED_SQL into $DB_NAME failed: psql exited with status $?"
log 'seed loaded'

# Loads the fixture as a forward step of run_teed_step, tee copying the standard output and standard error of psql
# to FIXTURE_LOG and to standard output.
FIXTURE_LOG="$WORK_DIR/fixture.log"

log "loading $FIXTURE_SQL into $DB_NAME"
fixture_command=()
psql_deadline_command fixture_command "$PSQL_FIXTURE_TIMEOUT_SECONDS" -v base_day="$BASE_DAY" -d "$DB_URI" \
    -c "$DB_GUARD_SQL" -f "$FIXTURE_SQL"
run_teed_step forward both "$FIXTURE_LOG" "${fixture_command[@]}" \
    || die "loading $FIXTURE_SQL into $DB_NAME failed: psql exited with status $STEP_COMMAND_STATUS, tee with status $STEP_TEE_STATUS"
log 'fixture loaded'

# Checks the plugins the suite needs are enabled.
enabled_plugins="$(psql_db -c "SELECT count(*) FROM public.qcadooplugin_plugin WHERE state = 'ENABLED'
    AND identifier IN ('productionScheduling','lineChangeoverNormsForOrders','cmmsMachineParts')")" \
    || die "counting the enabled plugins of database $DB_NAME failed: psql exited with status $?"

if [ "$enabled_plugins" != "${#REQUIRED_PLUGINS[@]}" ]; then
    missing_plugins="$(psql_db -c "SELECT string_agg(required.identifier, ', ' ORDER BY required.identifier)
        FROM (VALUES ('productionScheduling'), ('lineChangeoverNormsForOrders'), ('cmmsMachineParts')) AS required (identifier)
        WHERE NOT EXISTS (SELECT 1 FROM public.qcadooplugin_plugin plugin
            WHERE plugin.identifier = required.identifier AND plugin.state = 'ENABLED')")" \
        || die "listing the plugins not enabled in database $DB_NAME failed: psql exited with status $?"
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

# Waits for the login page, running curl and sleep as steps of run_step.
startup_began=$SECONDS
next_report="$STARTUP_REPORT_SECONDS"

until run_step terminate --stderr /dev/null curl -q -fsS -o /dev/null --max-time 10 --noproxy '*' "$BASE_URL/login.html"
do
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

    run_step terminate sleep "$STARTUP_POLL_SECONDS"
done

log "Tomcat answered $BASE_URL/login.html after $((SECONDS - startup_began)) s"

# Checks the started Tomcat holds the HTTP listener that answered.
verify_tomcat_listener "after $BASE_URL/login.html answered"

# ---------------------------------------------------------------------------------------------------------------
# Runner and TAP gate
# ---------------------------------------------------------------------------------------------------------------

TAP_LOG="$WORK_DIR/acceptance.tap"

# Repeats the worker isolation check right before acceptance.test.js starts.
check_worker_isolation 'before acceptance.test.js starts'

log "running acceptance.test.js against $BASE_URL with base day $BASE_DAY"
cd "$SCRIPT_DIR"

# Runs acceptance.test.js as a forward step of run_teed_step, tee copying its standard output to TAP_LOG and to
# standard output.
run_teed_step forward stdout "$TAP_LOG" node --test-reporter=tap acceptance.test.js --base-url="$BASE_URL" \
    --user="$USER_ARG" --password="$PASSWORD_ARG" --db-uri="$DB_URI" --chrome="$CHROME" --base-day="$BASE_DAY" || true

RUNNER_STATUS="$STEP_COMMAND_STATUS"
TEE_STATUS="$STEP_TEE_STATUS"

if [ "$RUNNER_STATUS" -ne 0 ]; then
    printf '%s: error: acceptance.test.js exited with status %s\n' "$PROGRAM" "$RUNNER_STATUS" >&2
    KEPT_STATUS="$RUNNER_STATUS"
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
