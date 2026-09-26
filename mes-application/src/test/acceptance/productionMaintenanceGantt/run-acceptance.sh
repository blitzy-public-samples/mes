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
#   --work-root      Existing or new directory that holds the temporary work directory.
#                    Default: the directory mktemp -t uses.
#
# Steps:
#   1. Checks the arguments, the repository layout and the tools on PATH.
#   2. Recreates the database marked with the comment in MARKER on the server of conf/tomcat/db.properties,
#      then loads mes_db_en.sql and fixture.sql into it and checks the required plugins are enabled.
#   3. Unpacks the distribution into a temporary work directory, checks its db.properties, sets hotDeploy=false in
#      its app.properties, applies --db-name, --http-port and --shutdown-port to the unpacked copy, and starts Tomcat.
#   4. Runs acceptance.test.js with the TAP reporter and checks the TAP summary and every case line.
#   5. Stops Tomcat and removes the work directory on every exit. The database is kept.
#
# Exit status: 0 when every acceptance case passed; 2 on a usage error; the runner's status when the runner failed;
# 1 on any other failure.

set -euo pipefail
unset CDPATH

readonly PROGRAM='run-acceptance.sh'
readonly MARKER='qcadoo-acceptance:productionMaintenanceGantt'
readonly REQUIRED_TOOLS=(psql node unzip curl chrome-headless-shell)
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

# State read by the cleanup trap.
WORK_DIR=''
TOMCAT_LOG=''
TOMCAT_PID=''
CATALINA_BASE=''

# Reads the port attributes of conf/server.xml outside XML comments, or rewrites them.
#   ports <server.xml>                           prints "<http port> <shutdown port>"; an absent value prints "-"
#   rewrite <server.xml> <http> <shutdown>       sets the port of the first non-AJP Connector and of the Server element
# shellcheck disable=SC2016
readonly SERVER_XML_JS='
"use strict";
const fs = require("fs");
const [mode, file, httpPort, shutdownPort] = process.argv.slice(1);
const parts = fs.readFileSync(file, "utf8").split(/(<!--[\s\S]*?-->)/);
const PORT = /(\bport\s*=\s*")(-?\d+)(")/;
const isAjp = (element) => /\bprotocol\s*=\s*"[^"]*ajp/i.test(element);
let http = null;
let shutdown = null;
for (let index = 0; index < parts.length; index += 2) {
    parts[index] = parts[index].replace(/<Server\b[^>]*>/g, (element) => {
        const port = PORT.exec(element);
        if (shutdown !== null || !port) {
            return element;
        }
        shutdown = port[2];
        return mode === "rewrite" ? element.replace(PORT, "$1" + shutdownPort + "$3") : element;
    });
    parts[index] = parts[index].replace(/<Connector\b[^>]*>/g, (element) => {
        const port = PORT.exec(element);
        if (http !== null || !port || isAjp(element)) {
            return element;
        }
        http = port[2];
        return mode === "rewrite" ? element.replace(PORT, "$1" + httpPort + "$3") : element;
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

# Succeeds when the argument is a TCP port number from 1 to 65535.
is_port() {
    [[ "$1" =~ ^[0-9]{1,5}$ ]] && [ "$((10#$1))" -ge 1 ] && [ "$((10#$1))" -le 65535 ]
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

# Succeeds when a TCP connection to the given port on 127.0.0.1 opens.
port_open() {
    (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null
}

# Runs psql against the maintenance database `postgres`, unaligned and tuples only.
psql_admin() {
    psql -X -w -At -v ON_ERROR_STOP=1 -d "$ADMIN_URI" "$@"
}

# Runs psql against the acceptance database, unaligned and tuples only.
psql_db() {
    psql -X -w -At -v ON_ERROR_STOP=1 -d "$DB_URI" "$@"
}

# Succeeds while the started Tomcat process runs under the unpacked CATALINA_BASE.
tomcat_alive() {
    local cmdline

    [ -n "$TOMCAT_PID" ] || return 1
    kill -0 "$TOMCAT_PID" 2> /dev/null || return 1

    if [ -r "/proc/$TOMCAT_PID/cmdline" ]; then
        cmdline="$(tr '\0' ' ' < "/proc/$TOMCAT_PID/cmdline" 2> /dev/null)" || return 1

        case "$cmdline" in
            *"$CATALINA_BASE"*) return 0 ;;
            *) return 1 ;;
        esac
    fi

    return 0
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
        printf '%s: error: Tomcat (pid %s) is still running\n' "$PROGRAM" "$TOMCAT_PID" >&2
    else
        wait "$TOMCAT_PID" 2> /dev/null || true
        log 'Tomcat stopped'
    fi
}

# Prints the Tomcat log tail on failure, stops Tomcat, removes the work directory and exits with the original status.
# shellcheck disable=SC2317
cleanup() {
    local status=$?

    if [ "$#" -gt 0 ]; then
        status="$1"
    fi

    trap - EXIT INT TERM
    set +e

    if [ "$status" -ne 0 ] && [ -n "$TOMCAT_LOG" ] && [ -f "$TOMCAT_LOG" ]; then
        printf '%s: last %s lines of %s:\n' "$PROGRAM" "$LOG_TAIL_LINES" "$TOMCAT_LOG" >&2
        tail -n "$LOG_TAIL_LINES" "$TOMCAT_LOG" >&2
    fi

    if [ -n "$TOMCAT_PID" ]; then
        stop_tomcat
    fi

    if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
        rm -rf -- "$WORK_DIR"
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

# Resolves the distribution and the work root against the caller's working directory.
DIST="$(absolute_path "$DIST_ARG")"

if [ -d "$(dirname "$DIST")" ]; then
    DIST="$(cd "$(dirname "$DIST")" && pwd)/$(basename "$DIST")"
fi

[ -f "$DIST" ] || die "distribution $DIST does not exist; build it with (cd mes/mes-application && mvn -B -Ptomcat clean install)"
[ -r "$DIST" ] || die "distribution $DIST is not readable"

WORK_ROOT=''

if [ -n "$WORK_ROOT_ARG" ]; then
    WORK_ROOT="$(absolute_path "$WORK_ROOT_ARG")"
fi

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

# ---------------------------------------------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------------------------------------------

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

if ! date -d 'today + 1 days' +%F > /dev/null 2>&1; then
    die 'date must accept -d with relative dates (GNU coreutils date)'
fi

CHROME="$(command -v chrome-headless-shell)"

log "node $(node --version), $(psql --version), chrome-headless-shell at $CHROME"

# Creates the temporary work directory.
if [ -n "$WORK_ROOT" ]; then
    mkdir -p -- "$WORK_ROOT"
    WORK_DIR="$(mktemp -d -p "$WORK_ROOT" pmg-acceptance.XXXXXX)"
else
    WORK_DIR="$(mktemp -d -t pmg-acceptance.XXXXXX)"
fi

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

# Splits dbJdbcUrl into host, port, database and query.
readonly JDBC_HOST_URL_RE='^jdbc:postgresql://([^/:]+)(:([0-9]+))?/([^/]*)$'
readonly JDBC_LOCAL_URL_RE='^jdbc:postgresql:([^/].*)$'

DB_URL_PATH="${REPO_DB_URL%%\?*}"
DB_URL_QUERY=''

if [ "$DB_URL_PATH" != "$REPO_DB_URL" ]; then
    DB_URL_QUERY="?${REPO_DB_URL#*\?}"
fi

if [[ "$DB_URL_PATH" =~ $JDBC_HOST_URL_RE ]]; then
    DB_HOST="${BASH_REMATCH[1]}"
    DB_PORT="${BASH_REMATCH[3]:-$DEFAULT_DB_PORT}"
    REPO_DB_NAME="${BASH_REMATCH[4]}"
elif [[ "$DB_URL_PATH" =~ $JDBC_LOCAL_URL_RE ]]; then
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
DB_URI="postgresql://$ENCODED_DB_USER:$ENCODED_DB_PASSWORD@$DB_HOST:$DB_PORT/$DB_NAME"
ADMIN_URI="postgresql://$ENCODED_DB_USER:$ENCODED_DB_PASSWORD@$DB_HOST:$DB_PORT/postgres"

# Checks the PostgreSQL server answers.
log "checking PostgreSQL at $DB_HOST:$DB_PORT as $REPO_DB_USER"

if ! server_check="$(psql -X -w -At -v ON_ERROR_STOP=1 -d "$ADMIN_URI" -c 'SELECT 1' 2>&1)" \
        || [ "$server_check" != '1' ]; then
    die "PostgreSQL at $DB_HOST:$DB_PORT is unreachable as $REPO_DB_USER: $server_check"
fi

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
psql -X -w -q -v ON_ERROR_STOP=1 -d "$DB_URI" -f "$SEED_SQL" > "$WORK_DIR/seed.log"
log 'seed loaded'

# Chooses the first day 2 to 13 days ahead whose UTC offset is identical on it and on the 8 days after it.
BASE_DAY=''
BASE_DAY_ZONE=''

for day_offset in {2..13}; do
    candidate_day="$(date -d "today + $day_offset days" +%F)"
    candidate_zone=''
    constant_zone=1

    for following_day in {0..8}; do
        if ! following_zone="$(date -d "$candidate_day + $following_day days" +%z 2> /dev/null)"; then
            constant_zone=0
            break
        fi

        if [ "$following_day" -eq 0 ]; then
            candidate_zone="$following_zone"
        elif [ "$following_zone" != "$candidate_zone" ]; then
            constant_zone=0
            break
        fi
    done

    if [ "$constant_zone" -eq 1 ]; then
        BASE_DAY="$candidate_day"
        BASE_DAY_ZONE="$candidate_zone"
        break
    fi
done

[ -n "$BASE_DAY" ] || die 'no day 2 to 13 days ahead has the same UTC offset on it and on the 8 days after it'

log "base day $BASE_DAY (UTC offset $BASE_DAY_ZONE from $BASE_DAY to 8 days later)"

# Loads the fixture.
FIXTURE_LOG="$WORK_DIR/fixture.log"

log "loading $FIXTURE_SQL into $DB_NAME"
psql -X -w -v ON_ERROR_STOP=1 -v base_day="$BASE_DAY" -d "$DB_URI" -f "$FIXTURE_SQL" 2>&1 | tee "$FIXTURE_LOG"
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

# Unpacks the distribution.
DIST_DIR="$WORK_DIR/dist"

log "unpacking $DIST into $DIST_DIR"
mkdir -- "$DIST_DIR"
unzip -q "$DIST" -d "$DIST_DIR"

# Finds the one catalina.sh of the distribution.
mapfile -t catalina_scripts < <(find "$DIST_DIR" -type f -path '*/bin/catalina.sh')

if [ "${#catalina_scripts[@]}" -ne 1 ]; then
    die "expected exactly one */bin/catalina.sh in $DIST, found ${#catalina_scripts[@]}"
fi

CATALINA_BASE="$(dirname "$(dirname "${catalina_scripts[0]}")")"
CATALINA_HOME="$CATALINA_BASE"
export CATALINA_BASE CATALINA_HOME
chmod +x "$CATALINA_BASE"/bin/*.sh

# Checks the distribution's db.properties holds the connection settings of conf/tomcat/db.properties.
mapfile -t dist_db_properties < <(find "$DIST_DIR" -type f -name db.properties)

if [ "${#dist_db_properties[@]}" -ne 1 ]; then
    die "expected exactly one db.properties in $DIST, found ${#dist_db_properties[@]}"
fi

DIST_DB_PROPERTIES="${dist_db_properties[0]}"

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
    ACCEPTANCE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME$DB_URL_QUERY"
    set_property_value "$DIST_DB_PROPERTIES" dbJdbcUrl "$ACCEPTANCE_DB_URL"

    if [ "$(property_value "$DIST_DB_PROPERTIES" dbJdbcUrl)" != "$ACCEPTANCE_DB_URL" ]; then
        die "cannot set dbJdbcUrl=$ACCEPTANCE_DB_URL in $DIST_DB_PROPERTIES"
    fi

    log "unpacked distribution uses dbJdbcUrl=$ACCEPTANCE_DB_URL"
fi

# Serves static resources from the packaged jars: sets hotDeploy=false in the app.properties next to db.properties.
DIST_APP_PROPERTIES="$(dirname "$DIST_DB_PROPERTIES")/app.properties"

[ -f "$DIST_APP_PROPERTIES" ] || die "$DIST_APP_PROPERTIES not found"

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

server_ports="$(node -e "$SERVER_XML_JS" -- ports "$SERVER_XML")" || die "cannot read the ports of $SERVER_XML"
read -r XML_HTTP_PORT XML_SHUTDOWN_PORT <<< "$server_ports"

if [ "$XML_HTTP_PORT" = '-' ]; then
    XML_HTTP_PORT="$DEFAULT_HTTP_PORT"
fi

if [ "$XML_SHUTDOWN_PORT" = '-' ]; then
    XML_SHUTDOWN_PORT="$DEFAULT_SHUTDOWN_PORT"
fi

HTTP_PORT="${HTTP_PORT_ARG:-$XML_HTTP_PORT}"
SHUTDOWN_PORT="${SHUTDOWN_PORT_ARG:-$XML_SHUTDOWN_PORT}"

is_port "$HTTP_PORT" || die "HTTP port $HTTP_PORT of $SERVER_XML is outside 1 to 65535"

if [ "$HTTP_PORT" = "$SHUTDOWN_PORT" ]; then
    die "HTTP port and shutdown port are both $HTTP_PORT"
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

until curl -fsS -o /dev/null --max-time 10 "$BASE_URL/login.html" 2> /dev/null; do
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

# ---------------------------------------------------------------------------------------------------------------
# Runner and TAP gate
# ---------------------------------------------------------------------------------------------------------------

TAP_LOG="$WORK_DIR/acceptance.tap"

log "running acceptance.test.js against $BASE_URL with base day $BASE_DAY"
cd "$SCRIPT_DIR"

set +e
node --test-reporter=tap acceptance.test.js --base-url="$BASE_URL" --user="$USER_ARG" --password="$PASSWORD_ARG" \
    --db-uri="$DB_URI" --chrome="$CHROME" --base-day="$BASE_DAY" | tee "$TAP_LOG"
RUNNER_STATUS="${PIPESTATUS[0]}"
set -e

if [ "$RUNNER_STATUS" -ne 0 ]; then
    printf '%s: error: acceptance.test.js exited with status %s\n' "$PROGRAM" "$RUNNER_STATUS" >&2
    exit "$RUNNER_STATUS"
fi

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

