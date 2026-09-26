/*
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.4
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
 *        --db-uri postgresql://<user>:<password>@<host>:<port>/<db> --chrome <path to chrome-headless-shell>
 *        --base-day YYYY-MM-DD
 *
 * http: cases post view events with Node's fetch. browser: cases drive the board in headless Chrome through the
 * DevTools Protocol with real mouse input. Database state is read, and concurrent transactions are run, with psql.
 * Every case works on its own draft schedule PMG-<caseName> of fixture.sql.
 */

'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const childProcess = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// ---------------------------------------------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------------------------------------------

const USAGE = 'usage: node acceptance.test.js --base-url <http://host:port> --user <login> --password <password> '
    + '--db-uri <postgresql://user:password@host:port/db> --chrome <path> --base-day <YYYY-MM-DD>';

const ARGUMENT_NAMES = ['base-url', 'user', 'password', 'db-uri', 'chrome', 'base-day'];

/**
 * Parses `--name value` and `--name=value` arguments. Throws with the usage line when an argument is unknown, a value
 * is missing, or --base-day is not a calendar date in the YYYY-MM-DD form.
 */
function parseArguments(argv) {
    const values = {};

    for (let index = 0; index < argv.length; index++) {
        const argument = argv[index];

        if (!argument.startsWith('--')) {
            throw new Error(`unexpected argument ${argument}\n${USAGE}`);
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
            throw new Error(`unknown argument --${name}\n${USAGE}`);
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
    const dayMillis = day ? Date.UTC(Number(day[1]), Number(day[2]) - 1, Number(day[3])) : NaN;

    if (!day || Number.isNaN(dayMillis) || new Date(dayMillis).toISOString().slice(0, 10) !== values['base-day']) {
        throw new Error(`--base-day must be a calendar date in the YYYY-MM-DD form, got ${values['base-day']}\n${USAGE}`);
    }

    return {
        baseUrl: values['base-url'].replace(/\/+$/, ''),
        user: values.user,
        password: values.password,
        dbUri: values['db-uri'],
        chrome: values.chrome,
        baseDay: values['base-day'],
        baseDayMillis: dayMillis
    };
}

const ARGS = parseArguments(process.argv.slice(2));

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
 * `description`, the last probe error and the text of `detail()`, when `timeout` ms pass first. A FatalError thrown by
 * the probe ends the poll at once.
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
                + (lastError ? `: ${lastError.message}` : '')
                + (detail ? `; last state: ${detail()}` : ''));
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

/** Parses 'YYYY-MM-DD HH:MM:SS' into wall-clock milliseconds, computed with Date.UTC. */
function parseWallClock(text) {
    const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(text);

    if (!match) {
        throw new Error(`not a wall-clock date YYYY-MM-DD HH:MM:SS: ${text}`);
    }

    return Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]), Number(match[4]), Number(match[5]),
        Number(match[6]));
}

/** Formats wall-clock milliseconds as 'YYYY-MM-DD HH:MM:SS' with the getUTC* accessors. */
function formatWallClock(millis) {
    const date = new Date(millis);

    return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())} `
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

// psql processes that have not exited yet.
const PSQL_CHILDREN = new Set();

// Temporary directories removed by the after hook.
const TEMP_DIRS = new Set();

function makeTempDir(prefix) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), `pmg-acceptance-${prefix}-`));

    TEMP_DIRS.add(directory);

    return directory;
}

/** Returns --db-uri with application_name=pmg-acceptance-<app> appended. */
function databaseUri(app) {
    const separator = ARGS.dbUri.includes('?') ? '&' : '?';

    return `${ARGS.dbUri}${separator}application_name=${encodeURIComponent(`pmg-acceptance-${app}`)}`;
}

/** Spawns psql -X -A -t -q -v ON_ERROR_STOP=1 against the acceptance database with application name pmg-acceptance-<app>. */
function spawnPsql(app) {
    const child = childProcess.spawn('psql', ['-X', '-A', '-t', '-q', '-v', 'ON_ERROR_STOP=1', '-d', databaseUri(app)],
        { stdio: ['pipe', 'pipe', 'pipe'] });

    PSQL_CHILDREN.add(child);
    child.on('exit', () => PSQL_CHILDREN.delete(child));

    const output = { stdout: '', stderr: '' };

    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
        output.stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
        output.stderr += chunk;
    });

    const done = new Promise((resolve, reject) => {
        child.on('error', (error) => reject(new Error(`psql (${app}) could not start: ${error.message}`)));
        child.on('close', (code, signal) => {
            if (code === 0) {
                resolve(output.stdout);
            } else {
                reject(new Error(`psql (${app}) exited with ${code === null ? signal : code}: ${output.stderr.trim()}`));
            }
        });
    });

    return { child, done, output };
}

/** Runs the SQL script with psql and returns its standard output. Rejects with psql's stderr on a non-zero exit. */
async function psql(sql, { app = 'main' } = {}) {
    const { child, done } = spawnPsql(app);

    child.stdin.end(sql);

    try {
        return await done;
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

    async request(pathAndQuery, { method = 'GET', headers = {}, body } = {}) {
        const requestHeaders = new Headers(headers);

        requestHeaders.set('Accept-Language', 'en');

        if (this.cookies.size > 0) {
            requestHeaders.set('Cookie', Array.from(this.cookies, ([name, value]) => `${name}=${value}`).join('; '));
        }

        const response = await fetch(this.baseUrl + pathAndQuery, { method, headers: requestHeaders, body, redirect: 'manual' });

        this.updateCookies(response);

        return { status: response.status, headers: response.headers, text: await response.text() };
    }

    csrfHeaders() {
        if (!this.csrf) {
            throw new Error('no CSRF token: log in first');
        }

        return { [this.csrf.header]: this.csrf.token };
    }

    /** Logs in through /j_spring_security_check with the CSRF token of the login page. */
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
            throw new Error(`login as ${user} failed (${response.status}): ${response.text.trim().slice(0, 500)}`);
        }
        if (!this.cookies.has('JSESSIONID') || this.cookies.get('JSESSIONID') === sessionBefore) {
            throw new Error('the session id did not change at login');
        }
    }

    /** Opens the board page of the schedule and takes the CSRF token of that page. */
    async openBoard(scheduleId) {
        const response = await this.request(boardUrl(scheduleId));
        const contentType = response.headers.get('content-type') || '';

        if (response.status !== 200 || !/text\/html/i.test(contentType)) {
            throw new Error(`GET ${BOARD_PATH} answered ${response.status} (${contentType}): `
                + response.text.slice(0, 500));
        }

        this.csrf = parseCsrf(response.text);
    }

    /** Posts a view event body to the board and returns the parsed JSON answer. */
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
            throw new Error(`event ${body.event.name} was redirected to the login page: ${location}`);
        }
        if (response.status !== 200) {
            throw new Error(`event ${body.event.name} answered ${response.status}: ${text.slice(0, 1000)}`);
        }
        if (text === 'sessionExpired') {
            throw new Error(`event ${body.event.name} answered sessionExpired`);
        }
        if (text.startsWith('<![CDATA[ERROR PAGE:')) {
            throw new Error(`event ${body.event.name} answered an error page: ${text.slice(0, 2000)}`);
        }

        try {
            return JSON.parse(text);
        } catch (error) {
            throw new Error(`event ${body.event.name} answered no JSON (${error.message}): ${text.slice(0, 1000)}`);
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

/**
 * Starts a psql session (application pmg-acceptance-bg) that writes writeSql in a transaction, creates a marker file,
 * sleeps 3 s and commits. Returns {markerPath, done}; done resolves when psql exits 0.
 */
function backgroundWrite(writeSql) {
    const markerPath = path.join(makeTempDir('bg'), 'written');
    const { child, done } = spawnPsql('bg');

    child.stdin.end(`BEGIN;\n${writeSql};\n\\! touch '${markerPath}'\nSELECT pg_sleep(3);\nCOMMIT;\n`);
    done.catch(() => undefined);

    return { markerPath, done };
}

/** Waits until the marker file exists, failing early when the psql session ends first. */
async function waitForMarker(markerPath, done, description) {
    let finished = null;

    done.then(() => {
        finished = 'exited';
    }, (error) => {
        finished = error;
    });

    await waitFor(description, () => {
        if (fs.existsSync(markerPath)) {
            return true;
        }
        if (finished instanceof Error) {
            throw new FatalError(finished.message);
        }
        if (finished) {
            throw new FatalError('psql exited before creating the marker');
        }

        return false;
    }, { timeout: 30000, interval: 25 });
}

/** Counts client backends of the application (any application name other than pmg-acceptance*) waiting on a lock. */
async function appLockWaiters() {
    return psqlInteger(`
SELECT count(*)
FROM pg_stat_activity
WHERE datname = current_database()
  AND backend_type = 'client backend'
  AND wait_event_type = 'Lock'
  AND coalesce(application_name, '') NOT LIKE 'pmg-acceptance%';
`, { app: 'monitor' });
}

/** Returns pid, application name, state, wait event and query text of every backend of the acceptance database. */
async function activityDump() {
    return psqlJson(`
SELECT coalesce(json_agg(json_build_object(
           'pid', pid, 'application', application_name, 'state', state, 'waitEventType', wait_event_type,
           'waitEvent', wait_event, 'query', left(query, 300)) ORDER BY pid), '[]')
FROM pg_stat_activity
WHERE datname = current_database();
`, { app: 'monitor' });
}

// ---------------------------------------------------------------------------------------------------------------
// DevTools Protocol client
// ---------------------------------------------------------------------------------------------------------------

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
    }

    /** Starts Chrome, connects to its DevTools endpoint and attaches to a new page with Page, Runtime and Network enabled. */
    async launch(chromePath) {
        const profileDir = makeTempDir('chrome-profile');
        const chromeArgs = ['--headless', '--remote-debugging-port=0', `--user-data-dir=${profileDir}`, '--no-first-run',
            '--no-default-browser-check', '--lang=en-US', '--window-size=1600,1000', '--disable-dev-shm-usage'];

        if (process.getuid && process.getuid() === 0) {
            chromeArgs.push('--no-sandbox');
        }

        chromeArgs.push('about:blank');

        this.process = childProcess.spawn(chromePath, chromeArgs, { stdio: ['ignore', 'ignore', 'pipe'] });
        this.process.on('exit', () => {
            this.exited = true;
        });
        this.process.stderr.setEncoding('utf8');

        const endpoint = await new Promise((resolve, reject) => {
            let stderr = '';
            let listening = false;
            const timer = setTimeout(() => reject(new Error(`Chrome printed no DevTools endpoint within 30 s: ${stderr}`)),
                30000);

            this.process.stderr.on('data', (chunk) => {
                this.stderrTail = (this.stderrTail + chunk).slice(-4000);

                if (listening) {
                    return;
                }

                stderr += chunk;

                const match = /DevTools listening on (ws:\/\/\S+)/.exec(stderr);

                if (match) {
                    listening = true;
                    clearTimeout(timer);
                    resolve(match[1]);
                }
            });
            this.process.on('error', (error) => {
                clearTimeout(timer);
                reject(new Error(`Chrome ${chromePath} could not start: ${error.message}`));
            });
            this.process.on('exit', (code, signal) => {
                clearTimeout(timer);
                reject(new Error(`Chrome exited with ${code === null ? signal : code} before listening: ${stderr}`));
            });
        });

        this.socket = new WebSocket(endpoint);

        await new Promise((resolve, reject) => {
            this.socket.addEventListener('open', () => resolve(), { once: true });
            this.socket.addEventListener('error', () => reject(new Error(`cannot connect to ${endpoint}`)), { once: true });
        });

        this.socket.addEventListener('message', (event) => this.onMessage(JSON.parse(String(event.data))));
        this.socket.addEventListener('close', () => {
            for (const pending of this.pending.values()) {
                pending.reject(new Error(`DevTools socket closed during ${pending.method}`));
            }

            this.pending.clear();
        });

        const { targetId } = await this.send('Target.createTarget', { url: 'about:blank' }, null);
        const { sessionId } = await this.send('Target.attachToTarget', { targetId, flatten: true }, null);

        this.sessionId = sessionId;

        await this.command('Page.enable');
        await this.command('Runtime.enable');
        await this.command('Network.enable');
        await this.command('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
    }

    send(method, params, sessionId) {
        const id = this.nextId++;
        const payload = { id, method, params: params || {} };

        if (sessionId) {
            payload.sessionId = sessionId;
        }

        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve, reject, method });
            this.socket.send(JSON.stringify(payload));
        });
    }

    /** Sends a command to the attached page. */
    command(method, params) {
        return this.send(method, params, this.sessionId);
    }

    onMessage(payload) {
        if (payload.id !== undefined) {
            const pending = this.pending.get(payload.id);

            if (!pending) {
                return;
            }

            this.pending.delete(payload.id);

            if (payload.error) {
                pending.reject(new Error(`${pending.method} failed: ${payload.error.message}`
                    + (payload.error.data ? ` (${payload.error.data})` : '')));
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

    /** Evaluates the expression in the page, awaiting a returned promise, and returns its value. */
    async evaluate(expression) {
        const result = await this.command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });

        if (result.exceptionDetails) {
            const details = result.exceptionDetails;

            throw new Error(`page evaluation failed: ${(details.exception && details.exception.description) || details.text}`);
        }

        return result.result ? result.result.value : undefined;
    }

    /** Closes the socket, sends SIGTERM and, when Chrome is still running after 5 s, SIGKILL. */
    async close() {
        if (this.socket) {
            try {
                this.socket.close();
            } catch (error) {
                this.stderrTail += `\nsocket close failed: ${error.message}`;
            }
        }
        if (this.process && !this.exited) {
            this.process.kill('SIGTERM');

            try {
                await waitFor('Chrome to exit after SIGTERM', () => this.exited, { timeout: 5000, interval: 100 });
            } catch (error) {
                this.process.kill('SIGKILL');
                await waitFor('Chrome to exit after SIGKILL', () => this.exited, { timeout: 5000, interval: 100 });
            }
        }
    }
}

let DEVTOOLS = null;

/** Records the POST requests to the board, with their post data and completion, from the page's Network events. */
class BoardRequestRecorder {
    constructor(devtools) {
        this.devtools = devtools;
        this.entries = [];

        const byId = new Map();

        this.unsubscribers = [
            devtools.on('Network.requestWillBeSent', (params) => {
                const request = params.request;
                const requestPath = request.url.replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]+/i, '').split(/[?#]/)[0];

                if (request.method !== 'POST' || requestPath !== BOARD_PATH) {
                    return;
                }

                const entry = {
                    requestId: params.requestId,
                    postData: request.postData === undefined ? null : request.postData,
                    postDataPromise: null,
                    finished: false,
                    failed: null
                };

                if (entry.postData === null && request.hasPostData) {
                    entry.postDataPromise = devtools.command('Network.getRequestPostData', { requestId: params.requestId })
                        .then((result) => {
                            entry.postData = result.postData;
                        }, (error) => {
                            entry.failed = `post data unavailable: ${error.message}`;
                        });
                }

                this.entries.push(entry);
                byId.set(params.requestId, entry);
            }),
            devtools.on('Network.loadingFinished', (params) => {
                const entry = byId.get(params.requestId);

                if (entry) {
                    entry.finished = true;
                }
            }),
            devtools.on('Network.loadingFailed', (params) => {
                const entry = byId.get(params.requestId);

                if (entry) {
                    entry.failed = params.errorText || 'loading failed';
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

    /** Waits for the post data requested with Network.getRequestPostData. */
    async settle() {
        await Promise.all(this.entries.map((entry) => entry.postDataPromise));
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

    /** Returns the parsed JSON response body of a finished request. */
    async responseJson(entry) {
        const result = await this.devtools.command('Network.getResponseBody', { requestId: entry.requestId });
        const text = result.base64Encoded ? Buffer.from(result.body, 'base64').toString('utf8') : result.body;

        return JSON.parse(text.trim());
    }
}

/** Waits until exactly one finished request of the recorder carries the event, failing on a failed or second request. */
async function waitForSingleEvent(recorder, name, timeout) {
    return waitFor(`the ${name} request`, async () => {
        await recorder.settle();

        const entries = recorder.withEvent(name);
        const failed = entries.find((entry) => entry.failed);

        if (failed) {
            throw new FatalError(`${name} request failed: ${failed.failed}`);
        }
        if (entries.length > 1) {
            throw new FatalError(`${entries.length} ${name} requests were sent`);
        }

        return entries.length === 1 && entries[0].finished ? entries[0] : false;
    }, { timeout, interval: 50, detail: () => JSON.stringify(recorder.eventNames()) });
}

// ---------------------------------------------------------------------------------------------------------------
// Browser
// ---------------------------------------------------------------------------------------------------------------

const FRAME_PREAMBLE = 'const F = document.getElementById(\'mainPageIframe\'); const W = F.contentWindow; '
    + 'const D = W.document;';

/** Wraps statements that read the board's iframe (F, W, D) into an expression. */
function frameExpression(statements) {
    return `(() => { ${FRAME_PREAMBLE} ${statements} })()`;
}

function barElementId(itemId) {
    return `${GANTT_PATH}_item_${itemId}`;
}

/** Logs in through the login form with fresh cookies and waits for main.html. */
async function browserLogin() {
    await DEVTOOLS.command('Network.clearBrowserCookies');

    const navigation = await DEVTOOLS.command('Page.navigate', { url: `${ARGS.baseUrl}/login.html?lang=en` });

    if (navigation.errorText) {
        throw new Error(`cannot open the login page: ${navigation.errorText}`);
    }

    await waitFor('the login form', () => DEVTOOLS.evaluate('document.readyState === \'complete\' '
        + '&& location.pathname === \'/login.html\' && !!document.getElementById(\'usernameInput\') '
        + '&& !!document.getElementById(\'passwordInput\') && !!document.getElementById(\'loginButton\')'),
    { timeout: 60000, interval: 100 });

    await DEVTOOLS.evaluate(`(() => {
        document.getElementById('usernameInput').value = ${JSON.stringify(ARGS.user)};
        document.getElementById('passwordInput').value = ${JSON.stringify(ARGS.password)};
        document.getElementById('loginButton').click();
        return true;
    })()`);

    await waitFor('main.html after login', () => DEVTOOLS.evaluate('location.pathname === \'/main.html\' '
        + '&& typeof window.goToPage === \'function\' && typeof window.encodeParams === \'function\''),
    { timeout: 120000, interval: 250 });
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

        const barCheck = barItemId === null
            ? 'return D.querySelectorAll(\'.ganttItem\').length > 0;'
            : `return !!D.getElementById(${JSON.stringify(barElementId(barItemId))});`;

        await waitFor(`the board of schedule ${scheduleId} in the main page iframe`, () => DEVTOOLS.evaluate(
            frameExpression(`
                if (!F || !W || !W.mainController || !D) { return false; }
                if (D.querySelectorAll('.ganttRowNameElement').length < 3) { return false; }
                ${barCheck}`)), { timeout: 120000, interval: 250 });

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

/**
 * Drags the bar of the rendered item from its centre to the point of targetRow and targetDate with real mouse input,
 * waits for the single moveItem request and checks its payload. Returns {payload, event, moveResult, content,
 * recorder}; the recorder keeps recording until the caller stops it.
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

    try {
        await dispatchMouse('mousePressed', press, { button: 'left', buttons: 1, clickCount: 1 });
        await dispatchMouse('mouseMoved', { x: press.x + (deltaX / length) * 6, y: press.y + (deltaY / length) * 6 },
            { button: 'left', buttons: 1 });

        for (let step = 1; step <= 10; step++) {
            await dispatchMouse('mouseMoved', { x: press.x + (deltaX * step) / 10, y: press.y + (deltaY * step) / 10 },
                { button: 'left', buttons: 1 });
        }

        await dispatchMouse('mouseReleased', target, { button: 'left', buttons: 0, clickCount: 1 });

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
        recorder.stop();
        throw error;
    }
}

/** Asserts that the drag sent no refresh event, and stops its recorder. */
async function finishDrag(drag) {
    try {
        await drag.recorder.settle();
        assert.deepStrictEqual(drag.recorder.withEvent('refresh').length, 0,
            `no refresh after the drop (events: ${drag.recorder.eventNames().join(', ')})`);
    } finally {
        drag.recorder.stop();
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------------------------------------------

// English rejection reasons and the rejection tooltip header.
let EXPECTED = null;

before(async () => {
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

after(async () => {
    if (DEVTOOLS) {
        await DEVTOOLS.close();
    }

    for (const child of PSQL_CHILDREN) {
        child.kill('SIGKILL');
    }

    for (const directory of TEMP_DIRS) {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

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
 * Runs one simultaneous-write move of A2 to PMG-B at D1 10:00: a background transaction writes writeSql, creates its
 * marker, sleeps 3 s and commits while the move request runs. Asserts the optimistic-lock rejection and that every
 * checksum equals the state after writeSql alone.
 */
async function concurrentWriteRun(t, caseName, session, writeSql) {
    const fixture = fx(caseName);
    const board = await initialize(session, fixture.scheduleId);
    const item = itemById(board.content, fixture.roles.A2.positionId);
    const expected = await precomputeChecksums(fixture.scheduleId, writeSql);
    const background = backgroundWrite(writeSql);

    await waitForMarker(background.markerPath, background.done, 'the background write to hold its row locks');

    const move = postMove(session, fixture.scheduleId, board.headerParameters, item, 'PMG-B', at(1, '10:00'));

    move.catch(() => undefined);

    let backgroundOpen = true;

    background.done.then(() => {
        backgroundOpen = false;
    }, () => {
        backgroundOpen = false;
    });

    await waitFor('the background transaction to commit', async () => {
        if (!backgroundOpen) {
            return true;
        }

        t.diagnostic(`application backends waiting on a lock: ${await appLockWaiters()}`);

        return !backgroundOpen;
    }, { timeout: 60000, interval: 500 });

    await background.done;

    const content = await move;

    assert.equal(content.moveResult.accepted, false, `the move is rejected (message: ${content.moveResult.message})`);
    assert.equal(norm(content.moveResult.message), norm(EXPECTED.optimisticLock), 'the rejection is the optimistic lock');
    assert.deepStrictEqual(await checksums(fixture.scheduleId), expected, 'only the background write persisted');
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

/**
 * Terminates the idle client backends of the application (any application name other than pmg-acceptance*) on the
 * acceptance database and waits until they have exited; each backend reports its pending table statistics as it
 * exits. The application's connection pool opens new connections on demand.
 */
async function flushApplicationBackendStats() {
    const terminated = await psqlJson(`
SELECT coalesce(json_agg(pid), '[]')
FROM pg_stat_activity
WHERE datname = current_database()
  AND backend_type = 'client backend'
  AND state = 'idle'
  AND pid <> pg_backend_pid()
  AND coalesce(application_name, '') NOT LIKE 'pmg-acceptance%'
  AND pg_terminate_backend(pid);
`, { app: 'stats' });

    if (terminated.length === 0) {
        return;
    }

    await waitFor('the terminated application backends to exit', async () => (await psqlInteger(`
SELECT count(*) FROM pg_stat_activity WHERE pid IN (${terminated.map(Number).join(', ')});
`, { app: 'stats' })) === 0, { timeout: 30000, interval: 100 });
}

/** Reads n_tup_ins of productionscheduling_planordertimecalculation in a fresh psql session. */
async function planOrderTimeCalculationInserts() {
    return psqlInteger(`
SELECT coalesce((SELECT n_tup_ins
                 FROM pg_stat_user_tables
                 WHERE schemaname = 'public' AND relname = 'productionscheduling_planordertimecalculation'), -1);
`, { app: 'stats' });
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
    const markerPath = path.join(makeTempDir('holder'), 'locked');
    const holder = spawnPsql('holder');

    holder.done.catch(() => undefined);
    holder.child.stdin.write(`BEGIN;\nSELECT id FROM public.orders_productionlinescheduleposition WHERE id = `
        + `${Number(fixture.roles.B5.positionId)} FOR UPDATE;\n\\! touch '${markerPath}'\n`);

    await waitForMarker(markerPath, holder.done, 'the holder session to lock B5');

    // M1: A2 -> PMG-B at D1 10:00; M2: A2 -> PMG-B at D1 09:30; both sent at once.
    const moves = [
        postMove(session, fixture.scheduleId, board.headerParameters, item, 'PMG-B', at(1, '10:00')),
        postMove(session, fixture.scheduleId, board.headerParameters, item, 'PMG-B', at(1, '09:30'))
    ];

    for (const move of moves) {
        move.catch(() => undefined);
    }

    let waitersError = null;

    try {
        await waitFor('two application backends waiting on a lock', async () => {
            const waiters = await appLockWaiters();

            t.diagnostic(`application backends waiting on a lock: ${waiters}`);

            return waiters >= 2;
        }, { timeout: 60000, interval: 250 });
    } catch (error) {
        waitersError = error;
    }

    const activity = waitersError ? await activityDump() : null;

    holder.child.stdin.end('COMMIT;\n');
    await holder.done;

    if (waitersError) {
        await Promise.allSettled(moves);
        throw new Error(`${waitersError.message}\npg_stat_activity: ${JSON.stringify(activity, null, 2)}`);
    }

    const results = await Promise.all(moves);
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
