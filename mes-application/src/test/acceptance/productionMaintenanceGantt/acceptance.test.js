/*
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.5-SNAPSHOT
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */

/*
 * Application-level acceptance suite of the production and maintenance Gantt board
 * (cmmsMachineParts/productionMaintenanceGantt).
 *
 * Runs against a started application whose database holds mes_db_en.sql and fixture.sql:
 *
 *   node --test-reporter=tap acceptance.test.js --base-url http://localhost:<port> --user <login> --password <password>
 *        --db-uri postgresql://<user>@localhost:<port>/<db>?passfile=<file> --chrome <path to chrome-headless-shell>
 *        --base-day YYYY-MM-DD
 *
 * Tested toolchain: Node.js v22.23.2, Chrome for Testing headless shell 154.0.8037.57, PostgreSQL 14 with its psql,
 * on Linux. Before it parses the arguments, the runner throws unless it runs on Linux with /proc/net/tcp and on
 * Node.js 22 or later with the global fetch, WebSocket and Headers.prototype.getSetCookie. Once the arguments are
 * parsed, it sets process.title to 'node acceptance.test.js' and throws unless /proc/self/cmdline then reads exactly
 * that title.
 *
 * Worker isolation: before it registers a case and again before it starts Chrome, the runner checks the worker. A
 * process is admitted when it is part of the run (this process, its ancestors and the processes admitted by an earlier
 * check that found nothing; the process that runs run-acceptance.sh next to this file, else this process, and the
 * members of this process's process group, each with its descendants), pid 1, a kernel thread or a server (a holder,
 * or the non-root account, of a TCP socket listening on the --base-url port or a --db-uri port, with its descendants).
 * A traced process of the run is an intrusion: the check throws. Co-tenancy is a process that is not admitted, a
 * socket of /proc/net/tcp, tcp6, udp and udp6 that belongs to no admitted process, a process that cannot be
 * inspected, a /proc, /proc/self/mountinfo or /proc/net table that cannot be read or parsed, and, unless the runner
 * runs as root, /proc mounted with hidepid: the check writes one warning line to stderr with the number of findings
 * and at most 10 of them, and the run continues. Processes outside the run can read the --password argument and
 * reach Chrome's DevTools endpoint. In a worker of its own, a private process list and a private network namespace
 * holding only this run, the application and its database server, the checks write no warning. A finding counts once
 * an immediate second check finds it again. From the first check until the after hook, the runner checks every 500 ms
 * for intrusions only: a traced process of the run, and, once Chrome runs, any connection to Chrome's DevTools port
 * other than its own. An intrusion found while the run runs stops Chrome, fails every later case and the after hook,
 * and sets the exit status to 1.
 *
 * Arguments, checked before any case is registered:
 *   --base-url  a loopback http or https origin (localhost, 127.0.0.0/8 or [::1]) without user name, password, path,
 *               query or fragment.
 *   --db-uri    a URI in libpq 14 syntax that starts with postgresql:// or postgres:// in lower case: well-formed
 *               percent escapes other than %00, at most one '@', only as the end of the user name and password before
 *               the first '/' and '?' (%40 elsewhere), key=value query parameters that libpq accepts other than
 *               service, hosts that are loopback hosts or absolute socket directories, loopback hostaddr values, and a
 *               database name. psql gets it with any password moved into a mode-600 password file of a private
 *               temporary directory and with application_name appended, and runs without the environment variables
 *               whose names start with PG other than PGCONNECT_TIMEOUT. A server that psql reaches over TCP on an
 *               address other than a loopback address is refused before any case.
 *   --chrome    an executable file, as a path or as a name found on PATH.
 *   --user      a non-blank login without control characters (U+0000-U+001F, U+007F-U+009F, U+2028 and U+2029).
 *
 * Before any case, the before hook first verifies the target with checks that only read the database and /proc: the
 * database comment must equal qcadoo-acceptance:productionMaintenanceGantt; exactly one process must listen on the
 * --base-url port; that process must hold at least one connection to the server of the database, every such
 * connection must be a loopback connection to that database, and it must hold no loopback connection to another
 * PostgreSQL server. When a check fails, the hook throws and every case fails without an HTTP request, a browser or a
 * database write. Only after every check passes does the hook read the expected English messages and the fixture ids,
 * launch Chrome, log in and open the board of the rowMapping schedule. flushApplicationBackendStats selects the client
 * backends of that database that are idle when observed and serve connections of that same process, then terminates
 * every selected backend, including one that has become active since it was selected.
 *
 * http: cases post view events with Node's fetch. browser: cases drive the board in headless Chrome through the
 * DevTools Protocol with real mouse input. Database state is read, and concurrent transactions are run, with psql.
 * Every case works on its own draft schedule PMG-<caseName> of fixture.sql.
 */

'use strict';

const { test, before, beforeEach, after } = require('node:test');
const assert = require('node:assert/strict');
const childProcess = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// ---------------------------------------------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------------------------------------------

const USAGE = 'usage: node acceptance.test.js --base-url <http://localhost:port> --user <login> --password <password> '
    + '--db-uri <postgresql://user@localhost:port/db?passfile=file> --chrome <path> --base-day <YYYY-MM-DD>';

const ARGUMENT_NAMES = ['base-url', 'user', 'password', 'db-uri', 'chrome', 'base-day'];

/**
 * Returns whether the host name is localhost, a 127.0.0.0/8 dotted quad or the IPv6 loopback address (::1 or [::1]).
 * The comparison ignores case.
 */
function isLoopbackHost(name) {
    const host = String(name).toLowerCase();

    return host === 'localhost' || host === '[::1]' || isLoopbackAddress(host);
}

/** Returns whether the text is a 127.0.0.0/8 dotted quad or ::1. The comparison ignores case. */
function isLoopbackAddress(text) {
    const address = String(text).toLowerCase();

    if (address === '::1') {
        return true;
    }

    const quad = /^127\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(address);

    return quad !== null && quad.slice(1).every((octet) => Number(octet) <= 255);
}

/** Returns whether the path names an existing regular file the process may execute. */
function isExecutableFile(candidate) {
    try {
        if (!fs.statSync(candidate).isFile()) {
            return false;
        }

        fs.accessSync(candidate, fs.constants.X_OK);

        return true;
    } catch (error) {
        return false;
    }
}

/**
 * Validates --base-url and returns { baseUrl, httpPort }: baseUrl is the URL's origin and httpPort its port, 80 or 443
 * when the URL names none. Throws with the usage line unless the value is an http or https URL on a loopback host
 * without user name, password, query or fragment and with the path '/' or none. The message never holds the value.
 */
function parseBaseUrl(value) {
    let url;

    try {
        url = new URL(value);
    } catch (error) {
        throw new Error(`--base-url must be an absolute http or https URL\n${USAGE}`);
    }

    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
        throw new Error(`--base-url must use the http or https scheme\n${USAGE}`);
    }
    if (url.username !== '' || url.password !== '') {
        throw new Error(`--base-url must not hold a user name or a password\n${USAGE}`);
    }
    if (url.pathname !== '/' || url.search !== '' || url.hash !== '') {
        throw new Error(`--base-url must be an origin without a path, a query or a fragment\n${USAGE}`);
    }
    if (!isLoopbackHost(url.hostname)) {
        throw new Error(`--base-url must name a loopback host: localhost, 127.0.0.0/8 or [::1]\n${USAGE}`);
    }

    return {
        baseUrl: url.origin,
        httpPort: url.port ? Number(url.port) : (url.protocol === 'https:' ? 443 : 80)
    };
}

// Query parameters of a libpq 14 connection URI accepted in --db-uri: the libpq connection keywords other than service.
// The URI-only parameter ssl is accepted with the value true.
const LIBPQ_URI_PARAMETERS = new Set(['user', 'password', 'passfile', 'channel_binding', 'connect_timeout', 'dbname',
    'host', 'hostaddr', 'port', 'client_encoding', 'options', 'application_name', 'fallback_application_name',
    'keepalives', 'keepalives_idle', 'keepalives_interval', 'keepalives_count', 'tcp_user_timeout', 'sslmode',
    'requiressl', 'sslcompression', 'sslcert', 'sslkey', 'sslpassword', 'sslrootcert', 'sslcrl', 'sslcrldir', 'sslsni',
    'requirepeer', 'ssl_min_protocol_version', 'ssl_max_protocol_version', 'gssencmode', 'krbsrvname', 'gsslib',
    'replication', 'target_session_attrs']);

/** Returns the percent-decoded text, or null when the decoded bytes are not UTF-8. */
function percentDecode(text) {
    try {
        return decodeURIComponent(text);
    } catch (error) {
        return null;
    }
}

// Beginnings of a libpq connection URI, in lower case.
const DATABASE_URI_PREFIXES = ['postgresql://', 'postgres://'];

/**
 * Splits a URI that starts with one of DATABASE_URI_PREFIXES into { prefix, userInfo, rest, query }:
 * - prefix: the DATABASE_URI_PREFIXES entry it starts with;
 * - userInfo: the text between the prefix and the first '@' when that '@' comes before the first '/' and the first
 *   '?' after the prefix, else null;
 * - rest: the text after that '@' (after the prefix when userInfo is null) up to the first '?';
 * - query: the text after that '?', or null when there is none.
 * Example: 'postgresql://u:p@localhost:5432/db?a=1' gives { prefix: 'postgresql://', userInfo: 'u:p',
 * rest: 'localhost:5432/db', query: 'a=1' }.
 */
function splitDatabaseUri(value) {
    const prefix = DATABASE_URI_PREFIXES.find((candidate) => value.startsWith(candidate));
    const body = value.slice(prefix.length);
    const at = body.indexOf('@');
    const authorityEnd = body.search(/[/?]/);
    const hasUserInfo = at >= 0 && (authorityEnd < 0 || at < authorityEnd);
    const afterUserInfo = hasUserInfo ? body.slice(at + 1) : body;
    const queryStart = afterUserInfo.indexOf('?');

    return {
        prefix,
        userInfo: hasUserInfo ? body.slice(0, at) : null,
        rest: queryStart < 0 ? afterUserInfo : afterUserInfo.slice(0, queryStart),
        query: queryStart < 0 ? null : afterUserInfo.slice(queryStart + 1)
    };
}

/**
 * Validates --db-uri. Throws with the usage line unless the value:
 * - has no leading or trailing white space and no '#';
 * - writes every '%' as a two-digit hex escape other than %00;
 * - starts with 'postgresql://' or 'postgres://' in lower case (DATABASE_URI_PREFIXES);
 * - holds at most one '@', and only as the end of the user information: before the first '/' and the first '?' after
 *   that beginning;
 * - is a postgresql:// or postgres:// URI;
 * - has a query, when it holds '?', of '&'-separated key=value parameters with no empty parameter and a single '=',
 *   each key in LIBPQ_URI_PARAMETERS, or ssl=true;
 * - names at least one host, and every host (of the authority and of the host query parameters) is a single loopback
 *   host or an absolute socket directory;
 * - has only single loopback addresses as hostaddr query parameters;
 * - names a database (the last dbname query parameter, else the path) that is non-blank and holds no '/'.
 * The message never holds the value.
 */
function validateDatabaseUri(value) {
    if (value !== value.trim()) {
        throw new Error(`--db-uri must not begin or end with white space\n${USAGE}`);
    }
    if (value.includes('#')) {
        throw new Error(`--db-uri must write '#' as %23\n${USAGE}`);
    }
    if (/%(?![0-9A-Fa-f]{2})/.test(value) || /%00/.test(value)) {
        throw new Error(`--db-uri must write every '%' as a two-digit hex escape other than %00\n${USAGE}`);
    }
    if (!DATABASE_URI_PREFIXES.some((prefix) => value.startsWith(prefix))) {
        throw new Error(`--db-uri must start with postgresql:// or postgres:// in lower case\n${USAGE}`);
    }

    const atSigns = value.split('@').length - 1;

    if (atSigns > 1 || (atSigns === 1 && splitDatabaseUri(value).userInfo === null)) {
        throw new Error(`--db-uri may hold '@' only once, at the end of the user name and password, before the first `
            + `'/' and '?'; write any other '@' as %40\n${USAGE}`);
    }

    let url;

    try {
        url = new URL(value);
    } catch (error) {
        throw new Error(`--db-uri must be a postgresql:// connection URI\n${USAGE}`);
    }

    if (url.protocol !== 'postgresql:' && url.protocol !== 'postgres:') {
        throw new Error(`--db-uri must use the postgresql or postgres scheme\n${USAGE}`);
    }

    const query = url.search.slice(1);
    const parameters = [];

    if (value.includes('?') && query === '') {
        throw new Error(`--db-uri query parameters must have the form key=value\n${USAGE}`);
    }

    for (const token of query === '' ? [] : query.split('&')) {
        const parts = token.split('=');
        const key = parts.length === 2 ? percentDecode(parts[0]) : null;
        const parameterValue = parts.length === 2 ? percentDecode(parts[1]) : null;

        if (key === null || parameterValue === null) {
            throw new Error(`--db-uri query parameters must have the form key=value\n${USAGE}`);
        }
        if (key === 'ssl' ? parameterValue !== 'true' : !LIBPQ_URI_PARAMETERS.has(key)) {
            throw new Error(`--db-uri may use only the libpq 14 connection parameters other than service, and ssl only `
                + `as ssl=true\n${USAGE}`);
        }

        parameters.push({ key, value: parameterValue });
    }

    const values = (key) => parameters.filter((parameter) => parameter.key === key).map((parameter) => parameter.value);
    const hostname = percentDecode(url.hostname);
    const hosts = (hostname === '' ? [] : [hostname]).concat(values('host'));
    const isLocalHost = (host) => host !== null && !host.includes(',') && (isLoopbackHost(host) || host.startsWith('/'));

    if (hosts.length === 0) {
        throw new Error(`--db-uri must name a host\n${USAGE}`);
    }
    if (!hosts.every(isLocalHost)) {
        throw new Error(`--db-uri must name only single loopback hosts (localhost, 127.0.0.0/8 or [::1]) or absolute `
            + `socket directories\n${USAGE}`);
    }
    if (!values('hostaddr').every((address) => !address.includes(',') && isLoopbackAddress(address))) {
        throw new Error(`--db-uri hostaddr must be a single loopback address: 127.0.0.0/8 or ::1\n${USAGE}`);
    }

    const databases = values('dbname');
    const database = databases.length > 0 ? databases[databases.length - 1] : percentDecode(url.pathname.slice(1));

    if (database === null || database.trim() === '' || database.includes('/')) {
        throw new Error(`--db-uri must name a database without '/'\n${USAGE}`);
    }
}

/**
 * Returns the absolute path of the executable file --chrome names: the path itself when the value holds '/', else the
 * first match on PATH. Throws with the usage line when the value is blank or names no executable file.
 */
function resolveChrome(value) {
    if (value.trim() === '') {
        throw new Error(`--chrome must not be blank\n${USAGE}`);
    }

    const candidates = value.includes('/')
        ? [path.resolve(value)]
        : (process.env.PATH || '').split(path.delimiter).map((directory) => path.resolve(directory, value));
    const executable = candidates.find(isExecutableFile);

    if (executable === undefined) {
        throw new Error(`--chrome must name an executable file\n${USAGE}`);
    }

    return executable;
}

// Characters escapeControlCharacters writes as \uXXXX and --user must not hold: the C0 controls U+0000-U+001F, DEL
// U+007F, the C1 controls U+0080-U+009F, and the line and paragraph separators U+2028 and U+2029.
const CONTROL_CHARACTERS = /[\u0000-\u001F\u007F-\u009F\u2028\u2029]/g;

/**
 * Returns the text with every character of CONTROL_CHARACTERS written as \u and four upper-case hex digits; the result
 * holds no line break. For example, 'a\r\nb' becomes 'a\u000D\u000Ab'.
 */
function escapeControlCharacters(text) {
    return String(text).replace(CONTROL_CHARACTERS,
        (character) => `\\u${character.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0')}`);
}

/**
 * Parses `--name value` and `--name=value` arguments, then normalizes and validates the values with parseBaseUrl,
 * validateDatabaseUri and resolveChrome. Throws with the usage line when an argument is unknown, a value is missing,
 * --user is blank or holds a character of CONTROL_CHARACTERS, --base-day is not a calendar date in the YYYY-MM-DD form
 * (any year 0000 to 9999, taken as written), or a value fails its validation. Messages echo no argument text other than
 * an unexpected argument, an unknown argument name or a rejected --base-day, each written through
 * escapeControlCharacters. Returns { baseUrl, httpPort, user, password, dbUri, chrome, baseDay, baseDayMillis }; dbUri
 * is --db-uri unchanged, and baseDayMillis is wallClockMillis of --base-day at 00:00:00.
 */
function parseArguments(argv) {
    const values = {};

    for (let index = 0; index < argv.length; index++) {
        const argument = argv[index];

        if (!argument.startsWith('--')) {
            throw new Error(`unexpected argument ${escapeControlCharacters(argument)}\n${USAGE}`);
        }

        const separator = argument.indexOf('=');
        let name;
        let value;

        if (separator >= 0) {
            name = argument.slice(2, separator);
            value = argument.slice(separator + 1);
        } else {
            name = argument.slice(2);
            value = argv[index + 1];
            index++;
        }

        if (!ARGUMENT_NAMES.includes(name)) {
            throw new Error(`unknown argument --${escapeControlCharacters(name)}\n${USAGE}`);
        }
        if (value === undefined || value === '' || (separator < 0 && value.startsWith('--'))) {
            throw new Error(`missing value of --${name}\n${USAGE}`);
        }

        values[name] = value;
    }

    for (const name of ARGUMENT_NAMES) {
        if (values[name] === undefined) {
            throw new Error(`missing argument --${name}\n${USAGE}`);
        }
    }

    const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(values['base-day']);
    const dayMillis = day ? wallClockMillis(Number(day[1]), Number(day[2]), Number(day[3])) : NaN;

    if (!day || Number.isNaN(dayMillis) || new Date(dayMillis).toISOString().slice(0, 10) !== values['base-day']) {
        throw new Error(`--base-day must be a calendar date in the YYYY-MM-DD form, got `
            + `${escapeControlCharacters(values['base-day'])}\n${USAGE}`);
    }

    if (values.user.trim() === '') {
        throw new Error(`--user must not be blank\n${USAGE}`);
    }
    if (values.user.search(CONTROL_CHARACTERS) >= 0) {
        throw new Error(`--user must not hold control characters (U+0000-U+001F, U+007F-U+009F, U+2028 or U+2029)\n`
            + USAGE);
    }

    const { baseUrl, httpPort } = parseBaseUrl(values['base-url']);

    validateDatabaseUri(values['db-uri']);

    return {
        baseUrl,
        httpPort,
        user: values.user,
        password: values.password,
        dbUri: values['db-uri'],
        chrome: resolveChrome(values.chrome),
        baseDay: values['base-day'],
        baseDayMillis: dayMillis
    };
}

/**
 * Throws unless the process runs on Linux with /proc/net/tcp, on Node.js 22 or later, and with the global fetch,
 * WebSocket, Headers and Headers.prototype.getSetCookie.
 */
function checkRuntime() {
    if (process.platform !== 'linux' || !fs.existsSync('/proc/net/tcp')) {
        throw new Error(`acceptance.test.js requires Linux with /proc/net/tcp, not ${process.platform}`);
    }

    const major = Number(process.versions.node.split('.')[0]);

    if (!(major >= 22)) {
        throw new Error(`acceptance.test.js requires Node.js 22 or later (tested on v22.23.2), not ${process.version}`);
    }

    const missing = [];

    if (typeof fetch !== 'function') {
        missing.push('fetch');
    }
    if (typeof WebSocket !== 'function') {
        missing.push('WebSocket');
    }
    if (typeof Headers !== 'function') {
        missing.push('Headers');
    } else if (typeof Headers.prototype.getSetCookie !== 'function') {
        missing.push('Headers.prototype.getSetCookie');
    }

    if (missing.length > 0) {
        throw new Error(`acceptance.test.js requires the global ${missing.join(', ')}, missing on Node.js ${process.version}`);
    }
}

// Largest number of findings an isolation error or warning names.
const ISOLATION_OFFENDER_LIMIT = 10;

// End of the co-tenancy warning of an isolation check.
const ISOLATION_EXPOSURE = 'The run continues; processes outside this run can read the --password argument and '
    + 'reach the DevTools endpoint of Chrome';

// Interval of the isolation watch, which checks for intrusions from the first isolation check until the after hook,
// in milliseconds.
const ISOLATION_WATCH_INTERVAL_MS = 500;

// Command line of the runner in /proc/<pid>/cmdline once process.title is set.
const PROCESS_TITLE = 'node acceptance.test.js';

// PF_KTHREAD, the flag of a kernel thread in the flags field of /proc/<pid>/stat.
const PF_KTHREAD = 0x00200000;

// Port of a --db-uri that names none, the PostgreSQL default.
const DEFAULT_DATABASE_PORT = 5432;

// Names of the TCP states of /proc/net/tcp and tcp6, by their hexadecimal code.
const TCP_STATE_NAMES = {
    '01': 'ESTABLISHED', '02': 'SYN_SENT', '03': 'SYN_RECV', '04': 'FIN_WAIT1', '05': 'FIN_WAIT2', '06': 'TIME_WAIT',
    '07': 'CLOSE', '08': 'CLOSE_WAIT', '09': 'LAST_ACK', '0A': 'LISTEN', '0B': 'CLOSING', '0C': 'NEW_SYN_RECV'
};

/**
 * Reads /proc/<pid>/stat and /proc/<pid>/status of every process of /proc and returns a Map from each pid, a string, to
 * { ppid, pgid, start, kernel, euid, tracer }: ppid, pgid and start are the parent pid, the process group id and the
 * start time (fields 4, 5 and 22 of stat), kernel whether the flags field of stat holds PF_KTHREAD, euid the effective
 * uid of the Uid: line of status and tracer its TracerPid: value; all but kernel are decimal strings. Pushes '/proc
 * cannot be listed' to `violations` when /proc cannot be read, and 'process <pid> cannot be inspected' for a process
 * whose files fail to read with an error other than ENOENT and ESRCH or lack one of those fields. A process that has
 * exited is skipped.
 */
function readProcesses(violations) {
    const processes = new Map();
    let names;

    try {
        names = fs.readdirSync('/proc');
    } catch (error) {
        violations.push('/proc cannot be listed');
        return processes;
    }

    for (const pid of names.filter((name) => /^\d+$/.test(name))) {
        let stat;
        let status;

        try {
            stat = fs.readFileSync(`/proc/${pid}/stat`, 'latin1');
            status = fs.readFileSync(`/proc/${pid}/status`, 'latin1');
        } catch (error) {
            if (error.code !== 'ENOENT' && error.code !== 'ESRCH') {
                violations.push(`process ${pid} cannot be inspected`);
            }

            continue;
        }

        // The fields after the parenthesised command name, from the state (field 3 of stat) on.
        const nameEnd = stat.lastIndexOf(')');
        const fields = stat.slice(nameEnd + 2).split(' ');
        const lines = status.split('\n').map((line) => line.split(/[ \t]+/).filter((field) => field !== ''));
        const uids = lines.find((line) => line[0] === 'Uid:');
        const tracer = lines.find((line) => line[0] === 'TracerPid:');

        if (nameEnd < 0 || fields.length < 20 || ![1, 2, 6, 19].every((index) => /^\d+$/.test(fields[index]))
            || !uids || uids.length !== 5 || !uids.slice(1).every((uid) => /^\d+$/.test(uid))
            || !tracer || tracer.length !== 2 || !/^\d+$/.test(tracer[1])) {
            violations.push(`process ${pid} cannot be inspected`);
            continue;
        }

        processes.set(pid, {
            ppid: fields[1],
            pgid: fields[2],
            start: fields[19],
            kernel: (Number(fields[6]) & PF_KTHREAD) !== 0,
            euid: uids[2],
            tracer: tracer[1]
        });
    }

    return processes;
}

/** Returns the pids from `pid` up through its parent, its parent's parent and so on, as far as `processes` holds them. */
function ancestorChain(pid, processes) {
    const chain = [];
    let current = pid;

    while (processes.has(current) && !chain.includes(current)) {
        chain.push(current);
        current = processes.get(current).ppid;
    }

    return chain;
}

/** Returns the Set of the pids of `roots` that `processes` holds and of all their descendants. */
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

/**
 * Returns the run root of `chain`, the ancestor chain of this process with this process first: the member farthest
 * from this process whose descriptor 255, the descriptor bash reads a script from, links to the file run-acceptance.sh
 * next to this file; this process when no member does.
 */
function runRoot(chain) {
    let script;

    try {
        script = fs.realpathSync(path.join(__dirname, 'run-acceptance.sh'));
    } catch (error) {
        return chain[0];
    }

    let root = chain[0];

    for (const pid of chain) {
        let link;

        try {
            link = fs.readlinkSync(`/proc/${pid}/fd/255`);
        } catch (error) {
            continue;
        }

        if (link === script) {
            root = pid;
        }
    }

    return root;
}

/** Returns the inodes of the sockets the process `pid` holds, read from its /proc/<pid>/fd links; empty when unreadable. */
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
            // A descriptor closed while the links are read is skipped.
            continue;
        }

        const match = /^socket:\[(\d+)\]$/.exec(link);

        if (match) {
            inodes.add(match[1]);
        }
    }

    return inodes;
}

/**
 * Returns the entries of the tables /proc/net/<protocol> of `protocols` as { protocol, state, local, remote, localPort,
 * remotePort, uid, inode }: state is the hexadecimal state code in upper case, local and remote the address:port
 * fields in upper case as the table writes them, localPort and remotePort their decimal ports, uid and inode decimal
 * strings. A missing table is skipped. Throws when a table cannot be read or holds a line that is not an entry.
 */
function socketTable(protocols) {
    const entries = [];

    for (const protocol of protocols) {
        const table = `/proc/net/${protocol}`;
        let text;

        try {
            text = fs.readFileSync(table, 'latin1');
        } catch (error) {
            if (error.code === 'ENOENT') {
                continue;
            }

            throw new Error(`${table} cannot be read (${error.code})`);
        }

        for (const line of text.split('\n').slice(1)) {
            const fields = line.split(/[ \t]+/).filter((field) => field !== '');

            if (fields.length === 0) {
                continue;
            }

            const local = /^[0-9A-F]+:([0-9A-F]{4})$/i.exec(fields[1] || '');
            const remote = /^[0-9A-F]+:([0-9A-F]{4})$/i.exec(fields[2] || '');

            if (!local || !remote || !/^[0-9A-F]{2}$/i.test(fields[3] || '') || !/^\d+$/.test(fields[7] || '')
                || !/^\d+$/.test(fields[9] || '')) {
                throw new Error(`${table} holds a line that is not a socket entry`);
            }

            entries.push({
                protocol,
                state: fields[3].toUpperCase(),
                local: fields[1].toUpperCase(),
                remote: fields[2].toUpperCase(),
                localPort: parseInt(local[1], 16),
                remotePort: parseInt(remote[1], 16),
                uid: fields[7],
                inode: fields[9]
            });
        }
    }

    return entries;
}

/**
 * Collects the findings of worker isolation once. `trustedPorts` is the Set of the TCP ports of the application and
 * database servers the run uses; `remembered` holds the '<pid>:<start>' keys of the processes admitted by an earlier
 * check that found nothing; `full` is false for a collection of intrusions only. The run is this process with
 * its ancestors, the remembered processes that still run, and the run root (runRoot) and the members of this process's
 * process group when its id is not 0, each with all its descendants. A process is admitted when it belongs to the run,
 * is pid 1 or a kernel thread, or is a server: a holder of a TCP socket listening on a trusted port, a process whose
 * effective uid is the uid of such a socket when that uid is neither 0 nor the runner's effective uid, or a
 * descendant of either. Returns { coTenancy, intrusion, admitted }:
 *   - intrusion: 'process <pid> is traced by process <tracer>' for every process of the run whose TracerPid is not 0;
 *   - coTenancy, in this order:
 *     - '/proc/self/mountinfo cannot be read, ...' when that file cannot be read or is empty;
 *     - unless the runner runs as root, '/proc is mounted with hidepid=<value>, ...' for every hidepid option other
 *       than hidepid=0 and hidepid=off in the mount options (field 6) or the super options (the last field) of a
 *       /proc/self/mountinfo line whose mount point (field 5) is /proc;
 *     - the entries of readProcesses;
 *     - the message of socketTable when a table of /proc/net/tcp, tcp6, udp and udp6 cannot be read or parsed;
 *     - 'process <pid> (uid <uid>) is not part of this run' for every process that is not admitted, naming its
 *       effective uid;
 *     - 'a <protocol> socket on local port <port> (uid <uid>) belongs to no process of this run' for every socket of
 *       those tables whose inode is not 0, that no admitted process holds and whose uid is not the uid of a server as
 *       above, other than a TCP socket whose local port is a trusted port and whose uid is the uid of a socket
 *       listening on that port;
 *   - admitted: the keys of the admitted processes.
 * With `full` false, it reads only /proc/<pid>/stat and status and the descriptor 255 of the ancestors, coTenancy
 * holds only the entries of readProcesses, and admitted holds the keys of the run.
 * Entries name pids, uids, ports and /proc paths only, never a command line, an environment or an argument value.
 */
function isolationViolationsOnce(trustedPorts, remembered, full = true) {
    const effectiveUid = String(process.geteuid());
    const coTenancy = [];
    const intrusion = [];

    if (full) {
        let mountinfo = '';

        try {
            mountinfo = fs.readFileSync('/proc/self/mountinfo', 'utf8');
        } catch (error) {
            mountinfo = '';
        }

        if (mountinfo === '') {
            coTenancy.push('/proc/self/mountinfo cannot be read, so the process list cannot be checked');
        } else if (effectiveUid !== '0') {
            for (const line of mountinfo.split('\n')) {
                const fields = line.split(' ');

                if (fields[4] !== '/proc') {
                    continue;
                }

                for (const option of `${fields[5]},${fields[fields.length - 1]}`.split(',')) {
                    const value = option.startsWith('hidepid=') ? option.slice('hidepid='.length) : null;

                    if (value !== null && value !== '0' && value !== 'off') {
                        coTenancy.push(`/proc is mounted with hidepid=${value}, so other accounts' processes cannot be `
                            + 'listed');
                    }
                }
            }
        }
    }

    const processes = readProcesses(coTenancy);
    const self = String(process.pid);
    const chain = ancestorChain(self, processes);
    const group = processes.has(self) ? processes.get(self).pgid : '0';
    const key = (pid) => `${pid}:${processes.get(pid).start}`;
    const roots = [runRoot(chain)];

    for (const [pid, info] of processes) {
        if (group !== '0' && info.pgid === group) {
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
        const { tracer } = processes.get(pid);

        if (tracer !== '0') {
            intrusion.push(`process ${pid} is traced by process ${tracer}`);
        }
    }

    if (!full) {
        return { coTenancy, intrusion, admitted: new Set([...run].map(key)) };
    }

    let table = [];

    try {
        table = socketTable(['tcp', 'tcp6', 'udp', 'udp6']);
    } catch (error) {
        coTenancy.push(error.message);
    }

    const isTcp = (entry) => entry.protocol === 'tcp' || entry.protocol === 'tcp6';
    const listening = table.filter((entry) => isTcp(entry) && entry.state === '0A' && trustedPorts.has(entry.localPort));
    const listeningInodes = new Set(listening.map((entry) => entry.inode));
    const serverAccounts = new Set(listening.map((entry) => entry.uid)
        .filter((uid) => uid !== '0' && uid !== effectiveUid));
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
    const isAdmitted = (pid) => run.has(pid) || servers.has(pid) || pid === '1' || processes.get(pid).kernel;
    const admitted = new Set();

    for (const [pid, info] of processes) {
        if (isAdmitted(pid)) {
            admitted.add(key(pid));
        } else {
            coTenancy.push(`process ${pid} (uid ${info.euid}) is not part of this run`);
        }
    }

    for (const entry of table) {
        if (entry.inode === '0' || serverAccounts.has(entry.uid) || (holders.get(entry.inode) || []).some(isAdmitted)) {
            continue;
        }
        if (isTcp(entry) && trustedPorts.has(entry.localPort)
            && listening.some((server) => server.localPort === entry.localPort && server.uid === entry.uid)) {
            continue;
        }

        coTenancy.push(`a ${entry.protocol} socket on local port ${entry.localPort} (uid ${entry.uid}) belongs to no `
            + 'process of this run');
    }

    return { coTenancy, intrusion, admitted };
}

// '<pid>:<start>' keys of the processes the isolation checks of this runner that found nothing have admitted.
const ISOLATION_ADMITTED = new Set();

/**
 * Returns { coTenancy, intrusion }, the findings of worker isolation of the Set of trusted ports `trustedPorts` (see
 * isolationViolationsOnce, with `full` false for intrusions only, coTenancy then empty): none when a first collection
 * finds none, and otherwise the findings of that collection that a second one, made at once, finds again. When none
 * remain, adds the keys of the processes the first collection admitted to ISOLATION_ADMITTED.
 */
function workerIsolation(trustedPorts, full = true) {
    const first = isolationViolationsOnce(trustedPorts, ISOLATION_ADMITTED, full);
    let coTenancy = full ? [...new Set(first.coTenancy)] : [];
    let intrusion = [...new Set(first.intrusion)];

    if (coTenancy.length > 0 || intrusion.length > 0) {
        const second = isolationViolationsOnce(trustedPorts, ISOLATION_ADMITTED, full);
        const coTenancyAgain = new Set(second.coTenancy);
        const intrusionAgain = new Set(second.intrusion);

        coTenancy = coTenancy.filter((finding) => coTenancyAgain.has(finding));
        intrusion = intrusion.filter((finding) => intrusionAgain.has(finding));
    }

    if (coTenancy.length === 0 && intrusion.length === 0) {
        first.admitted.forEach((admittedKey) => ISOLATION_ADMITTED.add(admittedKey));
    }

    return { coTenancy, intrusion };
}

/** Returns at most ISOLATION_OFFENDER_LIMIT of the findings joined with '; ', and the number of the others. */
function isolationReport(findings) {
    const more = findings.length > ISOLATION_OFFENDER_LIMIT
        ? `; and ${findings.length - ISOLATION_OFFENDER_LIMIT} more` : '';

    return `${findings.slice(0, ISOLATION_OFFENDER_LIMIT).join('; ')}${more}`;
}

/**
 * Returns the TCP ports of the database servers of --db-uri (checked by validateDatabaseUri): the ports of the last
 * port query parameter, else of the authority, each item of a comma-separated list that is a port 1-65535;
 * DEFAULT_DATABASE_PORT when there is none.
 */
function databasePorts(value) {
    const url = new URL(value);
    let ports = url.port;

    for (const token of url.search.slice(1).split('&')) {
        const parts = token.split('=');

        if (parts.length === 2 && percentDecode(parts[0]) === 'port') {
            ports = percentDecode(parts[1]) || '';
        }
    }

    const numbers = ports.split(',').map((item) => item.trim()).filter((item) => /^\d{1,5}$/.test(item))
        .map(Number).filter((port) => port >= 1 && port <= 65535);

    return numbers.length > 0 ? numbers : [DEFAULT_DATABASE_PORT];
}

/**
 * Checks worker isolation with workerIsolation(TRUSTED_PORTS) before the step `purpose` names. Throws when the check
 * finds an intrusion; the error names `purpose` and the intrusions as isolationReport gives them. When it finds
 * co-tenancy only, writes one warning line to stderr, through escapeControlCharacters, naming `purpose`, the number of
 * findings, the findings as isolationReport gives them and ISOLATION_EXPOSURE, and returns.
 *
 * Example: assertIsolatedWorker('before it starts Chrome') throws "acceptance.test.js stops before it starts Chrome:
 * worker isolation check failed: process 4242 is traced by process 4250" while a process of the run is traced, and
 * writes "acceptance.test.js: warning: worker isolation check before it starts Chrome: the worker is not private
 * (1 finding): process 4300 (uid 0) is not part of this run. The run continues; ..." while an unrelated process runs.
 */
function assertIsolatedWorker(purpose) {
    const { coTenancy, intrusion } = workerIsolation(TRUSTED_PORTS);

    if (intrusion.length > 0) {
        throw new Error(`acceptance.test.js stops ${purpose}: worker isolation check failed: `
            + isolationReport(intrusion));
    }
    if (coTenancy.length > 0) {
        const count = `${coTenancy.length} finding${coTenancy.length === 1 ? '' : 's'}`;

        process.stderr.write(`${escapeControlCharacters(`acceptance.test.js: warning: worker isolation check ${purpose}: `
            + `the worker is not private (${count}): ${isolationReport(coTenancy)}. ${ISOLATION_EXPOSURE}`)}\n`);
    }
}

// The error of the isolation watch once it has found an intrusion, or null.
let ISOLATION_ERROR = null;

// Interval timer of the isolation watch, or null while it does not run.
let ISOLATION_TIMER = null;

// DevTools client whose DevTools port the isolation watch checks (DevTools.devToolsPortViolations), or null.
let ISOLATION_DEVTOOLS = null;

/**
 * Collects the intrusions of workerIsolation(TRUSTED_PORTS, false) and the violations of
 * ISOLATION_DEVTOOLS.devToolsPortViolations(), which are intrusions too, and hands them to failIsolation when there
 * are any. An error while collecting counts as an intrusion. Does nothing once ISOLATION_ERROR is set.
 */
function checkIsolation() {
    if (ISOLATION_ERROR !== null) {
        return;
    }

    let intrusion;

    try {
        intrusion = workerIsolation(TRUSTED_PORTS, false).intrusion;

        if (ISOLATION_DEVTOOLS !== null) {
            intrusion = [...new Set(intrusion.concat(ISOLATION_DEVTOOLS.devToolsPortViolations()))];
        }
    } catch (error) {
        intrusion = [error.message];
    }

    if (intrusion.length > 0) {
        failIsolation(intrusion);
    }
}

/**
 * Acts on the first call only: stops the isolation watch, sets ISOLATION_ERROR, which names the intrusions as
 * isolationReport gives them, writes it to stderr, sets process.exitCode to 1 and stops the Chrome of
 * ISOLATION_DEVTOOLS (DevTools.stopForIsolation). From then on every case fails in the beforeEach hook, every DevTools
 * command is rejected and the after hook fails.
 */
function failIsolation(intrusion) {
    if (ISOLATION_ERROR !== null) {
        return;
    }

    stopIsolationWatch();
    ISOLATION_ERROR = new Error('acceptance.test.js stopped the run: worker isolation check failed while it ran: '
        + isolationReport(intrusion));
    process.exitCode = 1;
    process.stderr.write(`${ISOLATION_ERROR.message}\n`);

    if (ISOLATION_DEVTOOLS !== null) {
        ISOLATION_DEVTOOLS.stopForIsolation(ISOLATION_ERROR);
    }
}

/**
 * Runs checkIsolation, which checks for intrusions only, every ISOLATION_WATCH_INTERVAL_MS until stopIsolationWatch or
 * failIsolation.
 */
function startIsolationWatch() {
    ISOLATION_TIMER = setInterval(checkIsolation, ISOLATION_WATCH_INTERVAL_MS);
    ISOLATION_TIMER.unref();
}

/** Stops the isolation watch. */
function stopIsolationWatch() {
    clearInterval(ISOLATION_TIMER);
    ISOLATION_TIMER = null;
}

/**
 * Sets process.title to PROCESS_TITLE, then throws unless /proc/self/cmdline, without its trailing NUL bytes, equals
 * PROCESS_TITLE exactly. The error never quotes the command line.
 */
function replaceCommandLine() {
    process.title = PROCESS_TITLE;

    let cmdline;

    try {
        cmdline = fs.readFileSync('/proc/self/cmdline', 'latin1');
    } catch (error) {
        throw new Error(`acceptance.test.js cannot read /proc/self/cmdline after setting process.title: ${error.code}`);
    }

    if (cmdline.replace(/\0+$/, '') !== PROCESS_TITLE) {
        throw new Error(`acceptance.test.js could not replace its command line: /proc/self/cmdline does not read `
            + `'${PROCESS_TITLE}' after process.title was set to it`);
    }
}

checkRuntime();

const ARGS = parseArguments(process.argv.slice(2));

replaceCommandLine();

// TCP ports of the application and database servers of the run.
const TRUSTED_PORTS = new Set([ARGS.httpPort].concat(databasePorts(ARGS.dbUri)));

assertIsolatedWorker('before it registers a case');
startIsolationWatch();

// ---------------------------------------------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------------------------------------------

const REPO_ROOT = path.resolve(__dirname, '../../../../../..');

const ORDERS_BUNDLE = path.join(REPO_ROOT,
    'mes/mes-plugins/mes-plugins-orders/src/main/resources/orders/locales/orders_en.properties');
const CMMS_BUNDLE = path.join(REPO_ROOT,
    'mes/mes-plugins/mes-plugins-cmms-machine-parts/src/main/resources/cmmsMachineParts/locales/cmmsMachineParts_en.properties');
const QCADOO_VIEW_BUNDLE = path.join(REPO_ROOT,
    'qcadoo/qcadoo-view/src/main/resources/qcadooView/locales/qcadooView_en.properties');

// Bundle of each message key prefix.
const BUNDLE_BY_PREFIX = {
    orders: ORDERS_BUNDLE,
    cmmsMachineParts: CMMS_BUNDLE,
    qcadooView: QCADOO_VIEW_BUNDLE
};

const BOARD_PATH = '/page/cmmsMachineParts/productionMaintenanceGantt.html';

const CASE_TIMEOUT_MS = 600000;

// Deadline of one HttpSession request, from sending it to reading the whole response body.
const HTTP_REQUEST_TIMEOUT_MS = 180000;

// Deadline for the DevTools socket to open once Chrome has printed its endpoint.
const DEVTOOLS_CONNECT_TIMEOUT_MS = 30000;

// Deadline for the reply to one DevTools command, and to Page.navigate.
const DEVTOOLS_COMMAND_TIMEOUT_MS = 30000;
const DEVTOOLS_NAVIGATE_TIMEOUT_MS = 60000;

// Wait for Chrome to exit after SIGTERM, and again after SIGKILL.
const CHROME_EXIT_WAIT_MS = 5000;

// Exit status after SIGINT and SIGTERM, and the deadline of the cleanup that runs before it.
const SIGNAL_EXIT_CODES = { SIGINT: 130, SIGTERM: 143 };
const SIGNAL_CLEANUP_TIMEOUT_MS = 15000;

// Hosts a DevTools endpoint may name.
const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]']);

// Longest excerpt of an external text quoted in an error message by diagnosticText and responseExcerpt.
const RESPONSE_EXCERPT_LENGTH = 300;

// Literal values diagnosticText replaces by [redacted], longest first (see diagnosticSecrets).
const DIAGNOSTIC_SECRETS = diagnosticSecrets(ARGS);

// Deadline for the board to become idle after a drop, and the time without a board request event that counts as idle.
const DRAG_IDLE_TIMEOUT_MS = 30000;
const DRAG_IDLE_QUIET_MS = 1500;

// ganttChart.js geometry: CELL_WIDTH px per hour at H1 and CELL_HEIGHT px per row.
const CELL_WIDTH_PX = 25;
const CELL_HEIGHT_PX = 30;

// Roles with a position in every fixture schedule.
const POSITION_ROLES = ['A1', 'A2', 'A3', 'A4', 'A5', 'B1', 'B2', 'B3', 'B4', 'B5'];

// Tables of the checksums other than the schedule's positions, keyed by checksum name.
const PS_PPS_TABLES = {
    planOrderTimeCalculation: 'public.productionscheduling_planordertimecalculation',
    planOperCompTimeCalculation: 'public.productionscheduling_planopercomptimecalculation',
    planProductionPerShift: 'public.productionpershift_planproductionpershift',
    planProgressForDay: 'public.productionpershift_planprogressforday',
    planDailyProgress: 'public.productionpershift_plandailyprogress'
};

// ---------------------------------------------------------------------------------------------------------------
// Waiting
// ---------------------------------------------------------------------------------------------------------------

function pause(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/** Error that ends a waitFor poll at once. */
class FatalError extends Error {
}

/**
 * Calls `probe` every `interval` ms until it returns a truthy value, and returns that value. Throws, naming
 * `description`, the message of the last probe error and the text of `detail()`, each through diagnosticText, when
 * `timeout` ms pass first. A FatalError thrown by the probe ends the poll at once.
 */
async function waitFor(description, probe, { timeout = 30000, interval = 100, detail = null } = {}) {
    const deadline = Date.now() + timeout;
    let lastError = null;

    for (;;) {
        try {
            const value = await probe();

            if (value) {
                return value;
            }

            lastError = null;
        } catch (error) {
            if (error instanceof FatalError) {
                throw error;
            }

            lastError = error;
        }

        if (Date.now() >= deadline) {
            throw new Error(`timed out after ${timeout} ms waiting for ${description}`
                + (lastError ? `: ${diagnosticText(lastError.message)}` : '')
                + (detail ? `; last state: ${diagnosticText(detail())}` : ''));
        }

        await pause(interval);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Wall-clock time
// ---------------------------------------------------------------------------------------------------------------

function pad2(value) {
    return String(value).padStart(2, '0');
}

/**
 * Returns the wall-clock milliseconds (UTC milliseconds of the same fields) of a date and time in the proleptic
 * Gregorian calendar, built with setUTCFullYear and setUTCHours on new Date(0): the year is taken as written, the
 * years 0000 to 0099 included, month is 1 to 12, and a field out of its range rolls over into the next larger one.
 * For example, wallClockMillis(99, 1, 1) is the millisecond of 0099-01-01 00:00:00, not of 1999-01-01.
 */
function wallClockMillis(year, month, day, hours = 0, minutes = 0, seconds = 0) {
    const date = new Date(0);

    date.setUTCFullYear(year, month - 1, day);
    date.setUTCHours(hours, minutes, seconds, 0);

    return date.getTime();
}

/**
 * Parses 'YYYY-MM-DD HH:MM:SS' into wall-clock milliseconds with wallClockMillis, for every year 0000 to 9999. Throws,
 * quoting the text through escapeControlCharacters, when it does not have that form.
 */
function parseWallClock(text) {
    const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(text);

    if (!match) {
        throw new Error(`not a wall-clock date YYYY-MM-DD HH:MM:SS: ${escapeControlCharacters(text)}`);
    }

    return wallClockMillis(Number(match[1]), Number(match[2]), Number(match[3]), Number(match[4]), Number(match[5]),
        Number(match[6]));
}

/**
 * Formats wall-clock milliseconds as 'YYYY-MM-DD HH:MM:SS' with the getUTC* accessors and the year padded to four
 * digits; parseWallClock reads the result back for every year 0000 to 9999.
 */
function formatWallClock(millis) {
    const date = new Date(millis);
    const year = String(date.getUTCFullYear()).padStart(4, '0');

    return `${year}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())} `
        + `${pad2(date.getUTCHours())}:${pad2(date.getUTCMinutes())}:${pad2(date.getUTCSeconds())}`;
}

/** Returns 'YYYY-MM-DD HH:MM:00' of --base-day + dayOffset days at the wall-clock time hhmm ('HH:MM'). */
function at(dayOffset, hhmm) {
    const match = /^(\d{2}):(\d{2})$/.exec(hhmm);

    if (!match) {
        throw new Error(`not a time HH:MM: ${hhmm}`);
    }

    return formatWallClock(ARGS.baseDayMillis + dayOffset * 86400000 + Number(match[1]) * 3600000
        + Number(match[2]) * 60000);
}

/** Returns the minutes from wall-clock date a to wall-clock date b. */
function minutesBetween(a, b) {
    return (parseWallClock(b) - parseWallClock(a)) / 60000;
}

// ---------------------------------------------------------------------------------------------------------------
// psql
// ---------------------------------------------------------------------------------------------------------------

// psql processes that have not closed yet.
const PSQL_CHILDREN = new Set();

// Temporary directories removed by the after hook.
const TEMP_DIRS = new Set();

// Promise of the single run of releaseResources, or null before it starts.
let RELEASE = null;

// Deadline of an ordinary psql query, in ms.
const PSQL_QUERY_TIMEOUT_MS = 60000;

// Deadline of a monitoring query run inside a waitFor poll, in ms.
const PSQL_PROBE_TIMEOUT_MS = 10000;

// Deadline of a psql session that holds a transaction open, in ms.
const PSQL_HELD_TIMEOUT_MS = 180000;

// Time from SIGTERM to SIGKILL of a psql process past its deadline, in ms.
const PSQL_KILL_GRACE_MS = 5000;

// Characters of psql standard output collected before the process is killed.
const PSQL_STDOUT_LIMIT = 8 * 1024 * 1024;

// Characters of one psql standard output line read line by line before the process is killed.
const PSQL_LINE_LIMIT = 65536;

// Characters of psql standard error kept as a rolling tail.
const PSQL_STDERR_TAIL = 16384;

// PGCONNECT_TIMEOUT, in seconds, of every psql process; a connect_timeout parameter of --db-uri takes precedence over it.
const PSQL_CONNECT_TIMEOUT_SECONDS = '10';

// Outcome of the first psqlConnectionUri call, { uri } or { error }; null before that call.
let PSQL_CONNECTION = null;

/**
 * Creates the directory <system temporary directory>/pmg-acceptance-<prefix>-* and adds it to TEMP_DIRS. Throws,
 * creating nothing, once releaseResources has started.
 */
function makeTempDir(prefix) {
    if (RELEASE !== null) {
        throw new Error(`the temporary directory pmg-acceptance-${prefix}-* was not created: acceptance.test.js is `
            + 'releasing its resources');
    }

    const directory = fs.mkdtempSync(path.join(os.tmpdir(), `pmg-acceptance-${prefix}-`));

    TEMP_DIRS.add(directory);

    return directory;
}

/** Returns the text as a field of a libpq password file: every \ written as \\ and every : as \:. */
function pgpassField(text) {
    return text.replace(/[\\:]/g, (character) => `\\${character}`);
}

/**
 * Returns --db-uri (validated by validateDatabaseUri) without its password:
 * - the user information keeps its user name followed by '@', and is dropped with its '@' when the user name is empty;
 * - every query parameter whose percent-decoded key is password is removed.
 * The effective password is the percent-decoded value of the last password parameter, else the percent-decoded
 * password of the user information. When it is not empty, it is written as the line `*:*:*:*:<password>` (escaped by
 * pgpassField) to the file pgpass, mode 600, in a new directory of makeTempDir('pgpass'), and every passfile parameter
 * is replaced by one passfile parameter naming that file; otherwise the passfile parameters stay as given.
 * Example: 'postgresql://u:p@localhost/db?passfile=x' gives 'postgresql://u@localhost/db?passfile=<encoded file>'.
 * Throws when the password does not percent-decode to UTF-8 or holds a line break, or when the file is accessible to
 * other users. The message never holds the password.
 */
function passwordlessDatabaseUri(value) {
    const { prefix, userInfo, rest, query } = splitDatabaseUri(value);
    const userEnd = userInfo === null ? -1 : userInfo.indexOf(':');
    const user = userInfo === null ? '' : (userEnd < 0 ? userInfo : userInfo.slice(0, userEnd));
    const passwords = userEnd < 0 ? [] : [userInfo.slice(userEnd + 1)];
    const parameters = [];

    for (const token of query === null ? [] : query.split('&')) {
        const key = percentDecode(token.slice(0, token.indexOf('=')));

        if (key === 'password') {
            passwords.push(token.slice(token.indexOf('=') + 1));
        } else {
            parameters.push({ key, token });
        }
    }

    const password = passwords.length > 0 ? percentDecode(passwords[passwords.length - 1]) : '';

    if (password === null) {
        throw new Error('the password of --db-uri does not percent-decode to UTF-8');
    }
    if (/[\r\n]/.test(password)) {
        throw new Error('the password of --db-uri holds a line break, which a libpq password file cannot hold');
    }

    let tokens = parameters.map((parameter) => parameter.token);

    if (password !== '') {
        const file = path.join(makeTempDir('pgpass'), 'pgpass');

        fs.writeFileSync(file, `*:*:*:*:${pgpassField(password)}\n`, { mode: 0o600, flag: 'wx' });

        if ((fs.statSync(file).mode & 0o077) !== 0) {
            throw new Error(`the password file ${file} is accessible to other users`);
        }

        tokens = parameters.filter((parameter) => parameter.key !== 'passfile').map((parameter) => parameter.token)
            .concat(`passfile=${encodeURIComponent(file)}`);
    }

    return `${prefix}${user === '' ? '' : `${user}@`}${rest}${tokens.length > 0 ? `?${tokens.join('&')}` : ''}`;
}

/**
 * Returns passwordlessDatabaseUri(ARGS.dbUri), computed on the first call; every later call returns the same URI, or
 * throws the same error, without writing another password file.
 */
function psqlConnectionUri() {
    if (PSQL_CONNECTION === null) {
        try {
            PSQL_CONNECTION = { uri: passwordlessDatabaseUri(ARGS.dbUri) };
        } catch (error) {
            PSQL_CONNECTION = { error };
        }
    }
    if (PSQL_CONNECTION.error) {
        throw PSQL_CONNECTION.error;
    }

    return PSQL_CONNECTION.uri;
}

/** Returns psqlConnectionUri() with application_name=pmg-acceptance-<app> appended. */
function databaseUri(app) {
    const uri = psqlConnectionUri();
    const separator = uri.includes('?') ? '&' : '?';

    return `${uri}${separator}application_name=${encodeURIComponent(`pmg-acceptance-${app}`)}`;
}

/**
 * Returns a copy of process.env without the variables whose names start with PG, and with PGCONNECT_TIMEOUT set to
 * PSQL_CONNECT_TIMEOUT_SECONDS.
 */
function psqlEnvironment() {
    const environment = {};

    for (const [name, value] of Object.entries(process.env)) {
        if (!name.startsWith('PG')) {
            environment[name] = value;
        }
    }

    environment.PGCONNECT_TIMEOUT = PSQL_CONNECT_TIMEOUT_SECONDS;

    return environment;
}

/** Returns the first non-blank line of the SQL script, cut to 120 characters. */
function sqlLabel(sql) {
    const line = String(sql).split('\n').map((text) => text.trim()).find((text) => text !== '') || '(empty script)';

    return line.length > 120 ? `${line.slice(0, 117)}...` : line;
}

/**
 * Spawns psql -X -w -A -t -q -v ON_ERROR_STOP=1 against the acceptance database with application name
 * pmg-acceptance-<app> and the environment of psqlEnvironment(). Returns {child, done, write, end, terminate, isClosed}.
 *
 * Options:
 *   operation  label of the process in error messages
 *   timeout    deadline in ms; past it the process gets SIGTERM, then SIGKILL after PSQL_KILL_GRACE_MS
 *   onLine     function called with each standard output line; with it, standard output is not collected and done
 *              resolves with ''; without it, done resolves with the standard output, limited to PSQL_STDOUT_LIMIT
 *              characters
 *
 * done settles once the process has closed or has failed to start. It resolves when psql exited with 0 and no failure or
 * stream error was recorded, and otherwise rejects with an error naming the app, the operation, the exit status or the
 * recorded failure (start failure, missing standard stream, deadline, output limit, line handler error), the stream
 * errors and the standard error tail (the last PSQL_STDERR_TAIL characters). Once releaseResources has started,
 * spawnPsql starts no process: done rejects at once with an error saying the runner is releasing its resources.
 *
 * write(text) and end(text) write to standard input and return false, writing nothing, once the process has closed or
 * its standard input has ended. terminate(error) records error as the failure, sends SIGTERM and schedules SIGKILL.
 * isClosed() tells whether done has settled.
 */
function spawnPsql(app, { operation = 'psql session', timeout = PSQL_QUERY_TIMEOUT_MS, onLine = null } = {}) {
    const context = `psql (${app}, ${operation})`;
    const handle = { child: null, done: null, write: null, end: null, terminate: null, isClosed: null };
    let stdout = '';
    let stderr = '';
    let partialLine = '';
    let failure = null;
    const streamErrors = [];
    let closed = false;
    let deadlineTimer = null;
    let killTimer = null;
    let settleDone = null;

    handle.done = new Promise((resolve, reject) => {
        settleDone = { resolve, reject };
    });

    const stderrTail = () => stderr.trim() || '(no standard error)';

    const kill = (signal) => {
        if (!handle.child || closed) {
            return;
        }

        try {
            handle.child.kill(signal);
        } catch (error) {
            streamErrors.push(`kill ${signal}: ${error.message}`);
        }
    };

    const terminate = (error) => {
        if (closed) {
            return;
        }
        if (!failure) {
            failure = error;
        }

        kill('SIGTERM');

        if (!killTimer) {
            killTimer = setTimeout(() => kill('SIGKILL'), PSQL_KILL_GRACE_MS);
        }
    };

    const finish = (code, signal) => {
        if (closed) {
            return;
        }

        closed = true;
        clearTimeout(deadlineTimer);
        clearTimeout(killTimer);
        PSQL_CHILDREN.delete(handle.child);

        const streams = streamErrors.length > 0 ? ` (stream errors: ${streamErrors.join('; ')})` : '';

        if (failure) {
            settleDone.reject(new Error(`${failure.message}${streams}; standard error: ${stderrTail()}`));
        } else if (code === 0 && streamErrors.length === 0) {
            settleDone.resolve(stdout);
        } else {
            settleDone.reject(new Error(`${context} exited with ${code === null ? signal : code}${streams}: ${stderrTail()}`));
        }
    };

    const writable = () => !closed && handle.child !== null && handle.child.stdin !== null
        && !handle.child.stdin.destroyed && !handle.child.stdin.writableEnded;

    handle.write = (text) => {
        if (!writable()) {
            return false;
        }

        handle.child.stdin.write(text);

        return true;
    };
    handle.end = (text) => {
        if (!writable()) {
            return false;
        }

        handle.child.stdin.end(text);

        return true;
    };
    handle.terminate = terminate;
    handle.isClosed = () => closed;

    if (RELEASE !== null) {
        failure = new Error(`${context} was not started: acceptance.test.js is releasing its resources`);
        finish(null, null);

        return handle;
    }

    try {
        handle.child = childProcess.spawn('psql',
            ['-X', '-w', '-A', '-t', '-q', '-v', 'ON_ERROR_STOP=1', '-d', databaseUri(app)],
            { stdio: ['pipe', 'pipe', 'pipe'], env: psqlEnvironment() });
    } catch (error) {
        failure = new Error(`${context} could not start: ${error.message}`);
        finish(null, null);

        return handle;
    }

    const child = handle.child;

    PSQL_CHILDREN.add(child);
    child.on('error', (error) => {
        if (child.pid === undefined) {
            if (!failure) {
                failure = new Error(`${context} could not start: ${error.message}`);
            }

            finish(null, null);

            return;
        }

        terminate(new Error(`${context} failed: ${error.message}`));
    });
    child.on('close', (code, signal) => finish(code, signal));

    deadlineTimer = setTimeout(() => terminate(new Error(`${context} exceeded its ${timeout} ms deadline`)), timeout);

    for (const [name, stream] of [['standard input', child.stdin], ['standard output', child.stdout],
        ['standard error', child.stderr]]) {
        if (stream) {
            stream.on('error', (error) => streamErrors.push(`${name}: ${error.code || error.message}`));
        }
    }

    if (!child.stdin || !child.stdout || !child.stderr) {
        terminate(new Error(`${context} started without its standard input, output and error pipes`));

        return handle;
    }

    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => {
        stderr = (stderr + chunk).slice(-PSQL_STDERR_TAIL);
    });
    child.stdout.on('data', (chunk) => {
        if (closed || failure) {
            return;
        }

        if (!onLine) {
            if (stdout.length + chunk.length > PSQL_STDOUT_LIMIT) {
                terminate(new Error(`${context} output exceeded ${PSQL_STDOUT_LIMIT} characters`));

                return;
            }

            stdout += chunk;

            return;
        }

        const lines = (partialLine + chunk).split('\n');

        partialLine = lines.pop();

        if (partialLine.length > PSQL_LINE_LIMIT) {
            terminate(new Error(`${context} printed a line longer than ${PSQL_LINE_LIMIT} characters`));

            return;
        }

        for (const line of lines) {
            try {
                onLine(line);
            } catch (error) {
                terminate(new Error(`${context} output line handler failed on ${JSON.stringify(line.slice(0, 200))}: `
                    + error.message));

                return;
            }
        }
    });

    return handle;
}

/**
 * Runs the SQL script with psql and returns its standard output. Rejects with the error of spawnPsql's done and the
 * script. Options: app (default 'main'), timeout in ms (default PSQL_QUERY_TIMEOUT_MS).
 */
async function psql(sql, { app = 'main', timeout = PSQL_QUERY_TIMEOUT_MS } = {}) {
    const session = spawnPsql(app, { operation: sqlLabel(sql), timeout });

    session.end(sql);

    try {
        return await session.done;
    } catch (error) {
        throw new Error(`${error.message}\n--- SQL ---\n${sql}`);
    }
}

/** Runs the SQL script and parses its trimmed output as JSON. */
async function psqlJson(sql, options) {
    const output = (await psql(sql, options)).trim();

    try {
        return JSON.parse(output);
    } catch (error) {
        throw new Error(`psql output is not JSON (${error.message}): ${output}\n--- SQL ---\n${sql}`);
    }
}

/** Returns the integer printed by a single-value query. */
async function psqlInteger(sql, options) {
    const output = (await psql(sql, options)).trim();

    if (!/^-?\d+$/.test(output)) {
        throw new Error(`psql output is not an integer: ${output}\n--- SQL ---\n${sql}`);
    }

    return Number(output);
}

// ---------------------------------------------------------------------------------------------------------------
// Translations
// ---------------------------------------------------------------------------------------------------------------

/** Replaces the escapes \uXXXX, \t, \n, \r, \f and \<char> of a java.util.Properties key or value. */
function unescapeProperty(text) {
    let result = '';

    for (let index = 0; index < text.length; index++) {
        const character = text[index];

        if (character !== '\\' || index === text.length - 1) {
            result += character;
            continue;
        }

        const next = text[++index];

        if (next === 'u') {
            const hex = text.slice(index + 1, index + 5);

            if (!/^[0-9a-fA-F]{4}$/.test(hex)) {
                throw new Error(`malformed \\uXXXX escape in ${text}`);
            }

            result += String.fromCharCode(parseInt(hex, 16));
            index += 4;
        } else if (next === 't') {
            result += '\t';
        } else if (next === 'n') {
            result += '\n';
        } else if (next === 'r') {
            result += '\r';
        } else if (next === 'f') {
            result += '\f';
        } else {
            result += next;
        }
    }

    return result;
}

function endsWithOddBackslashes(line) {
    let count = 0;

    for (let index = line.length - 1; index >= 0 && line[index] === '\\'; index--) {
        count++;
    }

    return count % 2 === 1;
}

/**
 * Loads a .properties file with java.util.Properties semantics: UTF-8 without BOM, '#' and '!' comment lines,
 * continuation lines ending with an odd number of backslashes, the first unescaped '=', ':' or whitespace as the
 * key separator, leading value whitespace trimmed, and escapes replaced.
 */
function loadProperties(file) {
    let text = fs.readFileSync(file, 'utf8');

    if (text.charCodeAt(0) === 0xFEFF) {
        text = text.slice(1);
    }

    const lines = text.split(/\r\n|\r|\n/);
    const properties = new Map();
    let lineIndex = 0;

    while (lineIndex < lines.length) {
        let line = lines[lineIndex++].replace(/^[ \t\f]+/, '');

        if (line === '' || line[0] === '#' || line[0] === '!') {
            continue;
        }

        while (endsWithOddBackslashes(line)) {
            line = line.slice(0, -1);

            if (lineIndex >= lines.length) {
                break;
            }

            line += lines[lineIndex++].replace(/^[ \t\f]+/, '');
        }

        let keyEnd = 0;
        let valueStart = line.length;
        let hasSeparator = false;
        let precedingBackslash = false;

        for (; keyEnd < line.length; keyEnd++) {
            const character = line[keyEnd];

            if ((character === '=' || character === ':') && !precedingBackslash) {
                valueStart = keyEnd + 1;
                hasSeparator = true;
                break;
            }
            if ((character === ' ' || character === '\t' || character === '\f') && !precedingBackslash) {
                valueStart = keyEnd + 1;
                break;
            }

            precedingBackslash = character === '\\' ? !precedingBackslash : false;
        }

        while (valueStart < line.length) {
            const character = line[valueStart];

            if (character !== ' ' && character !== '\t' && character !== '\f') {
                if (!hasSeparator && (character === '=' || character === ':')) {
                    hasSeparator = true;
                } else {
                    break;
                }
            }

            valueStart++;
        }

        properties.set(unescapeProperty(line.slice(0, keyEnd)), unescapeProperty(line.slice(valueStart)));
    }

    return properties;
}

// Loaded bundles by file.
const BUNDLES = new Map();

function bundleOf(key) {
    const file = BUNDLE_BY_PREFIX[key.split('.')[0]];

    if (!file) {
        throw new Error(`no bundle for message key ${key}`);
    }
    if (!BUNDLES.has(file)) {
        BUNDLES.set(file, loadProperties(file));
    }

    return { file, properties: BUNDLES.get(file) };
}

/**
 * Returns the English translation of the key. With arguments, replaces {n} with the n-th argument and '' with '.
 * Throws, naming the key and the bundle file, when the key is missing.
 */
function message(key, ...args) {
    const { file, properties } = bundleOf(key);

    if (!properties.has(key)) {
        throw new Error(`message key ${key} not found in ${file}`);
    }

    const value = properties.get(key);

    if (args.length === 0) {
        return value;
    }

    return value.replace(/\{(\d+)\}/g, (placeholder, index) => (Number(index) < args.length
        ? String(args[Number(index)]) : placeholder)).replace(/''/g, '\'');
}

const HTML_ENTITIES = {
    '&amp;': '&',
    '&lt;': '<',
    '&gt;': '>',
    '&quot;': '"',
    '&#39;': '\'',
    '&#x27;': '\'',
    '&nbsp;': ' '
};

/** Replaces tags with a space, decodes the common HTML entities and removes all whitespace. */
function norm(text) {
    return String(text === undefined || text === null ? '' : text)
        .replace(/<[^>]*>/g, ' ')
        .replace(/&(?:amp|lt|gt|quot|#39|#x27|nbsp);/g, (entity) => HTML_ENTITIES[entity])
        .replace(/\s+/g, '');
}

// ---------------------------------------------------------------------------------------------------------------
// HTTP session
// ---------------------------------------------------------------------------------------------------------------

/** Returns the value of an attribute of an HTML tag, or undefined. */
function tagAttribute(tag, name) {
    const match = new RegExp(`\\s${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i').exec(tag);

    if (!match) {
        return undefined;
    }

    return match[1] !== undefined ? match[1] : (match[2] !== undefined ? match[2] : match[3]);
}

/** Reads the _csrf, _csrf_header and _csrf_parameter meta tags of an HTML page. */
function parseCsrf(html) {
    const csrf = {};

    for (const tag of html.match(/<meta\b[^>]*>/gi) || []) {
        const name = tagAttribute(tag, 'name');
        const content = tagAttribute(tag, 'content');

        if (name === '_csrf') {
            csrf.token = content;
        } else if (name === '_csrf_header') {
            csrf.header = content;
        } else if (name === '_csrf_parameter') {
            csrf.parameter = content;
        }
    }

    if (!csrf.token || !csrf.header) {
        throw new Error('page has no _csrf and _csrf_header meta tags');
    }

    return csrf;
}

function formEncode(fields) {
    return Object.keys(fields).map((name) => `${encodeURIComponent(name)}=${encodeURIComponent(fields[name])}`)
        .join('&');
}

function boardUrl(scheduleId) {
    return `${BOARD_PATH}?lang=en&context=`
        + encodeURIComponent(JSON.stringify({ 'gantt.productionLineScheduleId': String(scheduleId) }));
}

/** Cookie-keeping HTTP client of one application session. */
class HttpSession {
    constructor(baseUrl) {
        this.baseUrl = baseUrl;
        this.cookies = new Map();
        this.csrf = null;
    }

    updateCookies(response) {
        for (const setCookie of response.headers.getSetCookie()) {
            const pair = setCookie.split(';')[0];
            const separator = pair.indexOf('=');

            if (separator <= 0) {
                continue;
            }

            const name = pair.slice(0, separator).trim();
            const value = pair.slice(separator + 1).trim();

            if (value === '' || /;\s*max-age=0\b/i.test(setCookie)) {
                this.cookies.delete(name);
            } else {
                this.cookies.set(name, value);
            }
        }
    }

    /**
     * Sends the request with the session cookies, takes the answer's cookies and returns {status, headers, text}.
     * Throws, naming the method and the path, when the whole answer has not arrived within HTTP_REQUEST_TIMEOUT_MS.
     */
    async request(pathAndQuery, { method = 'GET', headers = {}, body } = {}) {
        const requestHeaders = new Headers(headers);

        requestHeaders.set('Accept-Language', 'en');

        if (this.cookies.size > 0) {
            requestHeaders.set('Cookie', Array.from(this.cookies, ([name, value]) => `${name}=${value}`).join('; '));
        }

        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), HTTP_REQUEST_TIMEOUT_MS);

        try {
            const response = await fetch(this.baseUrl + pathAndQuery,
                { method, headers: requestHeaders, body, redirect: 'manual', signal: controller.signal });

            this.updateCookies(response);

            return { status: response.status, headers: response.headers, text: await response.text() };
        } catch (error) {
            if (controller.signal.aborted) {
                throw new Error(`${method} ${pathAndQuery} got no complete answer within ${HTTP_REQUEST_TIMEOUT_MS} ms`);
            }

            throw error;
        } finally {
            clearTimeout(timer);
        }
    }

    csrfHeaders() {
        if (!this.csrf) {
            throw new Error('no CSRF token: log in first');
        }

        return { [this.csrf.header]: this.csrf.token };
    }

    /**
     * Logs in through /j_spring_security_check with the CSRF token of the login page. Throws when the answer is not
     * loginSuccessfull, with its status, the user through diagnosticText and the answer through responseExcerpt.
     */
    async login(user, password) {
        const loginPage = await this.request('/login.html?lang=en');

        if (loginPage.status !== 200) {
            throw new Error(`GET /login.html answered ${loginPage.status}`);
        }

        this.csrf = parseCsrf(loginPage.text);

        const sessionBefore = this.cookies.get('JSESSIONID');
        const response = await this.request('/j_spring_security_check', {
            method: 'POST',
            headers: Object.assign({
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest'
            }, this.csrfHeaders()),
            body: formEncode({ j_username: user, j_password: password })
        });

        if (response.text.trim() !== 'loginSuccessfull') {
            throw new Error(`login as ${diagnosticText(user)} failed (${response.status}): `
                + responseExcerpt(response.text));
        }
        if (!this.cookies.has('JSESSIONID') || this.cookies.get('JSESSIONID') === sessionBefore) {
            throw new Error('the session id did not change at login');
        }
    }

    /**
     * Opens the board page of the schedule and takes the CSRF token of that page. Throws unless the answer is an HTTP
     * 200 text/html page, with its status, its content type through diagnosticText and its body through
     * responseExcerpt.
     */
    async openBoard(scheduleId) {
        const response = await this.request(boardUrl(scheduleId));
        const contentType = response.headers.get('content-type') || '';

        if (response.status !== 200 || !/text\/html/i.test(contentType)) {
            throw new Error(`GET ${BOARD_PATH} answered ${response.status} (${diagnosticText(contentType)}): `
                + responseExcerpt(response.text));
        }

        this.csrf = parseCsrf(response.text);
    }

    /**
     * Posts a view event body to the board and returns the parsed JSON answer. Throws, naming the event, when the
     * answer is a redirect to the login page (its Location through diagnosticText), not HTTP 200, sessionExpired, an
     * error page or not JSON (the parser message through diagnosticText). The messages for an answer that is not HTTP
     * 200, an error page or not JSON quote it through responseExcerpt.
     */
    async postEvent(body) {
        const response = await this.request(BOARD_PATH, {
            method: 'POST',
            headers: Object.assign({
                'Content-Type': 'application/json; charset=utf-8',
                'X-Requested-With': 'XMLHttpRequest'
            }, this.csrfHeaders()),
            body: JSON.stringify(body)
        });
        const text = response.text.trim();
        const location = response.headers.get('location') || '';

        if (response.status >= 300 && response.status < 400 && /login/i.test(location)) {
            throw new Error(`event ${body.event.name} was redirected to the login page: ${diagnosticText(location)}`);
        }
        if (response.status !== 200) {
            throw new Error(`event ${body.event.name} answered ${response.status}: ${responseExcerpt(text)}`);
        }
        if (text === 'sessionExpired') {
            throw new Error(`event ${body.event.name} answered sessionExpired`);
        }
        if (text.startsWith('<![CDATA[ERROR PAGE:')) {
            throw new Error(`event ${body.event.name} answered an error page: ${responseExcerpt(text)}`);
        }

        try {
            return JSON.parse(text);
        } catch (error) {
            throw new Error(`event ${body.event.name} answered no JSON (${diagnosticText(error.message)}): `
                + responseExcerpt(text));
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// View value tree
// ---------------------------------------------------------------------------------------------------------------

// Initialize request body captured from the browser in the before hook, and the gantt component path derived from it.
let TEMPLATE = null;
let GANTT_PATH = null;

function clone(value) {
    return JSON.parse(JSON.stringify(value));
}

/** Returns the dot-joined path to the first node, depth first over `components` maps, whose content has headerParameters. */
function findGanttPath(components, prefix = []) {
    for (const name of Object.keys(components || {})) {
        const node = components[name];

        if (!node || typeof node !== 'object') {
            continue;
        }
        if (node.content && typeof node.content === 'object' && node.content.headerParameters) {
            return prefix.concat(name).join('.');
        }

        const found = findGanttPath(node.components, prefix.concat(name));

        if (found) {
            return found;
        }
    }

    return null;
}

/** Returns the node of a `components` tree at the dot-joined path. */
function ganttNode(components, componentPath) {
    const names = componentPath.split('.');
    let node = components[names[0]];

    for (let index = 1; node && index < names.length; index++) {
        node = node.components ? node.components[names[index]] : undefined;
    }
    if (!node) {
        throw new Error(`no component ${componentPath} in the value tree`);
    }

    return node;
}

/** Returns {path, content} of the node of a response whose content has rows or moveResult, or null. */
function findGanttContent(response) {
    function walk(components, prefix) {
        for (const name of Object.keys(components || {})) {
            const node = components[name];

            if (!node || typeof node !== 'object') {
                continue;
            }

            const content = node.content;

            if (content && typeof content === 'object' && ('rows' in content || 'moveResult' in content)) {
                return { path: prefix.concat(name).join('.'), content };
            }

            const found = walk(node.components, prefix.concat(name));

            if (found) {
                return found;
            }
        }

        return null;
    }

    return walk(response && response.components, []);
}

/**
 * Builds a view event body from the captured template: the gantt context names the schedule, the gantt content is
 * {headerParameters} when given, and every event except initialize targets the gantt component.
 */
function buildBody(name, scheduleId, headerParameters, args) {
    if (!TEMPLATE || !GANTT_PATH) {
        throw new Error('no captured request template');
    }

    const components = clone(TEMPLATE.components);
    const node = ganttNode(components, GANTT_PATH);

    node.context = { productionLineScheduleId: String(scheduleId) };

    if (headerParameters) {
        node.content = { headerParameters: clone(headerParameters) };
    }

    const event = { name };

    if (name !== 'initialize') {
        event.component = GANTT_PATH;
    }
    if (args) {
        event.args = args;
    }

    return { event, components };
}

/** Opens the board of the schedule, posts initialize and returns the gantt content with its headerParameters. */
async function initialize(session, scheduleId) {
    await session.openBoard(scheduleId);

    const response = await session.postEvent(buildBody('initialize', scheduleId));
    const found = findGanttContent(response);

    assert.ok(found, 'the initialize answer has a gantt content');
    assert.equal(found.path, GANTT_PATH, 'the initialize answer renders the gantt at the captured path');
    assert.ok(Array.isArray(found.content.rows), 'the initialize answer has rows');
    assert.ok(Array.isArray(found.content.items), 'the initialize answer has items');

    return {
        content: found.content,
        headerParameters: {
            scale: found.content.zoomLevel,
            dateFrom: found.content.dateFrom,
            dateTo: found.content.dateTo
        }
    };
}

/** Returns the moveItem event arguments that drop the rendered item on row at dateFrom. */
function moveArgs(item, row, dateFrom) {
    return [JSON.stringify({
        itemId: item.id,
        row,
        dateFrom,
        originalRow: item.row,
        originalName: item.info.name,
        originalDateFrom: item.info.dateFrom,
        originalDateTo: item.info.dateTo
    })];
}

/** Posts moveItem for the rendered item and returns the gantt content of the answer. */
async function postMove(session, scheduleId, headerParameters, item, row, dateFrom) {
    const response = await session.postEvent(buildBody('moveItem', scheduleId, headerParameters,
        moveArgs(item, row, dateFrom)));
    const found = findGanttContent(response);

    assert.ok(found, `the moveItem answer for ${row} ${dateFrom} has a gantt content`);
    assert.equal(found.path, GANTT_PATH);
    assert.ok(found.content.moveResult, `the moveItem answer for ${row} ${dateFrom} has a moveResult`);

    return found.content;
}

/** Returns the single rendered item with the entity id. */
function itemById(content, id) {
    const matches = content.items.filter((item) => item.id === id);

    assert.equal(matches.length, 1, `exactly one item has id ${id}`);

    return matches[0];
}

// ---------------------------------------------------------------------------------------------------------------
// Fixture
// ---------------------------------------------------------------------------------------------------------------

// Fixture ids loaded in the before hook.
let FIXTURE = null;

async function loadFixture() {
    const data = await psqlJson(`
SELECT json_build_object(
    'schedules', (SELECT coalesce(json_agg(json_build_object('number', s.number, 'id', s.id) ORDER BY s.id), '[]')
                  FROM public.orders_productionlineschedule s
                  WHERE s.number LIKE 'PMG-%'),
    'positions', (SELECT coalesce(json_agg(json_build_object(
                             'scheduleNumber', s.number,
                             'orderNumber', o.number,
                             'role', substring(o.number FROM '[^-]+$'),
                             'positionId', p.id,
                             'orderId', o.id,
                             'line', pl.number,
                             'start', to_char(p.starttime, 'YYYY-MM-DD HH24:MI:SS'),
                             'end', to_char(p.endtime, 'YYYY-MM-DD HH24:MI:SS'),
                             'norm', n.number,
                             'additionaltime', p.additionaltime) ORDER BY p.id), '[]')
                  FROM public.orders_productionlinescheduleposition p
                  JOIN public.orders_productionlineschedule s ON s.id = p.productionlineschedule_id
                  JOIN public.orders_order o ON o.id = p.order_id
                  LEFT JOIN public.productionlines_productionline pl ON pl.id = p.productionline_id
                  LEFT JOIN public.linechangeovernorms_linechangeovernorms n ON n.id = p.linechangeovernorm_id
                  WHERE s.number LIKE 'PMG-%'),
    'spares', (SELECT coalesce(json_object_agg(o.number, o.id), '{}')
               FROM public.orders_order o
               WHERE o.number LIKE 'PMG-%-SPARE'),
    'norms', (SELECT coalesce(json_object_agg(n.number, n.id), '{}')
              FROM public.linechangeovernorms_linechangeovernorms n
              WHERE n.number LIKE 'PMG-N-%'),
    'lines', (SELECT coalesce(json_object_agg(pl.number, pl.id), '{}')
              FROM public.productionlines_productionline pl
              WHERE pl.number IN ('PMG-A', 'PMG-B')),
    'events', (SELECT coalesce(json_object_agg(e.number, e.id), '{}')
               FROM public.cmmsmachineparts_plannedevent e
               WHERE e.number LIKE 'PMG-EV-%'));
`);

    const cases = {};

    for (const schedule of data.schedules) {
        const caseName = schedule.number.slice('PMG-'.length);

        cases[caseName] = {
            scheduleId: schedule.id,
            roles: {},
            spareOrderId: data.spares[`${schedule.number}-SPARE`]
        };
    }
    for (const position of data.positions) {
        const entry = cases[position.scheduleNumber.slice('PMG-'.length)];

        entry.roles[position.role] = {
            positionId: position.positionId,
            orderId: position.orderId,
            orderNumber: position.orderNumber,
            line: position.line,
            start: position.start,
            end: position.end,
            norm: position.norm,
            additionaltime: position.additionaltime
        };
    }
    for (const caseName of Object.keys(cases)) {
        assert.deepEqual(Object.keys(cases[caseName].roles).sort(), POSITION_ROLES,
            `schedule PMG-${caseName} has one position per role`);
        assert.ok(cases[caseName].spareOrderId, `schedule PMG-${caseName} has a SPARE order`);
    }
    for (const number of ['PMG-N-A-BB', 'PMG-N-A-BA', 'PMG-N-A-AB', 'PMG-N-B-BB', 'PMG-N-B-ZB', 'PMG-N-B-BZ']) {
        assert.ok(data.norms[number], `changeover norm ${number} exists`);
    }
    for (const number of ['PMG-A', 'PMG-B']) {
        assert.ok(data.lines[number], `production line ${number} exists`);
    }
    for (const number of ['PMG-EV-SHUTDOWN', 'PMG-EV-DIVISION']) {
        assert.ok(data.events[number], `planned event ${number} exists`);
    }

    return { cases, norms: data.norms, lines: data.lines, events: data.events };
}

/** Returns {scheduleId, roles, spareOrderId} of schedule PMG-<caseName>. */
function fx(caseName) {
    const entry = FIXTURE && FIXTURE.cases[caseName];

    if (!entry) {
        throw new Error(`fixture schedule PMG-${caseName} not found`);
    }

    return entry;
}

/**
 * Returns the stored positions of the case's schedule keyed by fixture role, each as
 * {line, start, end, norm, additionaltime, orderNumber}. A position that is not a fixture position is keyed #<id>.
 */
async function positionState(caseName) {
    const { scheduleId, roles } = fx(caseName);
    const rows = await psqlJson(`
SELECT coalesce(json_agg(json_build_object(
           'id', p.id,
           'line', pl.number,
           'start', to_char(p.starttime, 'YYYY-MM-DD HH24:MI:SS'),
           'end', to_char(p.endtime, 'YYYY-MM-DD HH24:MI:SS'),
           'norm', n.number,
           'additionaltime', p.additionaltime,
           'orderNumber', o.number) ORDER BY p.id), '[]')
FROM public.orders_productionlinescheduleposition p
LEFT JOIN public.orders_order o ON o.id = p.order_id
LEFT JOIN public.productionlines_productionline pl ON pl.id = p.productionline_id
LEFT JOIN public.linechangeovernorms_linechangeovernorms n ON n.id = p.linechangeovernorm_id
WHERE p.productionlineschedule_id = ${Number(scheduleId)};
`);
    const roleById = {};

    for (const role of Object.keys(roles)) {
        roleById[roles[role].positionId] = role;
    }

    const state = {};

    for (const row of rows) {
        const key = roleById[row.id] || `#${row.id}`;

        state[key] = {
            line: row.line,
            start: row.start,
            end: row.end,
            norm: row.norm,
            additionaltime: row.additionaltime,
            orderNumber: row.orderNumber
        };
    }

    return state;
}

/**
 * Asserts the stored positions: '=' keeps the pre-move state, [line, start, end, norm] sets those fields and keeps the
 * others. Every position of `before` needs an expectation.
 */
function assertPositions(actual, beforeMove, expected) {
    const wanted = {};

    for (const key of Object.keys(beforeMove)) {
        const expectation = expected[key];

        if (expectation === undefined) {
            throw new Error(`no expectation for position ${key}`);
        }

        wanted[key] = expectation === '='
            ? clone(beforeMove[key])
            : Object.assign(clone(beforeMove[key]),
                { line: expectation[0], start: expectation[1], end: expectation[2], norm: expectation[3] });
    }

    assert.deepStrictEqual(actual, wanted);
}

// ---------------------------------------------------------------------------------------------------------------
// Checksums
// ---------------------------------------------------------------------------------------------------------------

function checksumExpression(source) {
    return `(SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM ${source})`;
}

/** Returns the SELECT of the md5 checksums of the schedule's positions, the planned events and the PS/PPS tables. */
function checksumSelect(scheduleId) {
    const entries = [
        `'positions', ${checksumExpression(`public.orders_productionlinescheduleposition t
                                            WHERE t.productionlineschedule_id = ${Number(scheduleId)}`)}`,
        `'plannedEvents', ${checksumExpression(`(SELECT id, startdate, finishdate, state, entityversion
                                                 FROM public.cmmsmachineparts_plannedevent) t`)}`
    ];

    for (const name of Object.keys(PS_PPS_TABLES)) {
        entries.push(`'${name}', ${checksumExpression(`${PS_PPS_TABLES[name]} t`)}`);
    }

    return `SELECT json_build_object(\n    ${entries.join(',\n    ')});`;
}

async function checksums(scheduleId) {
    return psqlJson(checksumSelect(scheduleId), { app: 'checksum' });
}

/** Returns the checksums the database would have after writeSql, computed in a transaction that is rolled back. */
async function precomputeChecksums(scheduleId, writeSql) {
    return psqlJson(`BEGIN;\n${writeSql};\n${checksumSelect(scheduleId)}\nROLLBACK;\n`, { app: 'precompute' });
}

function psPpsChecksums(all) {
    const selected = {};

    for (const name of Object.keys(PS_PPS_TABLES)) {
        selected[name] = all[name];
    }

    return selected;
}

// ---------------------------------------------------------------------------------------------------------------
// Concurrent transactions
// ---------------------------------------------------------------------------------------------------------------

// Standard output line of a held transaction reporting its backend pid once its SQL has run.
const HELD_READY_PATTERN = /^pmg-ready:(\d+)$/;

// Standard output line of a held transaction reporting the end of its pg_sleep hold.
const HELD_HOLD_LINE = 'pmg-held';

// Backend pids of the HeldTransaction sessions that have reported their ready line and have not closed.
const HELD_BACKEND_PIDS = new Set();

// Time a held transaction has to report its SQL as run, and to close after COMMIT or ROLLBACK, in ms.
const HELD_ANSWER_TIMEOUT_MS = 30000;

// Seconds the background transaction sleeps inside its transaction once its marker exists.
const BACKGROUND_HOLD_SECONDS = 3;

// Time a settled request or transaction is awaited during the cleanup of a failed concurrent case, in ms.
const CLEANUP_SETTLE_TIMEOUT_MS = 60000;

// Time the answers of the posted moves are awaited once the concurrent transaction has ended, in ms.
const MOVE_ANSWER_TIMEOUT_MS = 60000;

/**
 * Returns {status: 'fulfilled', value}, {status: 'rejected', reason}, or {status: 'pending'} when the promise has not
 * settled within timeout ms. Clears its timer on return.
 */
async function settleWithin(promise, timeout) {
    let timer = null;
    const expiry = new Promise((resolve) => {
        timer = setTimeout(() => resolve({ status: 'pending' }), timeout);
    });

    try {
        return await Promise.race([
            Promise.resolve(promise).then((value) => ({ status: 'fulfilled', value }), (reason) => ({ status: 'rejected', reason })),
            expiry
        ]);
    } finally {
        clearTimeout(timer);
    }
}

/** Returns the value of the promise. Throws its rejection, or a timeout error naming description after timeout ms. */
async function valueWithin(promise, timeout, description) {
    const outcome = await settleWithin(promise, timeout);

    if (outcome.status === 'fulfilled') {
        return outcome.value;
    }
    if (outcome.status === 'rejected') {
        throw outcome.reason;
    }

    throw new Error(`timed out after ${timeout} ms waiting for ${description}`);
}

/** Returns {promise, resolve, reject, settled}; resolve and reject act once, and a rejection nobody awaits is handled. */
function deferred() {
    const result = { promise: null, resolve: null, reject: null, settled: false };

    result.promise = new Promise((resolve, reject) => {
        result.resolve = (value) => {
            if (!result.settled) {
                result.settled = true;
                resolve(value);
            }
        };
        result.reject = (error) => {
            if (!result.settled) {
                result.settled = true;
                reject(error);
            }
        };
    });
    result.promise.catch(() => undefined);

    return result;
}

/**
 * Returns {promise, settled, outcome, describe()} for a started request: settled turns true and outcome holds
 * {status, value | reason} once the promise settles; describe() prints the outcome, or 'pending'.
 */
function trackSettlement(promise) {
    const tracked = {
        promise,
        settled: false,
        outcome: null,
        describe() {
            if (!tracked.outcome) {
                return 'pending';
            }

            return tracked.outcome.status === 'fulfilled'
                ? `answered ${JSON.stringify(tracked.outcome.value && tracked.outcome.value.moveResult)}`
                : `failed: ${tracked.outcome.reason && tracked.outcome.reason.message}`;
        }
    };

    promise.then((value) => {
        tracked.settled = true;
        tracked.outcome = { status: 'fulfilled', value };
    }, (reason) => {
        tracked.settled = true;
        tracked.outcome = { status: 'rejected', reason };
    });

    return tracked;
}

/**
 * Waits up to timeout ms until every tracked move has settled and returns their answers in order. Throws when a move
 * failed or has not settled in time; the error names the outcome of every move, and its cause is the first failure.
 */
async function moveAnswers(moves, timeout) {
    const settled = await settleWithin(Promise.allSettled(moves.map((move) => move.promise)), timeout);
    const outcomes = () => moves.map((move, index) => `move M${index + 1}: ${move.describe()}`).join('\n');

    if (settled.status === 'pending') {
        throw new Error(`the moves did not all answer within ${timeout} ms\n${outcomes()}`);
    }

    const failure = settled.value.find((result) => result.status === 'rejected');

    if (failure) {
        throw new Error(`a move failed: ${failure.reason && failure.reason.message}\n${outcomes()}`,
            { cause: failure.reason });
    }

    return settled.value.map((result) => result.value);
}

/**
 * psql session (application pmg-acceptance-<app>, deadline PSQL_HELD_TIMEOUT_MS) that runs BEGIN, the SQL and
 * SELECT 'pmg-ready:' || pg_backend_pid(), then keeps its transaction open with standard input open until commit() or
 * rollback(). Its standard output is read line by line. Once the ready line arrives, the marker file
 * <temporary directory pmg-acceptance-<app>-*>/<markerName> is created with the flag 'wx' and checked to be a file;
 * when that fails, the transaction is rolled back and open() rejects.
 *
 *   const holder = await HeldTransaction.open('holder', 'SELECT id FROM t WHERE id = 1 FOR UPDATE', 'locked');
 *   // holder.pid is the backend pid, holder.markerPath the created marker file
 *   await holder.commit();
 */
class HeldTransaction {
    /** Opens the transaction and resolves with it once the marker file exists. */
    static async open(app, sql, markerName) {
        const transaction = new HeldTransaction(app, sql, markerName);

        await transaction.start();

        return transaction;
    }

    constructor(app, sql, markerName) {
        this.app = app;
        this.sql = sql;
        this.context = `held transaction psql (${app})`;
        this.markerPath = path.join(makeTempDir(app), markerName);
        this.pid = null;
        this.ready = deferred();
        this.held = deferred();
        this.holdRequested = false;
        this.ending = null;
        this.session = spawnPsql(app, {
            operation: `held transaction: ${sqlLabel(sql)}`,
            timeout: PSQL_HELD_TIMEOUT_MS,
            onLine: (line) => this.onLine(line)
        });
        this.closed = this.session.done.then(() => null, (error) => error);
        this.closed.then((error) => {
            const ended = new Error(`${this.context} ended${error ? `: ${error.message}` : ''}`);

            if (this.pid !== null) {
                HELD_BACKEND_PIDS.delete(this.pid);
            }

            this.ready.reject(ended);
            this.held.reject(ended);
        });
    }

    /** Tells whether the psql session has closed. */
    get ended() {
        return this.session.isClosed();
    }

    /**
     * Handles a standard output line: the ready line sets pid, adds it to HELD_BACKEND_PIDS until the session closes
     * and resolves ready; the hold line resolves held.
     */
    onLine(line) {
        const ready = HELD_READY_PATTERN.exec(line);

        if (ready) {
            this.pid = Number(ready[1]);
            HELD_BACKEND_PIDS.add(this.pid);
            this.ready.resolve(this.pid);
        } else if (line === HELD_HOLD_LINE) {
            this.held.resolve();
        }
    }

    /**
     * Sends BEGIN, the SQL and the ready query, waits HELD_ANSWER_TIMEOUT_MS for the ready line, then creates the
     * marker file. Rejects, after ending the session, when psql closes first, the ready line does not arrive, or the
     * marker cannot be created.
     */
    async start() {
        this.session.write(`BEGIN;\n${this.sql};\nSELECT 'pmg-ready:' || pg_backend_pid();\n`);

        try {
            this.pid = await valueWithin(this.ready.promise, HELD_ANSWER_TIMEOUT_MS, `${this.context} to run its SQL`);
        } catch (error) {
            await this.abandon(error);

            throw new Error(`${this.context} did not report its SQL as run: ${error.message}\n--- SQL ---\n${this.sql}`);
        }

        try {
            fs.writeFileSync(this.markerPath, `${this.pid}\n`, { flag: 'wx' });

            if (!fs.statSync(this.markerPath).isFile()) {
                throw new Error(`${this.markerPath} is not a regular file`);
            }
        } catch (error) {
            const rollback = await settleWithin(this.rollback(), HELD_ANSWER_TIMEOUT_MS + 2 * PSQL_KILL_GRACE_MS);
            const outcome = rollback.status === 'fulfilled'
                ? 'the transaction was rolled back'
                : `the rollback failed: ${rollback.status === 'rejected' ? rollback.reason.message : 'no answer'}`;

            throw new Error(`${this.context} could not create its marker ${this.markerPath} (${error.message}); ${outcome}`);
        }
    }

    /** Terminates a session that is still open and waits up to 2 * PSQL_KILL_GRACE_MS for it to close. */
    async abandon(reason) {
        if (!this.session.isClosed()) {
            this.session.terminate(new Error(`${this.context} abandoned: ${reason.message}`));
            await settleWithin(this.session.done, 2 * PSQL_KILL_GRACE_MS);
        }
    }

    /**
     * Sends SELECT 'pmg-held' FROM pg_sleep(seconds) once and returns the promise that resolves on its 'pmg-held' line
     * and rejects when the session ends first.
     */
    hold(seconds) {
        if (!this.holdRequested) {
            this.holdRequested = true;

            if (!this.session.write(`SELECT '${HELD_HOLD_LINE}' FROM pg_sleep(${Number(seconds)});\n`)) {
                this.held.reject(new Error(`${this.context} ended before its hold`));
            }
        }

        return this.held.promise;
    }

    /** Sends COMMIT, ends standard input and resolves once psql exited 0. Repeated calls return the same promise. */
    commit() {
        return this.finish('COMMIT');
    }

    /**
     * Sends ROLLBACK, ends standard input and resolves once psql exited 0, or at once when the session has already
     * closed without COMMIT. Repeated calls return the same promise.
     */
    rollback() {
        return this.finish('ROLLBACK');
    }

    finish(statement) {
        if (this.ending) {
            return this.ending.statement === statement
                ? this.ending.promise
                : Promise.reject(new Error(`${this.context} already ended with ${this.ending.statement}`));
        }

        this.ending = { statement, promise: this.end(statement) };
        this.ending.promise.catch(() => undefined);

        return this.ending.promise;
    }

    /**
     * Ends the session with the statement. Waits HELD_ANSWER_TIMEOUT_MS for psql to close; past that, terminates it,
     * waits up to 2 * PSQL_KILL_GRACE_MS and rejects. When the statement cannot be sent, terminates a session that is
     * still open; ROLLBACK then resolves and COMMIT rejects.
     */
    async end(statement) {
        if (!this.session.end(`${statement};\n`)) {
            await this.abandon(new Error(`${statement} could not be sent`));

            const closed = await settleWithin(this.closed, 2 * PSQL_KILL_GRACE_MS);

            if (statement === 'ROLLBACK') {
                return;
            }

            const cause = closed.status === 'fulfilled' && closed.value ? `: ${closed.value.message}` : '';

            throw new Error(`${this.context} closed before ${statement}${cause}`);
        }

        const outcome = await settleWithin(this.session.done, HELD_ANSWER_TIMEOUT_MS);

        if (outcome.status === 'fulfilled') {
            return;
        }
        if (outcome.status === 'rejected') {
            throw new Error(`${this.context} failed at ${statement}: ${outcome.reason.message}`);
        }

        const timeout = new Error(`${this.context} did not close within ${HELD_ANSWER_TIMEOUT_MS} ms of ${statement}`);

        this.session.terminate(timeout);

        const killed = await settleWithin(this.session.done, 2 * PSQL_KILL_GRACE_MS);

        throw new Error(killed.status === 'rejected' ? killed.reason.message
            : `${timeout.message}; it did not close after SIGTERM and SIGKILL`);
    }
}

/**
 * Opens a HeldTransaction (application pmg-acceptance-bg, marker file 'written') that writes writeSql, and starts its
 * BACKGROUND_HOLD_SECONDS s sleep inside the transaction once the marker exists. Resolves with {transaction, held};
 * held resolves when the sleep has ended. The transaction stays open until commit() or rollback().
 */
async function backgroundWrite(writeSql) {
    const transaction = await HeldTransaction.open('bg', writeSql, 'written');

    return { transaction, held: transaction.hold(BACKGROUND_HOLD_SECONDS) };
}

/**
 * Returns the lock-wait tree rooted at the backend rootPid: the client backends of the acceptance database whose
 * pg_blocking_pids holds rootPid, then, repeatedly, those whose pg_blocking_pids holds a backend already in the tree.
 * The suite's own sessions are left out by pid: rootPid, the backend that runs the query and the backends of
 * HELD_BACKEND_PIDS. Each backend appears once, ordered by pid, as {pid, application, waitEventType, waitEvent,
 * blockedBy}.
 */
async function lockWaitTree(rootPid) {
    const root = Number(rootPid);

    if (!Number.isInteger(root) || root <= 0) {
        throw new Error(`not a backend pid: ${rootPid}`);
    }

    const excluded = Array.from(new Set([root].concat(Array.from(HELD_BACKEND_PIDS)
        .filter((pid) => Number.isInteger(pid) && pid > 0))));

    return psqlJson(`
WITH RECURSIVE backends AS (
    SELECT pid, application_name, wait_event_type, wait_event, pg_blocking_pids(pid) AS blocked_by
    FROM pg_stat_activity
    WHERE datname = current_database()
      AND backend_type = 'client backend'
      AND pid <> pg_backend_pid()
      AND pid <> ALL (ARRAY[${excluded.join(', ')}]::integer[])
), tree AS (
    SELECT pid FROM backends WHERE ${root} = ANY (blocked_by)
    UNION
    SELECT b.pid FROM backends b JOIN tree ON tree.pid = ANY (b.blocked_by)
)
SELECT coalesce(json_agg(json_build_object(
           'pid', b.pid, 'application', b.application_name, 'waitEventType', b.wait_event_type,
           'waitEvent', b.wait_event, 'blockedBy', to_json(b.blocked_by)) ORDER BY b.pid), '[]')
FROM backends b
WHERE b.pid IN (SELECT pid FROM tree);
`, { app: 'monitor', timeout: PSQL_PROBE_TIMEOUT_MS });
}

/**
 * Returns pid, application name, state, wait event type, wait event and blocking pids of every backend of the
 * acceptance database. The rows hold no query text.
 */
async function activityDump() {
    return psqlJson(`
SELECT coalesce(json_agg(json_build_object(
           'pid', pid, 'application', application_name, 'state', state, 'waitEventType', wait_event_type,
           'waitEvent', wait_event, 'blockedBy', to_json(pg_blocking_pids(pid))) ORDER BY pid),
       '[]')
FROM pg_stat_activity
WHERE datname = current_database();
`, { app: 'monitor', timeout: PSQL_PROBE_TIMEOUT_MS });
}

/**
 * Cleans up after a failed concurrent case and returns the error to throw. Reads pg_stat_activity, rolls back every
 * transaction that has not been ended, and waits up to CLEANUP_SETTLE_TIMEOUT_MS for the tracked requests. The returned
 * error holds the message of error, each cleanup failure, the outcome of each request and the pg_stat_activity rows of
 * activityDump, without query text, or the error of reading them; its cause is error.
 */
async function concurrentFailure(error, transactions, requests) {
    const notes = [];
    let activity;

    try {
        activity = JSON.stringify(await activityDump(), null, 2);
    } catch (dumpError) {
        activity = `unavailable (${dumpError.message})`;
    }

    for (const transaction of transactions) {
        if (transaction.ending) {
            continue;
        }

        const rollback = await settleWithin(transaction.rollback(), CLEANUP_SETTLE_TIMEOUT_MS);

        if (rollback.status !== 'fulfilled') {
            notes.push(`rollback of the ${transaction.context}: `
                + (rollback.status === 'rejected' ? rollback.reason.message : `no answer within ${CLEANUP_SETTLE_TIMEOUT_MS} ms`));
        }
    }

    const settled = await settleWithin(Promise.allSettled(requests.map((request) => request.promise)),
        CLEANUP_SETTLE_TIMEOUT_MS);

    if (settled.status === 'pending') {
        notes.push(`requests still pending after ${CLEANUP_SETTLE_TIMEOUT_MS} ms`);
    }

    requests.forEach((request, index) => notes.push(`move M${index + 1}: ${request.describe()}`));

    return new Error(`${error.message}\n${notes.join('\n')}\npg_stat_activity at the failure: ${activity}`, { cause: error });
}

// ---------------------------------------------------------------------------------------------------------------
// DevTools Protocol client
// ---------------------------------------------------------------------------------------------------------------

/**
 * Returns the normalised DevTools endpoint printed by Chrome when it is a ws: URL of a loopback host (127.0.0.1,
 * localhost or [::1]) with no user name or password, an explicit port 1-65535, and a path under /devtools/browser/.
 * Throws, naming the endpoint and the unmet condition, each through diagnosticText, otherwise.
 *
 * The port is read as written in the text: the authority is the text between a leading ws:// (in any letter case) and
 * the first /, \, ? or #, and the port is the run of digits after the : that ends it, looking only at the part after
 * its last @ and its last ]. A written port 1-65535 is accepted with or without leading zeros, the default ws: port 80
 * included, which the URL parser reports as no port; an authority without a written port, such as ws://[::1]/... or
 * ws://localhost:/..., is refused. The user name and password check comes before the port check.
 *
 * Example: devToolsEndpoint('ws://127.0.0.1:41235/devtools/browser/0f3a') returns that URL;
 * devToolsEndpoint('ws://localhost:80/devtools/browser/x') returns 'ws://localhost/devtools/browser/x';
 * devToolsEndpoint('ws://[::1]/devtools/browser/x') throws "... it names no port";
 * devToolsEndpoint('ws://10.1.2.3:9222/devtools/browser/x') throws "... host 10.1.2.3 is not a loopback host".
 */
function devToolsEndpoint(text) {
    const refusal = (reason) => new Error(`refusing to connect to the DevTools endpoint ${diagnosticText(text)}: `
        + diagnosticText(reason));
    let url;

    try {
        url = new URL(text);
    } catch (error) {
        throw refusal(`it is not a URL (${error.message})`);
    }

    if (url.protocol !== 'ws:') {
        throw refusal(`protocol ${url.protocol} is not ws:`);
    }
    if (!LOOPBACK_HOSTS.has(url.hostname)) {
        throw refusal(`host ${url.hostname} is not a loopback host`);
    }
    if (url.username !== '' || url.password !== '') {
        throw refusal('it carries a user name or password');
    }

    const authority = /^ws:\/\/([^/\\?#]*)/i.exec(text);
    const hostAndPort = authority === null ? '' : authority[1].slice(authority[1].lastIndexOf('@') + 1);
    const writtenPort = /:(\d+)$/.exec(hostAndPort.slice(hostAndPort.lastIndexOf(']') + 1));

    if (writtenPort === null) {
        throw refusal('it names no port');
    }
    if (Number(writtenPort[1]) < 1 || Number(writtenPort[1]) > 65535) {
        throw refusal(`port ${writtenPort[1]} is not a port 1-65535`);
    }
    if (!url.pathname.startsWith('/devtools/browser/')) {
        throw refusal(`path ${url.pathname} is not under /devtools/browser/`);
    }

    return url.href;
}

/** Headless Chrome with one page target, driven over the DevTools Protocol with flattened sessions. */
class DevTools {
    constructor() {
        this.process = null;
        this.socket = null;
        this.sessionId = null;
        this.nextId = 1;
        this.pending = new Map();
        this.listeners = new Map();
        this.stderrTail = '';
        this.exited = false;
        this.processError = null;
        this.devToolsWatch = null;
        this.isolationError = null;
    }

    /**
     * Starts Chrome, connects to its DevTools endpoint, has the isolation watch check its DevTools port
     * (watchDevToolsPort) and attaches to a new page with Page, Runtime and Network enabled. Rejects without starting
     * Chrome when the isolation watch has stopped the run or assertIsolatedWorker finds an intrusion, and once Chrome
     * runs when the isolation watch has stopped it. Chrome's standard error is quoted through diagnosticText.
     */
    async launch(chromePath) {
        const profileDir = makeTempDir('chrome-profile');
        const chromeArgs = ['--headless', '--remote-debugging-port=0', `--user-data-dir=${profileDir}`, '--no-first-run',
            '--no-default-browser-check', '--lang=en-US', '--window-size=1600,1000', '--disable-dev-shm-usage'];

        if (process.getuid && process.getuid() === 0) {
            chromeArgs.push('--no-sandbox');
        }

        chromeArgs.push('about:blank');

        if (ISOLATION_ERROR !== null) {
            throw ISOLATION_ERROR;
        }

        assertIsolatedWorker('before it starts Chrome');

        try {
            this.process = childProcess.spawn(chromePath, chromeArgs, { stdio: ['ignore', 'ignore', 'pipe'] });
        } catch (error) {
            this.exited = true;
            throw new Error(`Chrome ${chromePath} could not start: ${error.message}`);
        }

        const chrome = this.process;

        const endpoint = await new Promise((resolve, reject) => {
            // Chrome's standard error up to the DevTools endpoint line, as a rolling tail of 8000 characters.
            let startupStderr = '';
            let settled = false;
            let timer = null;

            const settle = (error, value) => {
                if (settled) {
                    return;
                }

                settled = true;
                clearTimeout(timer);

                if (error) {
                    reject(error);
                } else {
                    resolve(value);
                }
            };

            chrome.on('error', (error) => {
                const started = chrome.pid !== undefined;

                if (!started) {
                    this.exited = true;
                }

                this.stderrTail = `${this.stderrTail}\nprocess error: ${error.message}`.slice(-4000);
                settle(new Error(`Chrome ${chromePath} ${started ? 'failed' : 'could not start'}: ${error.message}`), null);
            });
            chrome.on('exit', (code, signal) => {
                this.exited = true;
                settle(new Error(`Chrome exited with ${code === null ? signal : code} before listening: `
                    + diagnosticText(startupStderr)), null);
            });

            if (!chrome.stderr) {
                settle(new Error(`Chrome ${chromePath} started without a standard error pipe`), null);

                return;
            }

            chrome.stderr.on('error', (error) => {
                this.stderrTail = `${this.stderrTail}\nstandard error failed: ${error.message}`.slice(-4000);
                settle(new Error(`Chrome standard error failed before listening: ${error.message}`), null);
            });

            timer = setTimeout(() => settle(new Error('Chrome printed no DevTools endpoint within 30 s: '
                + diagnosticText(startupStderr)), null), 30000);

            chrome.stderr.setEncoding('utf8');
            chrome.stderr.on('data', (chunk) => {
                this.stderrTail = (this.stderrTail + chunk).slice(-4000);

                if (settled) {
                    return;
                }

                startupStderr = (startupStderr + chunk).slice(-8000);

                // The endpoint counts once its line has ended.
                const match = /DevTools listening on (ws:\/\/\S+)\r?\n/.exec(startupStderr);

                if (match) {
                    settle(null, match[1]);
                }
            });
        });

        this.process.on('error', (error) => {
            this.processError = error;
        });
        this.process.on('close', () => {
            this.exited = true;
        });

        const loopbackEndpoint = devToolsEndpoint(endpoint);

        await this.connect(loopbackEndpoint);

        this.socket.addEventListener('message', (event) => this.onFrame(event.data));
        this.socket.addEventListener('close', (event) => this.rejectPending((pending) => `DevTools socket closed `
            + `(code ${event.code}) during ${pending.method} (${pending.target})`));

        this.watchDevToolsPort(Number(new URL(loopbackEndpoint).port || 80));

        const { targetId } = await this.send('Target.createTarget', { url: 'about:blank' }, null);
        const { sessionId } = await this.send('Target.attachToTarget', { targetId, flatten: true }, null);

        this.sessionId = sessionId;

        await this.command('Page.enable');
        await this.command('Runtime.enable');
        await this.command('Network.enable');
        await this.command('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
    }

    /**
     * Opens the DevTools socket to the endpoint and settles once: resolves on open; rejects on a socket error, on a
     * close before open (with its code and reason), when Chrome has exited or exits, or when the socket has not opened
     * within DEVTOOLS_CONNECT_TIMEOUT_MS. The socket error, the close reason and Chrome's standard error tail are
     * quoted through diagnosticText. On rejection the socket is closed and every listener and timer is removed.
     */
    connect(endpoint) {
        const socket = new WebSocket(endpoint);
        const chrome = this.process;

        this.socket = socket;

        return new Promise((resolve, reject) => {
            let settled = false;
            let timer = null;
            let onOpen = null;
            let onError = null;
            let onClose = null;
            let onExit = null;

            const settle = (error) => {
                if (settled) {
                    return;
                }

                settled = true;
                clearTimeout(timer);
                socket.removeEventListener('open', onOpen);
                socket.removeEventListener('error', onError);
                socket.removeEventListener('close', onClose);

                if (chrome) {
                    chrome.removeListener('exit', onExit);
                }
                if (error === null) {
                    resolve();
                    return;
                }

                try {
                    socket.close();
                } catch (closeError) {
                    error.message += `; closing the socket failed: ${closeError.message}`;
                }

                reject(error);
            };

            onOpen = () => settle(null);
            onError = (event) => {
                const cause = (event && (event.message || (event.error && event.error.message))) || 'socket error';

                settle(new Error(`cannot connect to ${endpoint}: ${diagnosticText(cause)}`));
            };
            onClose = (event) => settle(new Error(`the DevTools socket ${endpoint} closed before it opened (code `
                + `${event.code}${event.reason ? `, reason ${diagnosticText(event.reason)}` : ''})`));
            onExit = (code, signal) => settle(new Error(`Chrome exited with ${code === null ? signal : code} before the `
                + `DevTools socket ${endpoint} opened: ${diagnosticText(this.stderrTail)}`));

            socket.addEventListener('open', onOpen);
            socket.addEventListener('error', onError);
            socket.addEventListener('close', onClose);

            if (chrome) {
                chrome.on('exit', onExit);
            }

            timer = setTimeout(() => settle(new Error(`the DevTools socket ${endpoint} did not open within `
                + `${DEVTOOLS_CONNECT_TIMEOUT_MS} ms`)), DEVTOOLS_CONNECT_TIMEOUT_MS);

            if (this.hasExited()) {
                settle(new Error(`Chrome exited before the DevTools socket ${endpoint} opened: `
                    + diagnosticText(this.stderrTail)));
            }
        });
    }

    /**
     * Returns true when Chrome is not running: it was never spawned, its spawn failed (no pid), or it has exited or
     * closed, as recorded by its exit and close events or by its exit code or signal.
     */
    hasExited() {
        const chrome = this.process;

        return this.exited || !chrome || chrome.pid === undefined || chrome.exitCode !== null || chrome.signalCode !== null;
    }

    /**
     * Starts checking the DevTools port `port` of the running Chrome: notes its listening sockets, the runner's own
     * connections to them (the entries of /proc/net/tcp and tcp6 whose inode is a socket of this process) and the
     * entries without a socket (inode 0), such as TIME_WAIT entries, already at a listening address, sets
     * ISOLATION_DEVTOOLS to this client and runs checkIsolation at once. Throws when the tables show no listening socket
     * of the port or no connection of the runner to it.
     */
    watchDevToolsPort(port) {
        const table = socketTable(['tcp', 'tcp6']);
        const listening = new Set(table.filter((entry) => entry.state === '0A' && entry.localPort === port)
            .map((entry) => entry.local));
        const inodes = socketInodes(process.pid);
        const own = new Set(table.filter((entry) => inodes.has(entry.inode) && listening.has(entry.remote))
            .map((entry) => entry.local));

        if (listening.size === 0 || own.size === 0) {
            throw new Error(`acceptance.test.js cannot find ${listening.size === 0 ? 'the listening socket of'
                : 'its own connection to'} the DevTools port ${port} in /proc/net/tcp and tcp6`);
        }

        const key = (entry) => `${entry.protocol} ${entry.local} ${entry.remote}`;
        const foreign = (entry) => entry.state !== '0A' && (listening.has(entry.local) || listening.has(entry.remote))
            && !own.has(entry.local) && !own.has(entry.remote);

        this.devToolsWatch = {
            port,
            listening,
            foreign,
            key,
            earlier: new Set(table.filter((entry) => foreign(entry) && entry.inode === '0').map(key))
        };
        ISOLATION_DEVTOOLS = this;
        checkIsolation();
    }

    /**
     * Returns, while watchDevToolsPort checks a port, 'a <protocol> connection from port <peer> to the DevTools port
     * <port> (<state>)' for every entry of /proc/net/tcp and tcp6, in any state but LISTEN, one end of which is a
     * listening address of the port and neither end of which is one of the runner's own connections, other than the
     * entries without a socket noted by watchDevToolsPort; an empty list otherwise.
     */
    devToolsPortViolations() {
        const watch = this.devToolsWatch;

        if (!watch) {
            return [];
        }

        const violations = [];

        for (const entry of socketTable(['tcp', 'tcp6'])) {
            if (watch.foreign(entry) && !watch.earlier.has(watch.key(entry))) {
                const peerPort = watch.listening.has(entry.local) ? entry.remotePort : entry.localPort;

                violations.push(`a ${entry.protocol} connection from port ${peerPort} to the DevTools port `
                    + `${watch.port} (${TCP_STATE_NAMES[entry.state] || entry.state})`);
            }
        }

        return violations;
    }

    /**
     * Acts on the first call only: stops checking the DevTools port, records `error` as isolationError, rejects every
     * command awaiting its reply, closes the socket and sends SIGKILL to Chrome.
     */
    stopForIsolation(error) {
        if (this.isolationError) {
            return;
        }

        this.devToolsWatch = null;
        this.isolationError = error;
        this.rejectPending((pending) => `${pending.method} (${pending.target}) was abandoned: ${error.message}`);

        if (this.socket) {
            try {
                this.socket.close();
            } catch (closeError) {
                process.stderr.write(`acceptance.test.js: closing the DevTools socket failed: ${closeError.message}\n`);
            }
        }

        if (!this.hasExited()) {
            try {
                this.process.kill('SIGKILL');
            } catch (killError) {
                process.stderr.write(`acceptance.test.js: sending SIGKILL to Chrome (pid ${this.process.pid}) failed: `
                    + `${killError.message}\n`);
            }
        }
    }

    /**
     * Sends a command to the page session sessionId, or to the browser when sessionId is null, and resolves with its
     * result. Rejects at once when the isolation watch has stopped Chrome or the socket is not open. Rejects, and
     * forgets the command, when no reply arrives within `timeout` ms. An error reply rejects with its message and data
     * quoted through diagnosticText.
     *
     * Example: devtools.send('Target.createTarget', { url: 'about:blank' }, null, { timeout: 10000 }).
     */
    send(method, params, sessionId, { timeout = DEVTOOLS_COMMAND_TIMEOUT_MS } = {}) {
        const target = `session ${sessionId || 'browser'}`;

        if (this.isolationError) {
            return Promise.reject(new Error(`${method} (${target}) was not sent: ${this.isolationError.message}`));
        }
        if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
            return Promise.reject(new Error(`${method} (${target}) was not sent: the DevTools socket is not open`));
        }

        const id = this.nextId++;
        const payload = { id, method, params: params || {} };

        if (sessionId) {
            payload.sessionId = sessionId;
        }

        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                if (this.pending.delete(id)) {
                    reject(new Error(`${method} (${target}) got no reply within ${timeout} ms`));
                }
            }, timeout);

            this.pending.set(id, { resolve, reject, method, target, timer });

            try {
                this.socket.send(JSON.stringify(payload));
            } catch (error) {
                clearTimeout(timer);
                this.pending.delete(id);
                reject(new Error(`${method} (${target}) could not be sent: ${error.message}`));
            }
        });
    }

    /** Sends a command to the attached page; options.timeout replaces DEVTOOLS_COMMAND_TIMEOUT_MS. */
    command(method, params, options) {
        return this.send(method, params, this.sessionId, options);
    }

    /** Clears the timer of every command awaiting its reply and rejects it with the message describe(pending) returns. */
    rejectPending(describe) {
        const pendingCommands = Array.from(this.pending.values());

        this.pending.clear();

        for (const pending of pendingCommands) {
            clearTimeout(pending.timer);
            pending.reject(new Error(describe(pending)));
        }
    }

    /**
     * Handles one frame of the DevTools socket: parses it as JSON and hands the object to onMessage. Throws nothing:
     * when the frame is not a JSON object, or onMessage throws, rejects every command awaiting its reply
     * (rejectPending) with an error naming the command and the cause, quoted through diagnosticText.
     */
    onFrame(data) {
        let payload;

        try {
            payload = JSON.parse(String(data));
        } catch (error) {
            this.rejectPending((pending) => `${pending.method} (${pending.target}) was abandoned: the DevTools socket `
                + `delivered a frame that is not JSON (${diagnosticText(error.message)})`);

            return;
        }

        if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
            this.rejectPending((pending) => `${pending.method} (${pending.target}) was abandoned: the DevTools socket `
                + 'delivered a frame that is not a JSON object');

            return;
        }

        try {
            this.onMessage(payload);
        } catch (error) {
            this.rejectPending((pending) => `${pending.method} (${pending.target}) was abandoned: handling a DevTools `
                + `frame failed (${diagnosticText(error.message)})`);
        }
    }

    /**
     * Settles the command a reply names, rejecting it with the error's message and data through diagnosticText, or
     * calls the handlers of an event of the page session.
     */
    onMessage(payload) {
        if (payload.id !== undefined) {
            const pending = this.pending.get(payload.id);

            if (!pending) {
                return;
            }

            this.pending.delete(payload.id);
            clearTimeout(pending.timer);

            if (payload.error) {
                pending.reject(new Error(`${pending.method} failed: ${diagnosticText(payload.error.message)}`
                    + (payload.error.data ? ` (${diagnosticText(payload.error.data)})` : '')));
            } else {
                pending.resolve(payload.result || {});
            }

            return;
        }

        if (payload.sessionId !== this.sessionId) {
            return;
        }

        const handlers = this.listeners.get(payload.method);

        if (handlers) {
            for (const handler of Array.from(handlers)) {
                handler(payload.params || {});
            }
        }
    }

    /** Subscribes to a page event by method name and returns the function that unsubscribes. */
    on(method, handler) {
        if (!this.listeners.has(method)) {
            this.listeners.set(method, new Set());
        }

        this.listeners.get(method).add(handler);

        return () => this.listeners.get(method).delete(handler);
    }

    /**
     * Evaluates the expression in the page, awaiting a returned promise, and returns its value. Throws, quoting the
     * page's exception text through diagnosticText, when the evaluation throws.
     */
    async evaluate(expression) {
        const result = await this.command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });

        if (result.exceptionDetails) {
            const details = result.exceptionDetails;

            throw new Error('page evaluation failed: '
                + diagnosticText((details.exception && details.exception.description) || details.text));
        }

        return result.result ? result.result.value : undefined;
    }

    /** Resolves true once Chrome is not running, or false when it still runs after `timeout` ms. */
    waitForExit(timeout) {
        if (this.hasExited()) {
            return Promise.resolve(true);
        }

        const chrome = this.process;

        return new Promise((resolve) => {
            let timer = null;

            const onExit = () => {
                clearTimeout(timer);
                resolve(true);
            };

            timer = setTimeout(() => {
                chrome.removeListener('exit', onExit);
                resolve(this.hasExited());
            }, timeout);
            chrome.once('exit', onExit);
        });
    }

    /**
     * Stops checking the DevTools port, rejects every command awaiting its reply, closes the socket and stops Chrome:
     * SIGTERM, then SIGKILL when Chrome still runs after CHROME_EXIT_WAIT_MS, then a last wait of CHROME_EXIT_WAIT_MS.
     * Every step runs whatever the earlier ones did. Throws, after the last step, one error naming every failed step.
     */
    async close() {
        const failures = [];

        this.devToolsWatch = null;

        if (ISOLATION_DEVTOOLS === this) {
            ISOLATION_DEVTOOLS = null;
        }

        this.rejectPending((pending) => `${pending.method} (${pending.target}) was abandoned: the DevTools client closed`);

        if (this.socket) {
            try {
                this.socket.close();
            } catch (error) {
                failures.push(`closing the DevTools socket failed: ${error.message}`);
            }
        }

        if (!this.hasExited()) {
            const chrome = this.process;
            let running = true;

            for (const signal of ['SIGTERM', 'SIGKILL']) {
                try {
                    chrome.kill(signal);
                } catch (error) {
                    failures.push(`sending ${signal} to Chrome (pid ${chrome.pid}) failed: ${error.message}`);
                }

                if (await this.waitForExit(CHROME_EXIT_WAIT_MS)) {
                    running = false;
                    break;
                }
            }

            if (running) {
                failures.push(`Chrome (pid ${chrome.pid}) still runs ${CHROME_EXIT_WAIT_MS} ms after SIGKILL`
                    + (this.processError ? ` (last process error: ${this.processError.message})` : ''));
            }
        }

        if (failures.length > 0) {
            throw new Error(failures.join('; '));
        }
    }
}

let DEVTOOLS = null;

/** Returns the path of a URL without scheme, host, query and fragment. */
function urlPath(url) {
    return url.replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]+/i, '').split(/[?#]/)[0];
}

/** Returns the value of a header of a DevTools Protocol headers object, matched without case, or null. */
function headerValue(headers, name) {
    const wanted = name.toLowerCase();

    for (const key of Object.keys(headers || {})) {
        if (key.toLowerCase() === wanted) {
            return String(headers[key]);
        }
    }

    return null;
}

/**
 * Returns the literal values diagnosticText replaces by [redacted], longest first, without duplicates and empty values:
 * each password of --db-uri (the password of its user information and every password or sslpassword query parameter)
 * as written, and the --password value and each percent-decoded --db-uri password in the forms of secretForms.
 * args is the result of parseArguments.
 */
function diagnosticSecrets(args) {
    const written = [];
    const authority = /^[A-Za-z][A-Za-z0-9+.-]*:\/\/([^/?#]*)/.exec(args.dbUri);
    const userInfo = authority && authority[1].includes('@')
        ? authority[1].slice(0, authority[1].lastIndexOf('@')) : '';

    if (userInfo.includes(':')) {
        written.push(userInfo.slice(userInfo.indexOf(':') + 1));
    }

    const queryStart = args.dbUri.indexOf('?');

    for (const parameter of queryStart < 0 ? [] : args.dbUri.slice(queryStart + 1).split('&')) {
        const separator = parameter.indexOf('=');

        if (separator > 0 && ['password', 'sslpassword'].includes(percentDecode(parameter.slice(0, separator)))) {
            written.push(parameter.slice(separator + 1));
        }
    }

    const plain = [args.password].concat(written.map(percentDecode).filter((value) => value !== null));
    const secrets = new Set(written.concat(...plain.map(secretForms)));

    return Array.from(secrets).filter((secret) => secret !== '').sort((a, b) => b.length - a.length);
}

/**
 * Returns the forms in which a secret can appear in a response: as given, URI-component-encoded,
 * application/x-www-form-urlencoded (space as '+'), inside a JSON string, and HTML-escaped with named or numeric
 * quote references (&amp; &lt; &gt; with &quot; &#39;, or with &#034; &#039;).
 */
function secretForms(secret) {
    const markup = secret.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

    return [
        secret,
        encodeURIComponent(secret),
        new URLSearchParams([['', secret]]).toString().slice(1),
        JSON.stringify(secret).slice(1, -1),
        markup.replace(/"/g, '&quot;').replace(/'/g, '&#39;'),
        markup.replace(/"/g, '&#034;').replace(/'/g, '&#039;')
    ];
}

// Words of a key whose value diagnosticText redacts, matched without case anywhere inside the key.
const CREDENTIAL_KEY_WORDS = 'password|passwd|pwd|secret|token|csrf|authorization|api[_-]?key|jsessionid'
    + '|session[_-]?id|cookie|credential';

// A whole key of letters, digits, '_', '.' and '-' that holds a word of CREDENTIAL_KEY_WORDS, as the group key.
const CREDENTIAL_KEY = String.raw`(?<![A-Za-z0-9_.-])(?=[A-Za-z0-9_.-]*?(?:${CREDENTIAL_KEY_WORDS}))`
    + String.raw`(?=(?<key>[A-Za-z0-9_.-]+))\k<key>`;

// An HTML character reference of a double or a single quote: &quot;, &#34;, &#x22;, &apos;, &#39; or &#x27;.
const QUOTE_ENTITY = '&(?:quot|#34|#x22|apos|#39|#x27);';

// A value in double quotes, single quotes (backslash escapes included; an unterminated one runs to the end of the
// text) or QUOTE_ENTITY quotes (up to the next '&', '<' or line break, and the closing reference).
const QUOTED_VALUE = String.raw`"(?:[^"\\]|\\[\s\S])*"?|'(?:[^'\\]|\\[\s\S])*'?`
    + String.raw`|${QUOTE_ENTITY}[^&<\r\n]*(?:${QUOTE_ENTITY})?`;

// "key": value, 'key': value and &quot;key&quot;: value with a quoted or a bare value, as in JSON and JavaScript.
const CREDENTIAL_QUOTED_PAIR = new RegExp(String.raw`(?<quote>["']|${QUOTE_ENTITY})`
    + String.raw`(?=[A-Za-z0-9_.-]*?(?:${CREDENTIAL_KEY_WORDS}))(?<key>[A-Za-z0-9_.-]+)\k<quote>`
    + String.raw`(?<separator>\s*:\s*)(?:${QUOTED_VALUE}|[^\s,}\]]*)`, 'gi');

// key=value with a quoted value or a bare value up to '&', white space, a quote, '<' or '>', as in forms, queries and
// cookies.
const CREDENTIAL_ASSIGNMENT = new RegExp(String.raw`${CREDENTIAL_KEY}(?<separator>[ \t]*=[ \t]*)`
    + String.raw`(?:${QUOTED_VALUE}|[^&\s"'<>]*)`, 'gi');

// key 'value' and key "value": a key, spaces or tabs, then a quoted value, as in "Invalid CSRF Token 'value'". A key
// after the word "Unexpected", as in the JSON.parse message "Unexpected token '<'", is left as it is.
const CREDENTIAL_MENTION = new RegExp(String.raw`(?<!\bunexpected[ \t]+)${CREDENTIAL_KEY}(?<separator>[ \t]+)`
    + String.raw`(?:${QUOTED_VALUE})`, 'gi');

// key: value with a quoted value or the rest of the line, as in HTTP headers.
const CREDENTIAL_HEADER = new RegExp(String.raw`${CREDENTIAL_KEY}(?<separator>[ \t]*:[ \t]*)`
    + String.raw`(?:${QUOTED_VALUE}|[^\r\n]*)`, 'gi');

// A meta or input tag with the tag name as group 1: attribute values in double or single quotes may hold '>', and a
// quote or tag left open runs to the end of the text.
const MARKUP_TAG = /<(meta|input)\b(?:[^>"']|"[^"]*(?:"|$)|'[^']*(?:'|$))*(?:>|$)/gi;

// One attribute of a tag, read from left to right: its name as group 1, then optionally '=' and a value in double
// quotes (group 2), single quotes (group 3) or bare (group 4); a quote left open runs to the end of the tag.
const MARKUP_ATTRIBUTE = /([^\s"'>/=]+)(?:\s*=\s*(?:"([^"]*)"?|'([^']*)'?|([^\s"'>]*)))?/g;

/** Returns whether a MARKUP_TAG match has an attribute named name (in any case) whose value is exactly _csrf. */
function isCsrfTag(tag, tagName) {
    for (const attribute of tag.slice(1 + tagName.length).matchAll(MARKUP_ATTRIBUTE)) {
        const value = [attribute[2], attribute[3], attribute[4]].find((part) => part !== undefined);

        if (attribute[1].toLowerCase() === 'name' && value === '_csrf') {
            return true;
        }
    }

    return false;
}

// The user information of a URI, scheme://user:password@, with the scheme as group 1.
const URI_USER_INFO = /(?<![A-Za-z0-9+.-])([A-Za-z][A-Za-z0-9+.-]*:\/\/)[^\s/?#"'<>]*@/g;

// One Java stack frame, 'at pkg.Class.method(File.java:N)' (also HTML-escaped or after a \t escape), or '... N more'.
const JAVA_STACK_FRAME = String.raw`(?:(?:(?<=\\t)|\b)at\s+[\w$&;#@/-]+(?:\.[\w$&;#@/<>-]+)+\([^()\r\n]*\)`
    + String.raw`|\.\.\.\s*\d+\s+(?:more|common frames omitted)\b)`;

// A run of Java stack frames separated by white space, <br> tags or the escapes \n, \r and \t.
const JAVA_STACK_TRACE = new RegExp(String.raw`${JAVA_STACK_FRAME}(?:(?:\s|<br\s*\/?>|\\[nrt])*${JAVA_STACK_FRAME})*`,
    'gi');

/**
 * Returns an external text (a response body, a header value, a URL, a user name, or a message of the browser, of a
 * parser or of the DevTools Protocol) as one line for an error message. It applies in this order:
 * - every value of DIAGNOSTIC_SECRETS replaced by [redacted];
 * - the leading and trailing white space removed;
 * - every meta or input tag (MARKUP_TAG) that isCsrfTag accepts replaced by one whose content or value is [redacted];
 * - the user information of every scheme://user:password@ URI replaced by [redacted];
 * - the value of every key holding a word of CREDENTIAL_KEY_WORDS (j_password, _csrf, X-CSRF-TOKEN, JSESSIONID,
 *   Cookie, Set-Cookie, Authorization, api_key and so on) replaced by [redacted], in the forms "key": value
 *   (CREDENTIAL_QUOTED_PAIR), key=value (CREDENTIAL_ASSIGNMENT), key 'value' (CREDENTIAL_MENTION) and key: value
 *   (CREDENTIAL_HEADER), in that order;
 * - every run of Java stack frames replaced by [stack trace omitted];
 * - every character of CONTROL_CHARACTERS written as a \uXXXX escape (escapeControlCharacters);
 * - white space runs collapsed to one space, and the result trimmed;
 * - a result longer than RESPONSE_EXCERPT_LENGTH characters cut to that length, then stripped of a trailing partial
 *   \uXXXX escape or lone high surrogate.
 * Returns '' for undefined, null and blank text. For example, 'HTTP 500\r\nSet-Cookie: JSESSIONID=1; Path=/' becomes
 * 'HTTP 500\u000D\u000ASet-Cookie: [redacted]'.
 */
function diagnosticText(text) {
    let result = String(text === undefined || text === null ? '' : text);

    if (DIAGNOSTIC_SECRETS.length > 0) {
        const secrets = DIAGNOSTIC_SECRETS.map((secret) => secret.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));

        result = result.replace(new RegExp(secrets.join('|'), 'g'), '[redacted]');
    }

    result = result.trim()
        .replace(MARKUP_TAG, (tag, tagName) => (isCsrfTag(tag, tagName)
            ? `<${tagName} name="_csrf" ${tagName.toLowerCase() === 'meta' ? 'content' : 'value'}="[redacted]">` : tag))
        .replace(URI_USER_INFO, '$1[redacted]@')
        .replace(CREDENTIAL_QUOTED_PAIR, '$<quote>$<key>$<quote>$<separator>[redacted]')
        .replace(CREDENTIAL_ASSIGNMENT, '$<key>$<separator>[redacted]')
        .replace(CREDENTIAL_MENTION, '$<key>$<separator>[redacted]')
        .replace(CREDENTIAL_HEADER, '$<key>$<separator>[redacted]')
        .replace(JAVA_STACK_TRACE, '[stack trace omitted]');

    result = escapeControlCharacters(result).replace(/\s+/g, ' ').trim();

    if (result.length > RESPONSE_EXCERPT_LENGTH) {
        result = result.slice(0, RESPONSE_EXCERPT_LENGTH).replace(/\\(?:u[0-9A-F]{0,3})?$|[\uD800-\uDBFF]$/, '')
            .trimEnd();
    }

    return result;
}

/** Returns diagnosticText of a response body, or '(empty body)' when that is ''. */
function responseExcerpt(text) {
    const excerpt = diagnosticText(text);

    return excerpt === '' ? '(empty body)' : excerpt;
}

/**
 * Records the POST requests to the board from the page's Network events. Each entry holds the request id, the post
 * data, the HTTP status, MIME type and Location header of the answer, the redirects the request followed ({status,
 * location, url}), whether loading finished and, when it failed, the cause. lastActivityAt is the time of the last
 * Network event of a recorded request.
 */
class BoardRequestRecorder {
    constructor(devtools) {
        this.devtools = devtools;
        this.entries = [];
        this.lastActivityAt = Date.now();

        const byId = new Map();
        const touch = () => {
            this.lastActivityAt = Date.now();
        };

        this.unsubscribers = [
            devtools.on('Network.requestWillBeSent', (params) => {
                const request = params.request;
                const known = byId.get(params.requestId);

                if (known && params.redirectResponse) {
                    known.redirects.push({
                        status: params.redirectResponse.status,
                        location: headerValue(params.redirectResponse.headers, 'location'),
                        url: request.url
                    });
                    touch();
                    return;
                }

                if (request.method !== 'POST' || urlPath(request.url) !== BOARD_PATH) {
                    return;
                }

                const entry = {
                    requestId: params.requestId,
                    postData: request.postData === undefined ? null : request.postData,
                    postDataPending: false,
                    status: null,
                    mimeType: null,
                    location: null,
                    url: request.url,
                    redirects: [],
                    finished: false,
                    failed: null
                };

                if (entry.postData === null && request.hasPostData) {
                    entry.postDataPending = true;
                    devtools.command('Network.getRequestPostData', { requestId: params.requestId })
                        .then((result) => {
                            entry.postData = result.postData;
                        }, (error) => {
                            entry.failed = `post data unavailable: ${error.message}`;
                        })
                        .finally(() => {
                            entry.postDataPending = false;
                            touch();
                        });
                }

                this.entries.push(entry);
                byId.set(params.requestId, entry);
                touch();
            }),
            devtools.on('Network.responseReceived', (params) => {
                const entry = byId.get(params.requestId);

                if (entry && params.response) {
                    entry.status = params.response.status;
                    entry.mimeType = params.response.mimeType || null;
                    entry.location = headerValue(params.response.headers, 'location');
                    entry.url = params.response.url || entry.url;
                    touch();
                }
            }),
            devtools.on('Network.loadingFinished', (params) => {
                const entry = byId.get(params.requestId);

                if (entry) {
                    entry.finished = true;
                    touch();
                }
            }),
            devtools.on('Network.loadingFailed', (params) => {
                const entry = byId.get(params.requestId);

                if (entry) {
                    entry.failed = params.errorText || 'loading failed';
                    touch();
                }
            })
        ];
    }

    stop() {
        for (const unsubscribe of this.unsubscribers) {
            unsubscribe();
        }

        this.unsubscribers = [];
    }

    /** Returns true while the post data of a recorded request is still being read with Network.getRequestPostData. */
    hasPendingPostData() {
        return this.entries.some((entry) => entry.postDataPending);
    }

    /** Returns the first recorded request that failed, or undefined. */
    firstFailed() {
        return this.entries.find((entry) => entry.failed);
    }

    static eventOf(entry) {
        if (!entry.postData) {
            return null;
        }

        try {
            return JSON.parse(entry.postData).event || null;
        } catch (error) {
            return null;
        }
    }

    eventNames() {
        return this.entries.map((entry) => {
            const event = BoardRequestRecorder.eventOf(entry);

            return event ? event.name : null;
        });
    }

    withEvent(name) {
        return this.entries.filter((entry) => {
            const event = BoardRequestRecorder.eventOf(entry);

            return event !== null && event.name === name;
        });
    }

    /** Returns 'board request <requestId> (event <name>)', with 'unknown' when the post data holds no event. */
    static describe(entry) {
        const event = BoardRequestRecorder.eventOf(entry);

        return `board request ${entry.requestId} (event ${event && event.name ? event.name : 'unknown'})`;
    }

    /** Returns {requestId, event, status, redirects, finished, failed} of every entry, failed through diagnosticText. */
    summary() {
        return this.entries.map((entry) => {
            const event = BoardRequestRecorder.eventOf(entry);

            return {
                requestId: entry.requestId,
                event: event ? event.name : null,
                status: entry.status,
                redirects: entry.redirects.map((redirect) => redirect.status),
                finished: entry.finished,
                failed: entry.failed === null ? null : diagnosticText(entry.failed)
            };
        });
    }

    /**
     * Returns the parsed JSON response body of a finished request. Throws an error naming the request id, the event,
     * the HTTP status and an excerpt of the body (see responseExcerpt) when the request failed or was redirected (to the
     * login page or elsewhere), or its answer is not HTTP 200, is sessionExpired, is an error page or an HTML page, or
     * is not JSON. The failure cause, the redirect target, the Location header, the MIME type and the DevTools and
     * parser messages are quoted through diagnosticText.
     */
    async responseJson(entry) {
        const request = BoardRequestRecorder.describe(entry);

        if (entry.failed) {
            throw new Error(`${request} failed: ${diagnosticText(entry.failed)}`);
        }
        if (entry.redirects.length > 0) {
            const redirect = entry.redirects[0];
            const target = redirect.location || redirect.url;

            throw new Error(`${request} was redirected (HTTP ${redirect.status}) to `
                + `${/login/i.test(`${target} ${redirect.url}`) ? 'the login page ' : ''}${diagnosticText(target)}`);
        }

        let text;

        try {
            const result = await this.devtools.command('Network.getResponseBody', { requestId: entry.requestId });

            text = (result.base64Encoded ? Buffer.from(result.body, 'base64').toString('utf8') : result.body).trim();
        } catch (error) {
            throw new Error(`${request} answered HTTP ${entry.status === null ? '(status unknown)' : entry.status}, `
                + `and its body is unavailable: ${diagnosticText(error.message)}`);
        }

        const answered = `${request} answered HTTP ${entry.status === null ? '(status unknown)' : entry.status}`;

        if (entry.status !== 200) {
            throw new Error(`${answered}${entry.location ? ` with Location ${diagnosticText(entry.location)}` : ''}: `
                + responseExcerpt(text));
        }
        if (text === 'sessionExpired') {
            throw new Error(`${answered} with sessionExpired`);
        }
        if (text.startsWith('<![CDATA[ERROR PAGE:')) {
            throw new Error(`${answered} with an error page: ${responseExcerpt(text)}`);
        }
        if (/^(?:<!DOCTYPE|<html)/i.test(text)) {
            throw new Error(`${answered} with an HTML page (${diagnosticText(entry.mimeType) || 'no MIME type'}`
                + `${/login/i.test(entry.url || '') ? ', the login page' : ''}): ${responseExcerpt(text)}`);
        }

        try {
            return JSON.parse(text);
        } catch (error) {
            throw new Error(`${answered} with no JSON (${diagnosticText(error.message)}): ${responseExcerpt(text)}`);
        }
    }
}

/**
 * Waits until exactly one finished request of the recorder carries the event and no post data is still being read.
 * Fails at the first poll that sees a failed board request, whatever its event and whatever other request is still
 * pending, naming its request id, its event and the cause, and when a second request carries the event.
 */
async function waitForSingleEvent(recorder, name, timeout) {
    return waitFor(`the ${name} request`, () => {
        const failed = recorder.firstFailed();

        if (failed) {
            throw new FatalError(`${BoardRequestRecorder.describe(failed)} failed while waiting for the ${name} request: `
                + diagnosticText(failed.failed));
        }
        if (recorder.hasPendingPostData()) {
            return false;
        }

        const entries = recorder.withEvent(name);

        if (entries.length > 1) {
            throw new FatalError(`${entries.length} ${name} requests were sent: `
                + entries.map((entry) => entry.requestId).join(', '));
        }

        return entries.length === 1 && entries[0].finished ? entries[0] : false;
    }, { timeout, interval: 50, detail: () => JSON.stringify(recorder.summary()) });
}

// ---------------------------------------------------------------------------------------------------------------
// Browser
// ---------------------------------------------------------------------------------------------------------------

// Binds F to the main page iframe, W to its window and D to its document; each is null when the one before is missing.
const FRAME_PREAMBLE = 'const F = document.getElementById(\'mainPageIframe\'); const W = F ? F.contentWindow : null; '
    + 'const D = W ? W.document : null;';

// Statements that throw when the main page iframe, its window or its document is missing.
const FRAME_GUARD = 'if (!F) { throw new Error(\'the main page iframe #mainPageIframe is missing\'); } '
    + 'if (!W || !D) { throw new Error(\'the main page iframe #mainPageIframe has no document\'); }';

/**
 * Wraps statements that read the board's iframe (F, W, D) into an expression. With requireFrame (the default) the
 * expression throws a missing-iframe or no-document error before the statements run; without it the statements handle
 * a null F, W or D themselves.
 */
function frameExpression(statements, { requireFrame = true } = {}) {
    return `(() => { ${FRAME_PREAMBLE} ${requireFrame ? FRAME_GUARD : ''} ${statements} })()`;
}

function barElementId(itemId) {
    return `${GANTT_PATH}_item_${itemId}`;
}

// Page state read after the login click: path and query, whether main.html is ready, whether login-min.js marked the
// inputs is-invalid, and the text of the #messagePanel when it is shown as alert-danger (null otherwise).
const LOGIN_STATE_EXPRESSION = `(() => {
    const panel = document.getElementById('messagePanel');
    const username = document.getElementById('usernameInput');
    const password = document.getElementById('passwordInput');
    const panelText = (id) => ((document.getElementById(id) || {}).textContent || '').trim();
    const alertShown = !!panel && panel.classList.contains('alert-danger') && getComputedStyle(panel).display !== 'none';
    return {
        pathname: location.pathname,
        search: location.search,
        ready: location.pathname === '/main.html' && typeof window.goToPage === 'function'
            && typeof window.encodeParams === 'function',
        invalid: (!!username && username.classList.contains('is-invalid'))
            || (!!password && password.classList.contains('is-invalid')),
        alert: alertShown ? (panelText('messageHeader') + ' ' + panelText('messageContent')).replace(/\\s+/g, ' ').trim() : null
    };
})()`;

/**
 * Logs in through the login form with fresh cookies and waits up to 120 s for main.html. Fails at once, naming the
 * user and never the password, when the POST to /j_spring_security_check fails or answers a status other than 200,
 * when the form marks the inputs is-invalid (wrong login or password), shows an alert-danger message (blocked user or
 * another refusal), or the page moves to ?loginError=true (request error) or ?timeout=true (sessionExpired). The user,
 * the navigation and request errors, the alert text, the page's path and query, and the last state of a timeout are
 * quoted through diagnosticText.
 */
async function browserLogin() {
    await DEVTOOLS.command('Network.clearBrowserCookies');

    const navigation = await DEVTOOLS.command('Page.navigate', { url: `${ARGS.baseUrl}/login.html?lang=en` },
        { timeout: DEVTOOLS_NAVIGATE_TIMEOUT_MS });

    if (navigation.errorText) {
        throw new Error(`cannot open the login page: ${diagnosticText(navigation.errorText)}`);
    }

    await waitFor('the login form', () => DEVTOOLS.evaluate('document.readyState === \'complete\' '
        + '&& location.pathname === \'/login.html\' && !!document.getElementById(\'usernameInput\') '
        + '&& !!document.getElementById(\'passwordInput\') && !!document.getElementById(\'loginButton\')'),
    { timeout: 60000, interval: 100 });

    const login = { requestId: null, status: null, failed: null };
    const unsubscribers = [
        DEVTOOLS.on('Network.requestWillBeSent', (params) => {
            if (params.request.method === 'POST' && urlPath(params.request.url) === '/j_spring_security_check') {
                login.requestId = params.requestId;
                login.status = null;
                login.failed = null;
            }
        }),
        DEVTOOLS.on('Network.responseReceived', (params) => {
            if (params.requestId === login.requestId && params.response) {
                login.status = params.response.status;
            }
        }),
        DEVTOOLS.on('Network.loadingFailed', (params) => {
            if (params.requestId === login.requestId) {
                login.failed = params.errorText || 'loading failed';
            }
        })
    ];

    try {
        await DEVTOOLS.evaluate(`(() => {
            document.getElementById('usernameInput').value = ${JSON.stringify(ARGS.user)};
            document.getElementById('passwordInput').value = ${JSON.stringify(ARGS.password)};
            document.getElementById('loginButton').click();
            return true;
        })()`);

        let last = null;
        const user = diagnosticText(ARGS.user);

        await waitFor('main.html after login', async () => {
            if (login.failed) {
                throw new FatalError(`the login request of user ${user} failed: ${diagnosticText(login.failed)}`);
            }
            if (login.status !== null && login.status !== 200) {
                throw new FatalError(`the login request of user ${user} answered HTTP ${login.status}`);
            }

            last = await DEVTOOLS.evaluate(LOGIN_STATE_EXPRESSION);

            if (!last) {
                return false;
            }
            if (last.ready) {
                return true;
            }
            if (last.invalid) {
                throw new FatalError(`wrong login or password for user ${user}`);
            }
            if (last.alert !== null) {
                throw new FatalError(`the login of user ${user} was refused: ${diagnosticText(last.alert)}`);
            }
            if (/[?&]loginError=true\b/.test(last.search)) {
                throw new FatalError(`the login request of user ${user} ended with an error: the page moved to `
                    + diagnosticText(`${last.pathname}${last.search}`));
            }
            if (/[?&]timeout=true\b/.test(last.search)) {
                throw new FatalError(`the login of user ${user} answered sessionExpired: the page moved to `
                    + diagnosticText(`${last.pathname}${last.search}`));
            }

            return false;
        }, {
            timeout: 120000,
            interval: 250,
            detail: () => diagnosticText(JSON.stringify({ loginStatus: login.status, page: last }))
        });
    } finally {
        for (const unsubscribe of unsubscribers) {
            unsubscribe();
        }
    }
}

/**
 * Opens the board of the schedule in the main page iframe, as the ribbon redirect does, and waits for its rows and,
 * when barItemId is given, the bar of that item. Returns the initialize request body and its response.
 */
async function browserOpenBoard(scheduleId, barItemId) {
    const recorder = new BoardRequestRecorder(DEVTOOLS);

    try {
        const pageUrl = `${BOARD_PATH}?lang=en&context=`;

        await DEVTOOLS.evaluate(`(() => {
            window.goToPage(window.encodeParams(${JSON.stringify(pageUrl)}
                + JSON.stringify({ 'gantt.productionLineScheduleId': ${JSON.stringify(String(scheduleId))} })), null, false);
            return true;
        })()`);

        const barPresent = barItemId === null
            ? 'D.querySelectorAll(\'.ganttItem\').length > 0'
            : `!!D.getElementById(${JSON.stringify(barElementId(barItemId))})`;
        let frameState = null;

        await waitFor(`the board of schedule ${scheduleId} in the main page iframe`, async () => {
            frameState = await DEVTOOLS.evaluate(frameExpression(`
                const state = {
                    iframe: !!F,
                    window: !!W,
                    document: !!D,
                    mainController: !!(W && W.mainController),
                    rows: D ? D.querySelectorAll('.ganttRowNameElement').length : 0,
                    bar: !!D && ${barPresent}
                };
                state.ready = state.mainController && state.rows >= 3 && state.bar;
                return state;`, { requireFrame: false }));

            return frameState !== null && frameState !== undefined && frameState.ready;
        }, { timeout: 120000, interval: 250, detail: () => JSON.stringify(frameState) });

        const initializeEntry = await waitForSingleEvent(recorder, 'initialize', 60000);

        return {
            request: JSON.parse(initializeEntry.postData),
            response: await recorder.responseJson(initializeEntry)
        };
    } finally {
        recorder.stop();
    }
}

/**
 * Reads the bar of the item: inline left/top, class names, centre in top-level CSS pixels, whether the bar is the
 * topmost element at its centre (hit), x within the rows content, row index, row names, the visible rows rectangle (the
 * rows wrapper's client area within the iframe) and the tooltip body text.
 */
async function readBar(itemId) {
    return DEVTOOLS.evaluate(frameExpression(`
        const root = D.getElementById(${JSON.stringify(GANTT_PATH)});
        const bar = D.getElementById(${JSON.stringify(barElementId(itemId))});
        if (!root || !bar) { return null; }
        const frameRect = F.getBoundingClientRect();
        const offsetX = frameRect.left + F.clientLeft;
        const offsetY = frameRect.top + F.clientTop;
        const barRect = bar.getBoundingClientRect();
        const wrapper = root.querySelector('.rowsContainerWrapper');
        const wrapperRect = wrapper.getBoundingClientRect();
        const container = root.querySelector('.rowsContainer');
        const containerRect = container.getBoundingClientRect();
        const rows = Array.prototype.slice.call(root.querySelectorAll('.ganttRowElement'));
        const rowNames = Array.prototype.slice.call(root.querySelectorAll('.ganttRowNameElement'))
            .map((element) => element.textContent);
        const tooltip = D.querySelector('.ganttChartTooltipBody');
        const centerX = offsetX + barRect.left + barRect.width / 2;
        const centerY = offsetY + barRect.top + barRect.height / 2;
        const frameHit = D.elementFromPoint(barRect.left + barRect.width / 2, barRect.top + barRect.height / 2);
        return {
            left: bar.style.left,
            top: bar.style.top,
            className: bar.className,
            centerX: centerX,
            centerY: centerY,
            hit: document.elementFromPoint(centerX, centerY) === F && !!frameHit
                && (frameHit === bar || bar.contains(frameHit)),
            contentX: barRect.left - containerRect.left,
            rowIndex: rows.indexOf(bar.parentElement),
            rowNames: rowNames,
            visible: {
                left: offsetX + Math.max(wrapperRect.left, 0),
                top: offsetY + Math.max(wrapperRect.top, 0),
                right: offsetX + Math.min(wrapperRect.left + wrapper.clientWidth, F.clientWidth),
                bottom: offsetY + Math.min(wrapperRect.top + wrapper.clientHeight, F.clientHeight)
            },
            scrollLeft: wrapper.scrollLeft,
            tooltip: tooltip ? tooltip.textContent : null
        };`));
}

async function requireBar(itemId) {
    const bar = await readBar(itemId);

    if (!bar) {
        throw new Error(`bar ${barElementId(itemId)} is not on the board`);
    }

    return bar;
}

function isInside(point, rect) {
    return point.x > rect.left && point.x < rect.right && point.y > rect.top && point.y < rect.bottom;
}

function dispatchMouse(type, point, extra) {
    return DEVTOOLS.command('Input.dispatchMouseEvent', Object.assign({ type, x: point.x, y: point.y }, extra));
}

/** Sends a left-button mouseReleased at the point. A failure is written to stderr and not thrown. */
async function releaseLeftButton(point) {
    try {
        await dispatchMouse('mouseReleased', point, { button: 'left', buttons: 0, clickCount: 1 });
    } catch (error) {
        process.stderr.write(`acceptance.test.js: releasing the left mouse button at ${JSON.stringify(point)} after a `
            + `failed drag failed: ${error.message}\n`);
    }
}

/**
 * Drags the bar of the rendered item from its centre to the point of targetRow and targetDate with real mouse input,
 * waits for the single moveItem request and checks its payload. Returns {payload, event, moveResult, content,
 * recorder}; the recorder keeps recording until finishDrag stops it. On a failure the drag releases the left button
 * at the last point sent when the button is down, stops the recorder and throws the first error.
 */
async function browserDrag({ item, targetRow, targetDate }) {
    const initial = await requireBar(item.id);
    const scrollLeft = Math.max(0, Math.round(initial.contentX - 150));

    await DEVTOOLS.evaluate(frameExpression(`
        const wrapper = D.getElementById(${JSON.stringify(GANTT_PATH)}).querySelector('.rowsContainerWrapper');
        wrapper.scrollLeft = ${scrollLeft};
        return wrapper.scrollLeft;`));

    // The bar is topmost at its centre (no loading overlay) and its geometry is unchanged between two reads.
    let previous = null;
    const bar = await waitFor('a stable, uncovered bar after scrolling', async () => {
        const current = await requireBar(item.id);
        const stable = previous !== null && JSON.stringify(current) === JSON.stringify(previous);

        previous = current;

        return stable && current.hit ? current : false;
    }, { timeout: 30000, interval: 100, detail: () => JSON.stringify(previous) });

    const targetIndex = bar.rowNames.indexOf(targetRow);

    assert.ok(targetIndex >= 0, `row ${targetRow} is on the board (${bar.rowNames.join(', ')})`);
    assert.ok(bar.rowIndex >= 0, `bar ${barElementId(item.id)} sits in a row`);

    const press = { x: bar.centerX, y: bar.centerY };
    const target = {
        x: press.x + (minutesBetween(item.info.dateFrom, targetDate) / 60) * CELL_WIDTH_PX,
        y: press.y + (targetIndex - bar.rowIndex) * CELL_HEIGHT_PX
    };

    assert.ok(isInside(press, bar.visible), `press point ${JSON.stringify(press)} lies inside ${JSON.stringify(bar.visible)}`);
    assert.ok(isInside(target, bar.visible), `target point ${JSON.stringify(target)} lies inside ${JSON.stringify(bar.visible)}`);

    const deltaX = target.x - press.x;
    const deltaY = target.y - press.y;
    const length = Math.hypot(deltaX, deltaY);

    assert.ok(length > 0, 'the target differs from the press point');

    const recorder = new BoardRequestRecorder(DEVTOOLS);

    // Whether the left button is down, and the last point sent while it is.
    let buttonDown = false;
    let lastPoint = press;

    const moveTo = (point) => {
        lastPoint = point;

        return dispatchMouse('mouseMoved', point, { button: 'left', buttons: 1 });
    };

    try {
        buttonDown = true;
        await dispatchMouse('mousePressed', press, { button: 'left', buttons: 1, clickCount: 1 });
        await moveTo({ x: press.x + (deltaX / length) * 6, y: press.y + (deltaY / length) * 6 });

        for (let step = 1; step <= 10; step++) {
            await moveTo({ x: press.x + (deltaX * step) / 10, y: press.y + (deltaY * step) / 10 });
        }

        lastPoint = target;
        await dispatchMouse('mouseReleased', target, { button: 'left', buttons: 0, clickCount: 1 });
        buttonDown = false;

        const moveEntry = await waitForSingleEvent(recorder, 'moveItem', 120000);
        const event = BoardRequestRecorder.eventOf(moveEntry);

        assert.equal(event.component, GANTT_PATH, 'moveItem targets the gantt component');
        assert.ok(Array.isArray(event.args) && event.args.length === 1, 'moveItem carries one argument');

        const payload = JSON.parse(event.args[0]);

        assert.equal(payload.itemId, item.id, 'moveItem names the dragged item');
        assert.equal(payload.row, targetRow, 'moveItem names the target row');
        assert.equal(payload.dateFrom, targetDate, 'moveItem names the target date');

        const found = findGanttContent(await recorder.responseJson(moveEntry));

        assert.ok(found && found.content.moveResult, 'the moveItem answer has a moveResult');

        return { payload, event, moveResult: found.content.moveResult, content: found.content, recorder };
    } catch (error) {
        if (buttonDown) {
            await releaseLeftButton(lastPoint);
        }

        recorder.stop();
        throw error;
    }
}

/**
 * Keeps the drag's recorder recording until the board is idle: every recorded request has finished or failed, its
 * post data has been read, the gantt root holds no .blockUI element, and no Network event of a board request has
 * arrived for DRAG_IDLE_QUIET_MS. Fails at once when a recorded request failed, and after DRAG_IDLE_TIMEOUT_MS
 * without an idle board. Then asserts that no request failed and that the drag sent exactly one board request, a
 * moveItem, and no refresh. Stops the recorder in every case.
 */
async function finishDrag(drag) {
    const recorder = drag.recorder;
    let last = null;

    try {
        await waitFor('the board idle after the drop', async () => {
            const failed = recorder.firstFailed();

            if (failed) {
                throw new FatalError(`${BoardRequestRecorder.describe(failed)} failed after the drop: `
                    + diagnosticText(failed.failed));
            }

            const blocked = await DEVTOOLS.evaluate(frameExpression(`
                const root = D.getElementById(${JSON.stringify(GANTT_PATH)});
                return root ? root.querySelector('.blockUI') !== null : null;`));
            const quietFor = Date.now() - recorder.lastActivityAt;
            const postDataPending = recorder.hasPendingPostData();

            last = { ganttBlocked: blocked, quietFor, postDataPending, requests: recorder.summary() };

            return blocked === false && quietFor >= DRAG_IDLE_QUIET_MS && !postDataPending
                && recorder.entries.every((entry) => entry.finished || entry.failed);
        }, { timeout: DRAG_IDLE_TIMEOUT_MS, interval: 100, detail: () => JSON.stringify(last) });

        const events = recorder.eventNames();

        assert.deepStrictEqual(recorder.summary().filter((request) => request.failed !== null), [],
            'no board request failed after the drop');
        assert.equal(recorder.withEvent('moveItem').length, 1,
            `exactly one moveItem for the drop (events: ${events.join(', ')})`);
        assert.deepStrictEqual(recorder.withEvent('refresh').length, 0,
            `no refresh after the drop (events: ${events.join(', ')})`);
        assert.deepStrictEqual(events, ['moveItem'], `no board request other than the moveItem (events: ${events.join(', ')})`);
    } finally {
        recorder.stop();
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------------------------------------------

// English rejection reasons and the rejection tooltip header.
let EXPECTED = null;

before(async () => {
    await verifyAcceptanceTarget();

    EXPECTED = {
        routing: message('orders.error.inappropriateProductionLineForPositionOrder'),
        shutdownWindow: message('cmmsMachineParts.productionMaintenanceGantt.move.error.shutdownWindow', 'PMG-EV-SHUTDOWN'),
        outsideWorkingHours: message('cmmsMachineParts.productionMaintenanceGantt.move.error.outsideWorkingHours'),
        recomputeFailed: message('cmmsMachineParts.productionMaintenanceGantt.move.error.recomputeFailed'),
        optimisticLock: message('qcadooView.validate.global.optimisticLock'),
        rejectedHeader: message('qcadooView.gantt.move.rejectedHeader')
    };

    FIXTURE = await loadFixture();

    DEVTOOLS = new DevTools();
    await DEVTOOLS.launch(ARGS.chrome);
    await browserLogin();

    // The initialize request of the rowMapping board is the template of every http: event body.
    const board = await browserOpenBoard(fx('rowMapping').scheduleId, null);

    TEMPLATE = board.request;
    GANTT_PATH = findGanttPath(TEMPLATE.components);

    assert.ok(GANTT_PATH, 'the initialize request holds a component with headerParameters');

    const found = findGanttContent(board.response);

    assert.ok(found, 'the initialize answer holds the gantt content');
    assert.equal(found.path, GANTT_PATH, 'the initialize answer renders rows and items at the gantt path');
    assert.ok(Array.isArray(found.content.rows) && Array.isArray(found.content.items));
}, { timeout: CASE_TIMEOUT_MS });

/**
 * Stops the isolation watch, then stops Chrome, sends SIGKILL to every psql child still running and removes every
 * temporary directory once Chrome is stopped. Each step, and each child and directory within a step, runs on its own,
 * whatever the earlier ones did. From its first call on, spawnPsql starts no psql process and makeTempDir creates no
 * directory, so DevTools.launch starts no Chrome and HeldTransaction.open rejects. Runs once: later calls
 * return the promise of the first call. Resolves with the errors of the failed steps, empty when every step
 * succeeded; never rejects.
 */
function releaseResources() {
    if (RELEASE === null) {
        stopIsolationWatch();

        RELEASE = (async () => {
            const errors = [];

            if (DEVTOOLS) {
                try {
                    await DEVTOOLS.close();
                } catch (error) {
                    errors.push(new Error(`stopping Chrome failed: ${error.message}`));
                }
            }

            for (const child of PSQL_CHILDREN) {
                try {
                    child.kill('SIGKILL');
                } catch (error) {
                    errors.push(new Error(`sending SIGKILL to psql (pid ${child.pid}) failed: ${error.message}`));
                }
            }

            for (const directory of TEMP_DIRS) {
                try {
                    fs.rmSync(directory, { recursive: true, force: true });
                } catch (error) {
                    errors.push(new Error(`removing ${directory} failed: ${error.message}`));
                }
            }

            return errors;
        })();
    }

    return RELEASE;
}

// Fails every case once the isolation watch has found an intrusion.
beforeEach(() => {
    if (ISOLATION_ERROR !== null) {
        throw ISOLATION_ERROR;
    }
});

// Stops the isolation watch, Chrome, psql and the temporary directories, then fails with every cleanup error and the
// intrusion the isolation watch found, each when present.
after(async () => {
    const errors = await releaseResources();
    const isolationError = ISOLATION_ERROR;
    const messages = [];

    if (errors.length > 0) {
        messages.push(`cleanup failed: ${errors.map((error) => error.message).join('; ')}`);
    }
    if (isolationError) {
        messages.push(isolationError.message);
    }

    if (messages.length > 0) {
        throw new AggregateError(isolationError ? errors.concat([isolationError]) : errors, messages.join('; '));
    }
});

// Name of the first termination signal received, or null.
let TERMINATION_SIGNAL = null;

/**
 * Handles SIGINT and SIGTERM: writes the signal to stderr, runs releaseResources, writes each cleanup error to stderr
 * and exits with SIGNAL_EXIT_CODES[signal]. Exits with that status after SIGNAL_CLEANUP_TIMEOUT_MS when the cleanup
 * has not finished. A later signal is written to stderr while the cleanup of the first one runs.
 */
function onTerminationSignal(signal) {
    if (TERMINATION_SIGNAL !== null) {
        process.stderr.write(`acceptance.test.js: ${signal} received while cleaning up after ${TERMINATION_SIGNAL}\n`);
        return;
    }

    TERMINATION_SIGNAL = signal;

    const exitCode = SIGNAL_EXIT_CODES[signal];

    process.stderr.write(`acceptance.test.js: ${signal} received; stopping Chrome and psql and removing temporary `
        + `directories, then exiting with ${exitCode}\n`);

    const deadline = setTimeout(() => {
        process.stderr.write(`acceptance.test.js: cleanup did not finish within ${SIGNAL_CLEANUP_TIMEOUT_MS} ms\n`);
        process.exit(exitCode);
    }, SIGNAL_CLEANUP_TIMEOUT_MS);

    releaseResources().then((errors) => {
        for (const error of errors) {
            process.stderr.write(`acceptance.test.js: cleanup failed: ${error.message}\n`);
        }
    }, (error) => {
        process.stderr.write(`acceptance.test.js: cleanup failed: ${error.message}\n`);
    }).finally(() => {
        clearTimeout(deadline);
        process.exit(exitCode);
    });
}

process.on('SIGINT', onTerminationSignal);
process.on('SIGTERM', onTerminationSignal);

// ---------------------------------------------------------------------------------------------------------------
// Shared case flows
// ---------------------------------------------------------------------------------------------------------------

async function httpLogin() {
    const session = new HttpSession(ARGS.baseUrl);

    await session.login(ARGS.user, ARGS.password);

    return session;
}

/**
 * Drags a bar of the case's board in the browser to a rejected target and asserts: the moveResult is rejected with the
 * expected message, the bar returns to its pre-drag left/top, the tooltip shows the rejection header and message, no
 * refresh is sent, and every checksum is unchanged. afterRender runs once the board is rendered, before the checksum
 * baseline.
 */
async function browserRejection({ caseName, role, targetRow, targetDate, expectedMessage, afterRender = null }) {
    const fixture = fx(caseName);
    const position = fixture.roles[role];

    await browserLogin();

    const board = await browserOpenBoard(fixture.scheduleId, position.positionId);
    const item = itemById(findGanttContent(board.response).content, position.positionId);
    const preDrag = await requireBar(position.positionId);

    if (afterRender) {
        await afterRender(fixture);
    }

    const baseline = await checksums(fixture.scheduleId);
    const drag = await browserDrag({ item, targetRow, targetDate });

    try {
        assert.equal(drag.moveResult.accepted, false, `the drop is rejected (message: ${drag.moveResult.message})`);
        assert.equal(norm(drag.moveResult.message), norm(expectedMessage), 'the rejection names the expected reason');

        let last = null;

        await waitFor('the bar back at its pre-drag place with the rejection tooltip', async () => {
            last = await requireBar(position.positionId);

            return last.left === preDrag.left && last.top === preDrag.top
                && norm(last.tooltip).includes(norm(expectedMessage))
                && norm(last.tooltip).includes(norm(EXPECTED.rejectedHeader));
        }, {
            timeout: 30000,
            interval: 100,
            detail: () => JSON.stringify({ preDrag: { left: preDrag.left, top: preDrag.top }, last })
        });
    } finally {
        await finishDrag(drag);
    }

    assert.deepStrictEqual(await checksums(fixture.scheduleId), baseline, 'no persisted change');
}

/**
 * Runs one simultaneous-write move of A2 to PMG-B at D1 10:00 against the background transaction of
 * backgroundWrite(writeSql). The move is posted once the background marker exists. The case then waits up to 60 s until
 * a backend in the lock-wait tree of the background backend lists that backend in its blocking pids, and fails at once
 * when the background session ends or the move answers first. It then waits for the end of the background sleep and
 * commits the background transaction. On a failure before the commit, the background transaction is rolled back and
 * the error carries concurrentFailure's report. After the commit, the move answer is awaited with moveAnswers for up to
 * MOVE_ANSWER_TIMEOUT_MS.
 *
 * Asserts the optimistic-lock rejection, that every checksum equals the state after writeSql alone, and that a backend
 * blocked by the background backend was seen.
 */
async function concurrentWriteRun(t, caseName, session, writeSql) {
    const fixture = fx(caseName);
    const board = await initialize(session, fixture.scheduleId);
    const item = itemById(board.content, fixture.roles.A2.positionId);
    const expected = await precomputeChecksums(fixture.scheduleId, writeSql);
    const { transaction: writer, held } = await backgroundWrite(writeSql);
    const moves = [];
    let blocked = null;

    try {
        moves.push(trackSettlement(postMove(session, fixture.scheduleId, board.headerParameters, item, 'PMG-B',
            at(1, '10:00'))));

        blocked = await waitFor(`the move to wait on a lock of the background transaction (pid ${writer.pid})`, async () => {
            if (writer.ended) {
                throw new FatalError('the background transaction ended before the move waited on it');
            }
            if (moves[0].settled) {
                throw new FatalError(`the move finished before it waited on the background transaction: ${moves[0].describe()}`);
            }

            const waiters = (await lockWaitTree(writer.pid)).filter((backend) => backend.blockedBy.includes(writer.pid));

            return waiters.length > 0 ? waiters : null;
        }, { timeout: 60000, interval: 100 });

        t.diagnostic(`backends blocked by the background transaction (pid ${writer.pid}): ${JSON.stringify(blocked)}`);

        await valueWithin(held, HELD_ANSWER_TIMEOUT_MS, 'the end of the background sleep');
        await writer.commit();
    } catch (error) {
        throw await concurrentFailure(error, [writer], moves);
    }

    const [content] = await moveAnswers(moves, MOVE_ANSWER_TIMEOUT_MS);

    assert.equal(content.moveResult.accepted, false, `the move is rejected (message: ${content.moveResult.message})`);
    assert.equal(norm(content.moveResult.message), norm(EXPECTED.optimisticLock), 'the rejection is the optimistic lock');
    assert.deepStrictEqual(await checksums(fixture.scheduleId), expected, 'only the background write persisted');
    assert.ok(blocked.some((backend) => backend.blockedBy.includes(writer.pid)),
        `a backend blocked by the background transaction (pid ${writer.pid}) was seen: ${JSON.stringify(blocked)}`);
}

/** Returns the rows of productionscheduling_planordertimecalculation of the schedule. */
async function planOrderTimeCalculations(scheduleId) {
    return psqlJson(`
SELECT coalesce(json_agg(json_build_object(
           'orderId', c.order_id,
           'line', pl.number,
           'from', to_char(c.effectivedatefrom, 'YYYY-MM-DD HH24:MI:SS'),
           'to', to_char(c.effectivedateto, 'YYYY-MM-DD HH24:MI:SS')) ORDER BY c.id), '[]')
FROM public.productionscheduling_planordertimecalculation c
LEFT JOIN public.productionlines_productionline pl ON pl.id = c.productionline_id
WHERE c.productionlineschedule_id = ${Number(scheduleId)};
`);
}

// ---------------------------------------------------------------------------------------------------------------
// Acceptance target
// ---------------------------------------------------------------------------------------------------------------

// Comment of the acceptance database, set by run-acceptance.sh with COMMENT ON DATABASE.
const ACCEPTANCE_DATABASE_MARKER = 'qcadoo-acceptance:productionMaintenanceGantt';

// { database, serverPort, pid } of the verified acceptance database, its server port and the pid of the process
// listening on the --base-url port, set by verifyAcceptanceTarget.
let ACCEPTANCE_TARGET = null;

// Socket states of /proc/net/tcp and /proc/net/tcp6.
const TCP_ESTABLISHED = '01';
const TCP_LISTEN = '0A';

// Errors of reading /proc/<pid>/fd and its links when the process or descriptor is gone or belongs to another user.
const PROC_ENTRY_UNAVAILABLE = new Set(['ENOENT', 'ESRCH', 'EACCES', 'EPERM']);

/**
 * Decodes a /proc/net/tcp endpoint 'HEX:PORT' into { bytes, port }. The address hex is a sequence of 32-bit words,
 * each printed in host byte order; the port is plain hex.
 */
function decodeProcEndpoint(text) {
    const match = /^((?:[0-9A-F]{8}){1,4}):([0-9A-F]{4})$/i.exec(text);

    if (!match || (match[1].length !== 8 && match[1].length !== 32)) {
        throw new Error(`not a /proc/net/tcp endpoint: ${text}`);
    }

    const bytes = [];

    for (let offset = 0; offset < match[1].length; offset += 8) {
        const word = [];

        for (let digit = 0; digit < 8; digit += 2) {
            word.push(parseInt(match[1].slice(offset + digit, offset + digit + 2), 16));
        }
        if (os.endianness() === 'LE') {
            word.reverse();
        }

        bytes.push(...word);
    }

    return { bytes, port: parseInt(match[2], 16) };
}

/**
 * Returns the TCP sockets of /proc/net/tcp and /proc/net/tcp6 (skipped when absent) as
 * { localBytes, localPort, remoteBytes, remotePort, state, inode }; state is the two-digit hex state, inode a string.
 */
function readTcpSockets() {
    const sockets = [];

    for (const file of ['/proc/net/tcp', '/proc/net/tcp6']) {
        let text;

        try {
            text = fs.readFileSync(file, 'utf8');
        } catch (error) {
            if (error.code === 'ENOENT') {
                continue;
            }

            throw error;
        }

        for (const line of text.split('\n').slice(1)) {
            const fields = line.trim().split(/\s+/);

            if (fields.length < 10) {
                continue;
            }

            const local = decodeProcEndpoint(fields[1]);
            const remote = decodeProcEndpoint(fields[2]);

            sockets.push({
                localBytes: local.bytes,
                localPort: local.port,
                remoteBytes: remote.bytes,
                remotePort: remote.port,
                state: fields[3].toUpperCase(),
                inode: fields[9]
            });
        }
    }

    return sockets;
}

/**
 * Returns the text of an address: the dotted quad of 4 bytes or of an IPv4-mapped IPv6 address, '::1' for the IPv6
 * loopback address, and null for any other address.
 */
function addressText(bytes) {
    if (bytes.length === 4) {
        return bytes.join('.');
    }
    if (bytes.length === 16) {
        if (bytes.slice(0, 10).every((byte) => byte === 0) && bytes[10] === 0xff && bytes[11] === 0xff) {
            return bytes.slice(12).join('.');
        }
        if (bytes.slice(0, 15).every((byte) => byte === 0) && bytes[15] === 1) {
            return '::1';
        }
    }

    return null;
}

/** Returns whether the address is in 127.0.0.0/8, is ::1, or is an IPv4-mapped IPv6 address in 127.0.0.0/8. */
function isLoopbackBytes(bytes) {
    if (bytes.length === 4) {
        return bytes[0] === 127;
    }
    if (bytes.length === 16 && bytes.slice(0, 10).every((byte) => byte === 0)) {
        return (bytes.slice(10, 15).every((byte) => byte === 0) && bytes[15] === 1)
            || (bytes[10] === 0xff && bytes[11] === 0xff && bytes[12] === 127);
    }

    return false;
}

/**
 * Returns the socket inodes of the descriptors of process pid, or null when its descriptor directory is gone or not
 * readable. Descriptors that close while they are read are left out.
 */
function processSocketInodes(pid) {
    const directory = `/proc/${pid}/fd`;
    let descriptors;

    try {
        descriptors = fs.readdirSync(directory);
    } catch (error) {
        if (PROC_ENTRY_UNAVAILABLE.has(error.code)) {
            return null;
        }

        throw error;
    }

    const inodes = [];

    for (const descriptor of descriptors) {
        let target;

        try {
            target = fs.readlinkSync(path.join(directory, descriptor));
        } catch (error) {
            if (PROC_ENTRY_UNAVAILABLE.has(error.code)) {
                continue;
            }

            throw error;
        }

        const match = /^socket:\[(\d+)\]$/.exec(target);

        if (match) {
            inodes.push(match[1]);
        }
    }

    return inodes;
}

/**
 * Reads the TCP sockets and the socket inodes of every process. Returns { sockets, inodesByPid }: sockets as
 * readTcpSockets gives them, and inodesByPid mapping the pid of each process with a readable descriptor directory to
 * the Set of its socket inodes.
 */
function readSocketOwnership() {
    const sockets = readTcpSockets();
    const inodesByPid = new Map();

    for (const entry of fs.readdirSync('/proc')) {
        if (!/^\d+$/.test(entry)) {
            continue;
        }

        const inodes = processSocketInodes(entry);

        if (inodes !== null) {
            inodesByPid.set(Number(entry), new Set(inodes));
        }
    }

    return { sockets, inodesByPid };
}

/** Returns the pids of the processes of the ownership snapshot that hold the socket inode. */
function socketHolders(ownership, inode) {
    const holders = [];

    for (const [pid, inodes] of ownership.inodesByPid) {
        if (inodes.has(inode)) {
            holders.push(pid);
        }
    }

    return holders;
}

/**
 * Returns { pid, inodes } of the one process that holds every socket listening on the port, on any address. Throws a
 * FatalError when no socket listens on the port, when a listening socket has no readable holder, or when more than
 * one process holds the listening sockets.
 */
function listeningProcess(ownership, port) {
    const listening = ownership.sockets.filter((socket) => socket.state === TCP_LISTEN && socket.localPort === port);

    if (listening.length === 0) {
        throw new FatalError(`no process listens on port ${port}`);
    }

    const owners = new Set();

    for (const socket of listening) {
        const holders = socketHolders(ownership, socket.inode);

        if (holders.length === 0) {
            throw new FatalError(`the process of a socket listening on port ${port} cannot be identified`);
        }

        holders.forEach((pid) => owners.add(pid));
    }

    if (owners.size > 1) {
        throw new FatalError(`refusing to run: ${owners.size} processes listen on port ${port} `
            + `(${[...owners].sort((left, right) => left - right).join(', ')})`);
    }

    const pid = [...owners][0];

    return { pid, inodes: ownership.inodesByPid.get(pid) };
}

/** Returns whether a process named postgres or postmaster holds a socket listening on the port. */
function isPostgresqlPort(ownership, port) {
    return ownership.sockets
        .filter((socket) => socket.state === TCP_LISTEN && socket.localPort === port)
        .some((socket) => socketHolders(ownership, socket.inode).some((pid) => {
            let name;

            try {
                name = fs.readFileSync(`/proc/${pid}/comm`, 'utf8').trim();
            } catch (error) {
                return false;
            }

            return name === 'postgres' || name === 'postmaster';
        }));
}

/**
 * Returns the established TCP connections of the process ({ pid, inodes } as listeningProcess gives it) as
 * { sockets, unverifiable, foreignServerPorts }:
 * - sockets: { address, port } of each connection to serverPort whose remote end is a loopback address, where address
 *   is the local address as addressText gives it;
 * - unverifiable: the number of connections to serverPort whose remote end is not a loopback address or whose local
 *   address has no text;
 * - foreignServerPorts: the other ports of loopback connections that a PostgreSQL server listens on.
 */
function processConnections(ownership, application, serverPort) {
    const sockets = [];
    const foreignServerPorts = new Set();
    let unverifiable = 0;

    for (const socket of ownership.sockets) {
        if (socket.state !== TCP_ESTABLISHED || !application.inodes.has(socket.inode)) {
            continue;
        }

        const loopback = isLoopbackBytes(socket.remoteBytes);

        if (socket.remotePort === serverPort) {
            const address = addressText(socket.localBytes);

            if (loopback && address !== null) {
                sockets.push({ address, port: socket.localPort });
            } else {
                unverifiable++;
            }
        } else if (loopback && isPostgresqlPort(ownership, socket.remotePort)) {
            foreignServerPorts.add(socket.remotePort);
        }
    }

    return { sockets, unverifiable, foreignServerPorts: [...foreignServerPorts].sort((left, right) => left - right) };
}

/**
 * Returns the SQL VALUES rows `('address', port), ...` of the sockets. Throws when an address holds a character other
 * than [0-9a-f.:] or a port is not an integer from 1 to 65535.
 */
function socketValues(sockets) {
    return sockets.map(({ address, port }) => {
        if (!/^[0-9a-f.:]+$/.test(address) || !Number.isInteger(port) || port < 1 || port > 65535) {
            throw new Error(`not a socket address: ${address} port ${port}`);
        }

        return `('${address}', ${port})`;
    }).join(', ');
}

/**
 * Verifies the acceptance target and sets ACCEPTANCE_TARGET. Only reads. Throws, before any request or write:
 * - when psql reached the server over TCP on an address that is not a loopback address: host(inet_server_addr()),
 *   without a leading ::ffff:, is neither null (a Unix-socket connection) nor accepted by isLoopbackAddress;
 * - when the database of --db-uri does not carry the comment ACCEPTANCE_DATABASE_MARKER;
 * - at once, when no process, or more than one process, listens on the --base-url port, when that process holds a
 *   connection to the server port that is not a loopback connection, when it holds a loopback connection to another
 *   PostgreSQL server, or when one of its connections to the server belongs to another database;
 * - when, within 30 s, that process does not hold at least one connection to the server with every connection to the
 *   server belonging to that database.
 */
async function verifyAcceptanceTarget() {
    const target = await psqlJson(`
SELECT json_build_object('database', current_database(),
                         'marker', shobj_description(d.oid, 'pg_database'),
                         'port', current_setting('port')::integer,
                         'serverAddress', host(inet_server_addr()))
FROM pg_database d
WHERE d.datname = current_database();
`, { app: 'target' });

    if (target.serverAddress !== null && !isLoopbackAddress(String(target.serverAddress).replace(/^::ffff:/i, ''))) {
        throw new Error(`refusing to run: psql reached the PostgreSQL server at ${target.serverAddress}, not a loopback `
            + 'server');
    }
    if (target.marker !== ACCEPTANCE_DATABASE_MARKER) {
        throw new Error(`refusing to run: database ${JSON.stringify(target.database)} does not carry the comment `
            + ACCEPTANCE_DATABASE_MARKER);
    }

    let lastSockets = null;
    let lastRows = null;
    let verifiedPid = null;

    await waitFor(`the application at ${ARGS.baseUrl} to hold connections only to database ${target.database}`,
        async () => {
            const ownership = readSocketOwnership();
            const application = listeningProcess(ownership, ARGS.httpPort);
            const connections = processConnections(ownership, application, target.port);
            const sockets = connections.sockets;

            lastSockets = sockets.length;
            lastRows = null;

            if (connections.foreignServerPorts.length > 0) {
                throw new FatalError(`refusing to run: the application at ${ARGS.baseUrl} is also connected to the `
                    + `PostgreSQL server on port(s) ${connections.foreignServerPorts.join(', ')}`);
            }
            if (connections.unverifiable > 0) {
                throw new FatalError(`refusing to run: the application at ${ARGS.baseUrl} holds `
                    + `${connections.unverifiable} connection(s) to port ${target.port} `
                    + 'that are not loopback connections');
            }
            if (sockets.length === 0) {
                return false;
            }

            const rows = await psqlJson(`
WITH application_sockets (address, port) AS (VALUES ${socketValues(sockets)})
SELECT coalesce(json_agg(json_build_object('address', a.address, 'port', a.port, 'database', s.datname)), '[]')
FROM application_sockets a
LEFT JOIN pg_stat_activity s ON s.backend_type = 'client backend'
                            AND s.client_port = a.port
                            AND regexp_replace(host(s.client_addr), '^::ffff:', '') = a.address;
`, { app: 'target' });

            lastRows = rows;

            const foreign = [...new Set(rows
                .map((row) => row.database)
                .filter((database) => database !== null && database !== target.database))];

            if (foreign.length > 0) {
                throw new FatalError(`refusing to run: the application at ${ARGS.baseUrl} is connected to database(s) `
                    + `${foreign.map((database) => JSON.stringify(database)).join(', ')} instead of `
                    + JSON.stringify(target.database));
            }

            if (rows.length === 0 || !rows.every((row) => row.database === target.database)) {
                return false;
            }

            verifiedPid = application.pid;

            return true;
        }, {
            timeout: 30000,
            interval: 250,
            detail: () => `${lastSockets} application socket(s) to port ${target.port}`
                + (lastRows === null ? ''
                    : `, ${lastRows.filter((row) => row.database === target.database).length} with a backend of `
                    + `${JSON.stringify(target.database)}, ${lastRows.filter((row) => row.database === null).length} `
                    + 'without a client backend')
        });

    ACCEPTANCE_TARGET = { database: target.database, serverPort: target.port, pid: verifiedPid };
}

/**
 * Selects into a materialized set the client backends of the acceptance database that are idle when observed and
 * whose client address and port belong to a loopback connection of the verified process, then terminates every
 * selected pid, including one whose backend has become active since it was selected, and waits until the terminated
 * backends have exited; each reports its pending table statistics as it exits. Throws when verifyAcceptanceTarget has
 * not succeeded, or when the one process listening on the --base-url port is not the verified process.
 */
async function flushApplicationBackendStats() {
    if (ACCEPTANCE_TARGET === null) {
        throw new Error('flushApplicationBackendStats requires a verified acceptance target');
    }

    const ownership = readSocketOwnership();
    const application = listeningProcess(ownership, ARGS.httpPort);

    if (application.pid !== ACCEPTANCE_TARGET.pid) {
        throw new Error(`the process listening on port ${ARGS.httpPort} is ${application.pid}, `
            + `not the verified process ${ACCEPTANCE_TARGET.pid}`);
    }

    const sockets = processConnections(ownership, application, ACCEPTANCE_TARGET.serverPort).sockets;

    if (sockets.length === 0) {
        return;
    }

    const terminated = await psqlJson(`
WITH application_sockets (address, port) AS (VALUES ${socketValues(sockets)}),
targets AS MATERIALIZED (
    SELECT s.pid
    FROM pg_stat_activity s
    JOIN application_sockets a ON a.address = regexp_replace(host(s.client_addr), '^::ffff:', '')
                              AND a.port = s.client_port
    WHERE s.datid = (SELECT d.oid FROM pg_database d WHERE d.datname = current_database())
      AND s.backend_type = 'client backend'
      AND s.state = 'idle'
      AND s.pid <> pg_backend_pid()
)
SELECT coalesce(json_agg(t.pid) FILTER (WHERE pg_terminate_backend(t.pid)), '[]')
FROM targets t;
`, { app: 'stats' });

    if (terminated.length === 0) {
        return;
    }

    await waitFor('the terminated application backends to exit', async () => (await psqlInteger(`
SELECT count(*) FROM pg_stat_activity WHERE pid IN (${terminated.map(Number).join(', ')});
`, { app: 'stats', timeout: PSQL_PROBE_TIMEOUT_MS })) === 0, { timeout: 30000, interval: 100 });
}

/**
 * Reads n_tup_ins of productionscheduling_planordertimecalculation in a fresh psql session with the deadline
 * PSQL_PROBE_TIMEOUT_MS.
 */
async function planOrderTimeCalculationInserts() {
    return psqlInteger(`
SELECT coalesce((SELECT n_tup_ins
                 FROM pg_stat_user_tables
                 WHERE schemaname = 'public' AND relname = 'productionscheduling_planordertimecalculation'), -1);
`, { app: 'stats', timeout: PSQL_PROBE_TIMEOUT_MS });
}

// Stored positions after the accepted A2 -> PMG-B D1 10:00 move (acceptedCrossRowMove, concurrentMoves M1).
function crossRowMoveAt1000() {
    return {
        A1: '=',
        A2: ['PMG-B', at(1, '10:00'), at(1, '11:00'), 'PMG-N-B-ZB'],
        A3: ['PMG-A', at(1, '07:30'), at(1, '08:30'), 'PMG-N-A-BB'],
        A4: ['PMG-A', at(1, '09:00'), at(1, '10:00'), 'PMG-N-A-BB'],
        A5: ['PMG-A', at(1, '10:30'), at(1, '11:30'), 'PMG-N-A-BA'],
        B1: '=',
        B2: '=',
        B3: '=',
        B4: ['PMG-B', at(1, '11:30'), at(1, '12:30'), 'PMG-N-B-BB'],
        B5: ['PMG-B', at(1, '13:00'), at(1, '14:00'), 'PMG-N-B-BB']
    };
}

// Stored positions after the accepted A2 -> PMG-B D1 09:30 move (concurrentMoves M2).
function crossRowMoveAt0930() {
    return Object.assign(crossRowMoveAt1000(), {
        A2: ['PMG-B', at(1, '09:30'), at(1, '10:30'), 'PMG-N-B-ZB'],
        B4: ['PMG-B', at(1, '11:00'), at(1, '12:00'), 'PMG-N-B-BB'],
        B5: ['PMG-B', at(1, '12:30'), at(1, '13:30'), 'PMG-N-B-BB']
    });
}

// ---------------------------------------------------------------------------------------------------------------
// Cases
// ---------------------------------------------------------------------------------------------------------------

test('http:rowMapping', { timeout: CASE_TIMEOUT_MS }, async () => {
    const fixture = fx('rowMapping');
    const session = await httpLogin();
    const { content } = await initialize(session, fixture.scheduleId);
    const stored = await positionState('rowMapping');

    // Rows: seed line Line, then PMG-A and PMG-B; no overlaps.
    assert.deepStrictEqual(content.rows, ['Line', 'PMG-A', 'PMG-B']);
    assert.deepStrictEqual(content.collisions, []);

    // Every position: one item with the position id on the row of its line, named by its order, with its stored times.
    for (const role of POSITION_ROLES) {
        const position = fixture.roles[role];
        const item = itemById(content, position.positionId);

        assert.deepStrictEqual(
            { row: item.row, name: item.info.name, dateFrom: item.info.dateFrom, dateTo: item.info.dateTo },
            { row: stored[role].line, name: position.orderNumber, dateFrom: stored[role].start, dateTo: stored[role].end },
            `item of position ${role}`);
    }

    // PMG-EV-SHUTDOWN: one item without id on PMG-B, DD + 2 days 07:00-09:00.
    const shutdownItems = content.items.filter((item) => item.info && item.info.name === 'PMG-EV-SHUTDOWN');

    assert.equal(shutdownItems.length, 1, 'PMG-EV-SHUTDOWN is rendered once');
    assert.deepStrictEqual(
        {
            row: shutdownItems[0].row,
            idIsNull: shutdownItems[0].id == null,
            dateFrom: shutdownItems[0].info.dateFrom,
            dateTo: shutdownItems[0].info.dateTo
        },
        { row: 'PMG-B', idIsNull: true, dateFrom: at(2, '07:00'), dateTo: at(2, '09:00') });

    // PMG-EV-DIVISION: one item without id on each line of division PMG-D, DD + 3 days 08:00-10:00.
    const divisionItems = content.items.filter((item) => item.info && item.info.name === 'PMG-EV-DIVISION');

    assert.deepStrictEqual(divisionItems.map((item) => ({
        row: item.row,
        idIsNull: item.id == null,
        dateFrom: item.info.dateFrom,
        dateTo: item.info.dateTo
    })).sort((a, b) => a.row.localeCompare(b.row)), [
        { row: 'PMG-A', idIsNull: true, dateFrom: at(3, '08:00'), dateTo: at(3, '10:00') },
        { row: 'PMG-B', idIsNull: true, dateFrom: at(3, '08:00'), dateTo: at(3, '10:00') }
    ]);

    // No item on row Line, and no PMG- item other than this schedule's orders and the PMG-EV- events.
    assert.deepStrictEqual(content.items.filter((item) => item.row === 'Line'), []);

    const orderNumbers = new Set(POSITION_ROLES.map((role) => fixture.roles[role].orderNumber));
    const foreignItems = content.items.filter((item) => {
        const name = (item.info && item.info.name) || '';

        return name.startsWith('PMG-') && !orderNumbers.has(name) && !name.startsWith('PMG-EV-');
    });

    assert.deepStrictEqual(foreignItems.map((item) => item.info.name), []);
});

test('browser:acceptedCrossRowMove', { timeout: CASE_TIMEOUT_MS }, async () => {
    const fixture = fx('acceptedCrossRowMove');
    const a2 = fixture.roles.A2;

    await browserLogin();

    const board = await browserOpenBoard(fixture.scheduleId, a2.positionId);
    const item = itemById(findGanttContent(board.response).content, a2.positionId);
    const beforeMove = await positionState('acceptedCrossRowMove');
    const drag = await browserDrag({ item, targetRow: 'PMG-B', targetDate: at(1, '10:00') });

    try {
        assert.equal(drag.event.component, GANTT_PATH, 'the browser targets the captured gantt path');
        assert.equal(drag.moveResult.accepted, true, `the drop is accepted (message: ${drag.moveResult.message})`);
        assert.ok(Array.isArray(drag.content.rows) && Array.isArray(drag.content.items),
            'the moveItem answer carries the refreshed board');

        // The redrawn A2 bar sits in the row of PMG-B.
        let last = null;

        await waitFor('the A2 bar redrawn in row PMG-B', async () => {
            last = await readBar(a2.positionId);

            return last !== null && last.rowIndex === last.rowNames.indexOf('PMG-B')
                && !/\bganttItemDragging\b/.test(last.className);
        }, { timeout: 30000, interval: 100, detail: () => JSON.stringify(last) });
    } finally {
        await finishDrag(drag);
    }

    const afterMove = await positionState('acceptedCrossRowMove');

    // A3 is the first downstream position on the origin row, B4 on the destination row.
    assertPositions(afterMove, beforeMove, crossRowMoveAt1000());

    // A3, A4, A5, B4 and B5 have plan order time calculations on their line with their new times.
    const calculations = await planOrderTimeCalculations(fixture.scheduleId);

    for (const role of ['A3', 'A4', 'A5', 'B4', 'B5']) {
        const wanted = {
            orderId: fixture.roles[role].orderId,
            line: afterMove[role].line,
            from: afterMove[role].start,
            to: afterMove[role].end
        };

        assert.ok(calculations.some((calculation) => calculation.orderId === wanted.orderId
            && calculation.line === wanted.line && calculation.from === wanted.from && calculation.to === wanted.to),
        `plan order time calculation of ${role} ${JSON.stringify(wanted)} in ${JSON.stringify(calculations)}`);
    }
});

test('http:acceptedLaterSameRowMove', { timeout: CASE_TIMEOUT_MS }, async () => {
    const fixture = fx('acceptedLaterSameRowMove');
    const a2 = fixture.roles.A2;
    const session = await httpLogin();
    const board = await initialize(session, fixture.scheduleId);
    const item = itemById(board.content, a2.positionId);
    const beforeMove = await positionState('acceptedLaterSameRowMove');
    const content = await postMove(session, fixture.scheduleId, board.headerParameters, item, 'PMG-A', at(1, '09:30'));

    assert.equal(content.moveResult.accepted, true, `the move is accepted (message: ${content.moveResult.message})`);
    assert.ok(Array.isArray(content.rows), 'the answer carries rows');
    assert.ok(Array.isArray(content.items), 'the answer carries items');
    assert.ok(Array.isArray(content.collisions), 'the answer carries collisions');

    const moved = itemById(content, a2.positionId);

    assert.deepStrictEqual({ row: moved.row, dateFrom: moved.info.dateFrom }, { row: 'PMG-A', dateFrom: at(1, '09:30') });

    // A3 is recomputed before the anchor: 07:30-08:30; A4 is the first position after the anchor.
    assertPositions(await positionState('acceptedLaterSameRowMove'), beforeMove, {
        A1: '=',
        A2: ['PMG-A', at(1, '09:30'), at(1, '10:30'), 'PMG-N-A-BB'],
        A3: ['PMG-A', at(1, '07:30'), at(1, '08:30'), 'PMG-N-A-BB'],
        A4: ['PMG-A', at(1, '11:00'), at(1, '12:00'), 'PMG-N-A-BB'],
        A5: ['PMG-A', at(1, '12:30'), at(1, '13:30'), 'PMG-N-A-BA'],
        B1: '=',
        B2: '=',
        B3: '=',
        B4: '=',
        B5: '='
    });
});

test('browser:rejectRouting', { timeout: CASE_TIMEOUT_MS }, async () => {
    // A5 (PMG-T-A-ONLY) -> PMG-B at D1 12:00.
    await browserRejection({
        caseName: 'rejectRouting',
        role: 'A5',
        targetRow: 'PMG-B',
        targetDate: at(1, '12:00'),
        expectedMessage: EXPECTED.routing
    });
});

test('browser:rejectShutdown', { timeout: CASE_TIMEOUT_MS }, async () => {
    // A2 -> PMG-B at DD + 2 days 07:30, inside PMG-EV-SHUTDOWN.
    await browserRejection({
        caseName: 'rejectShutdown',
        role: 'A2',
        targetRow: 'PMG-B',
        targetDate: at(2, '07:30'),
        expectedMessage: EXPECTED.shutdownWindow
    });
});

test('browser:rejectCalendar', { timeout: CASE_TIMEOUT_MS }, async () => {
    // A2 -> PMG-B at D1 16:00, after the 6:00-14:00 shift of PMG-B.
    await browserRejection({
        caseName: 'rejectCalendar',
        role: 'A2',
        targetRow: 'PMG-B',
        targetDate: at(1, '16:00'),
        expectedMessage: EXPECTED.outsideWorkingHours
    });
});

test('browser:rejectStaleBoard', { timeout: CASE_TIMEOUT_MS }, async () => {
    // After the board renders, A2's order becomes the SPARE order; then A2 -> PMG-B at D1 10:00.
    await browserRejection({
        caseName: 'rejectStaleBoard',
        role: 'A2',
        targetRow: 'PMG-B',
        targetDate: at(1, '10:00'),
        expectedMessage: EXPECTED.optimisticLock,
        afterRender: async (fixture) => {
            await psql(`UPDATE public.orders_productionlinescheduleposition SET order_id = ${Number(fixture.spareOrderId)} `
                + `WHERE id = ${Number(fixture.roles.A2.positionId)};`, { app: 'edit' });
        }
    });
});

test('http:concurrentMovedPositionWrite', { timeout: CASE_TIMEOUT_MS }, async (t) => {
    const fixture = fx('concurrentMovedPositionWrite');
    const a2 = fixture.roles.A2;
    const session = await httpLogin();
    const beforeCase = await positionState('concurrentMovedPositionWrite');

    // Run 1: the background transaction changes A2's additionaltime.
    await concurrentWriteRun(t, 'concurrentMovedPositionWrite', session,
        `UPDATE public.orders_productionlinescheduleposition SET additionaltime = additionaltime + 15 WHERE id = ${Number(a2.positionId)}`);

    // Run 2: the background transaction changes A2's order to the SPARE order.
    await concurrentWriteRun(t, 'concurrentMovedPositionWrite', session,
        `UPDATE public.orders_productionlinescheduleposition SET order_id = ${Number(fixture.spareOrderId)} WHERE id = ${Number(a2.positionId)}`);

    // A2 holds both background changes with its original line and times.
    const afterCase = await positionState('concurrentMovedPositionWrite');

    assert.deepStrictEqual(afterCase.A2, Object.assign(clone(beforeCase.A2), {
        additionaltime: beforeCase.A2.additionaltime + 15,
        orderNumber: 'PMG-concurrentMovedPositionWrite-SPARE'
    }));
    assert.deepStrictEqual({ line: afterCase.A2.line, start: afterCase.A2.start }, { line: 'PMG-A', start: at(1, '07:30') });
});

test('http:concurrentNeighbourWrite', { timeout: CASE_TIMEOUT_MS }, async (t) => {
    const fixture = fx('concurrentNeighbourWrite');
    const session = await httpLogin();
    const beforeCase = await checksums(fixture.scheduleId);

    // The background transaction changes B5, the last downstream position on PMG-B.
    await concurrentWriteRun(t, 'concurrentNeighbourWrite', session,
        `UPDATE public.orders_productionlinescheduleposition SET additionaltime = additionaltime + 15 WHERE id = ${Number(fixture.roles.B5.positionId)}`);

    assert.deepStrictEqual(psPpsChecksums(await checksums(fixture.scheduleId)), psPpsChecksums(beforeCase),
        'the PS/PPS tables equal the state before the case');
});

test('http:concurrentMembershipChange', { timeout: CASE_TIMEOUT_MS }, async (t) => {
    const fixture = fx('concurrentMembershipChange');
    const b4 = fixture.roles.B4;
    const session = await httpLogin();
    const stored = await positionState('concurrentMembershipChange');
    const replacementId = await psqlInteger('SELECT nextval(\'public.orders_productionlinescheduleposition_id_seq\');');
    const normId = stored.B4.norm === null ? 'NULL' : Number(FIXTURE.norms[stored.B4.norm]);

    // The background transaction deletes B4 and inserts a copy of it with a new id.
    await concurrentWriteRun(t, 'concurrentMembershipChange', session,
        `DELETE FROM public.orders_productionlinescheduleposition WHERE id = ${Number(b4.positionId)};\n`
        + 'INSERT INTO public.orders_productionlinescheduleposition (id, productionlineschedule_id, order_id, '
        + 'productionline_id, linechangeovernorm_id, starttime, endtime, additionaltime) '
        + `VALUES (${replacementId}, ${Number(fixture.scheduleId)}, ${Number(b4.orderId)}, `
        + `${Number(FIXTURE.lines[stored.B4.line])}, ${normId}, TIMESTAMP '${stored.B4.start}', `
        + `TIMESTAMP '${stored.B4.end}', ${Number(stored.B4.additionaltime)})`);

    const counts = await psqlJson(`
SELECT json_build_object(
    'old', (SELECT count(*) FROM public.orders_productionlinescheduleposition WHERE id = ${Number(b4.positionId)}),
    'replacement', (SELECT count(*) FROM public.orders_productionlinescheduleposition WHERE id = ${replacementId}));
`);

    assert.deepStrictEqual(counts, { old: 0, replacement: 1 });
});

test('http:concurrentMoves', { timeout: CASE_TIMEOUT_MS }, async (t) => {
    const fixture = fx('concurrentMoves');
    const a2 = fixture.roles.A2;
    const session = await httpLogin();
    const board = await initialize(session, fixture.scheduleId);
    const item = itemById(board.content, a2.positionId);
    const beforeMoves = await positionState('concurrentMoves');

    // The holder session locks B5 FOR UPDATE and keeps its transaction open.
    const holder = await HeldTransaction.open('holder', 'SELECT id FROM public.orders_productionlinescheduleposition '
        + `WHERE id = ${Number(fixture.roles.B5.positionId)} FOR UPDATE`, 'locked');
    const moves = [];

    try {
        // M1: A2 -> PMG-B at D1 10:00; M2: A2 -> PMG-B at D1 09:30; both sent at once.
        moves.push(trackSettlement(postMove(session, fixture.scheduleId, board.headerParameters, item, 'PMG-B',
            at(1, '10:00'))));
        moves.push(trackSettlement(postMove(session, fixture.scheduleId, board.headerParameters, item, 'PMG-B',
            at(1, '09:30'))));

        // Two distinct backends in the lock-wait tree rooted at the holder, before either move answers.
        const waiters = await waitFor(`two backends in the lock-wait tree of the holder session (pid ${holder.pid})`,
            async () => {
                if (holder.ended) {
                    throw new FatalError('the holder session ended before both moves waited on it');
                }

                const finished = moves.findIndex((move) => move.settled);

                if (finished >= 0) {
                    throw new FatalError(`move M${finished + 1} finished before both moves waited on the holder session: `
                        + moves[finished].describe());
                }

                const tree = await lockWaitTree(holder.pid);

                return tree.length >= 2 ? tree : null;
            }, { timeout: 60000, interval: 250 });

        t.diagnostic(`backends in the lock-wait tree of the holder session (pid ${holder.pid}): ${JSON.stringify(waiters)}`);

        // The holder commits without a change.
        await holder.commit();
    } catch (error) {
        throw await concurrentFailure(error, [holder], moves);
    }

    const results = await moveAnswers(moves, MOVE_ANSWER_TIMEOUT_MS);
    const acceptedIndexes = results.map((content, index) => (content.moveResult.accepted === true ? index : -1))
        .filter((index) => index >= 0);

    assert.equal(acceptedIndexes.length, 1,
        `exactly one move is accepted: ${JSON.stringify(results.map((content) => content.moveResult))}`);

    const winner = acceptedIndexes[0];
    const loser = results[1 - winner];

    assert.equal(loser.moveResult.accepted, false);
    assert.equal(norm(loser.moveResult.message), norm(EXPECTED.optimisticLock), 'the other move gets the optimistic lock');

    t.diagnostic(`accepted move: M${winner + 1}`);

    assertPositions(await positionState('concurrentMoves'), beforeMoves,
        winner === 0 ? crossRowMoveAt1000() : crossRowMoveAt0930());
});

test('http:rollbackAfterPsSideEffect', { timeout: CASE_TIMEOUT_MS }, async () => {
    const fixture = fx('rollbackAfterPsSideEffect');
    const session = await httpLogin();
    const board = await initialize(session, fixture.scheduleId);
    const item = itemById(board.content, fixture.roles.A2.positionId);
    const beforeMove = await checksums(fixture.scheduleId);

    // n_tup_ins baseline after the idle application backends have reported: two reads at least 1 s apart that agree.
    await flushApplicationBackendStats();

    let previousRead = null;
    const baselineInserts = await waitFor('a stable n_tup_ins baseline', async () => {
        const value = await planOrderTimeCalculationInserts();
        const now = Date.now();

        if (previousRead !== null && previousRead.value === value && now - previousRead.time >= 1000) {
            return { value };
        }
        if (previousRead === null || previousRead.value !== value) {
            previousRead = { value, time: now };
        }

        return false;
    }, { timeout: 15000, interval: 250 });

    // A2 -> PMG-B at D1 07:00: B2 is recomputed, then B3 (PMG-T-ZERO) yields no data.
    const content = await postMove(session, fixture.scheduleId, board.headerParameters, item, 'PMG-B', at(1, '07:00'));

    assert.equal(content.moveResult.accepted, false, `the move is rejected (message: ${content.moveResult.message})`);
    assert.equal(norm(content.moveResult.message), norm(EXPECTED.recomputeFailed), 'the rejection is recomputeFailed');
    assert.deepStrictEqual(await checksums(fixture.scheduleId), beforeMove, 'no persisted change');

    // The rolled-back move inserted plan order time calculations: n_tup_ins grows once the application backends report.
    await flushApplicationBackendStats();

    let lastInserts = null;

    await waitFor('n_tup_ins of productionscheduling_planordertimecalculation above the baseline', async () => {
        lastInserts = await planOrderTimeCalculationInserts();

        return lastInserts > baselineInserts.value;
    }, { timeout: 10000, interval: 500, detail: () => `baseline ${baselineInserts.value}, last ${lastInserts}` });
});
