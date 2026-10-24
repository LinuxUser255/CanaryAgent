#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Script:       burp-headless-longsession.sh
# Description:  Long-session memory check: loads the newest self-test JAR
#               in a headless Burp Suite Pro, pushes thousands of
#               agent-shaped requests from the test corpus through its
#               Proxy listener to a local echo server, and reads the
#               extension's STATS lines (store sizes, heap after GC).
#               Passes when the traffic store never exceeds its cap and
#               the heap settles after the traffic stops. Nothing leaves
#               the machine. Dev helper; never part of the extension.
# Usage:        burp-headless-longsession.sh [-h|--help] [-n|--dry-run]
#                 [-v|--verbose] [--burp-home DIR] [--jar FILE]
#                 [--port N] [--echo-port N] [--requests N]
#                 [--stats-interval SECONDS] [--timeout SECONDS]
#               BURP_HOME may be set instead of --burp-home.
# Dependencies: bash >= 4.4, python3, curl, mktemp, grep, kill, sleep, date
# Notes:        Measures the self-test JAR: its runtime classes are the
#               production ones byte for byte; only the entry class and
#               the dev-only tab differ. Record which JAR ran. Burp prints
#               its own "no ComponentUI class" errors and a
#               NullPointerException under the headless flag; they are
#               Burp's and the run proceeds. JVM -D properties must
#               precede -jar; anything after it is an argument to Burp.
# ----------------------------------------------------------------------
set -euo pipefail
IFS=$'\n\t'

SCRIPT_NAME="${0##*/}"
readonly SCRIPT_NAME
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd -P)"
readonly REPO_ROOT

readonly EXT_NAME='CanaryAgent (self-test)'
readonly PROP_PORT='canaryagent.selftest.proxyPort'
readonly PROP_FORCE_MATCHING='canaryagent.selftest.forceMatching'
readonly PROP_STATS='canaryagent.selftest.statsEverySeconds'
readonly TRAFFIC_CAP=5000
readonly HEAP_TOLERANCE_PERCENT=10
readonly POLL_SECONDS=2
readonly STOP_GRACE_SECONDS=30
readonly CORPUS_DIR="${REPO_ROOT}/src/test/resources/corpus"

# Echo server: answers every POST with the request body, every GET with
# 204. Loopback only. Run with python3 -I so nothing is imported from the
# working directory.
readonly ECHO_SERVER_PY='
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class Echo(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n)
        self.send_response(200)
        self.send_header("Content-Type",
                         self.headers.get("Content-Type", "application/json"))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_GET(self):
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()
    def log_message(self, *args):
        pass
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Echo).serve_forever()
'

RED=$'\033[0;31m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'
if [[ ! -t 1 || ! -t 2 ]]; then RED=''; CYAN=''; NC=''; fi

DEBUG="${DEBUG:-0}"
VERBOSE=0
DRY_RUN=0
TMP_DIR=''
BURP_PID=''
ECHO_PID=''
BURP_HOME="${BURP_HOME:-}"
SELFTEST_JAR=''
PORT=8089
ECHO_PORT=8099
REQUESTS=10000
STATS_INTERVAL=10
TIMEOUT=900
LOG_FILE=''
BASELINE=0

err()   { printf '%b[ERROR]%b %s\n' "${RED}" "${NC}" "$*" >&2; }
log()   { printf '%b[INFO]%b %s\n' "${CYAN}" "${NC}" "$*"; }
debug() {
  if [[ "${DEBUG}" == 1 ]]; then printf '[DEBUG] %s\n' "$*" >&2; fi
  return 0
}

show_help() {
  printf '%s\n' \
    "Usage: ${SCRIPT_NAME} [-h|--help] [-n|--dry-run] [-v|--verbose]" \
    '         [--burp-home DIR] [--jar FILE] [--port N] [--echo-port N]' \
    '         [--requests N] [--stats-interval SECONDS] [--timeout SECONDS]' \
    '         [--baseline]' \
    '' \
    '--baseline  leave annotation off, so the extension stores stay empty' \
    '            and the heap growth seen is Burp'"'"'s own (Proxy history).' \
    '            Compare its STATS series with a normal run; the cap check' \
    '            is skipped.' \
    '' \
    'Loads the newest build/libs/canaryagent-selftest-N.jar (or --jar) in a' \
    'headless Burp Suite Pro at --burp-home (or BURP_HOME) with a Proxy' \
    'listener on 127.0.0.1:PORT (default 8089), starts a local echo server' \
    'on 127.0.0.1:ECHO_PORT (default 8099), sends REQUESTS corpus bodies' \
    '(default 10000) through the listener, and reads the STATS lines the' \
    'self-test JAR logs every STATS_INTERVAL seconds (default 10).' \
    '' \
    "Pass: traffic store reaches its cap of ${TRAFFIC_CAP} rows and never" \
    "exceeds it, and the last three heap-after-GC samples are within" \
    "${HEAP_TOLERANCE_PERCENT}% of each other once traffic stops." \
    'Log: build/longsession-N.log. Exit: 0 pass, 1 fail/timeout, 2 bad' \
    'arguments, 127 missing command.'
  return 0
}

wants_help() {
  local arg
  for arg in "$@"; do
    if [[ "${arg}" == -h || "${arg}" == --help ]]; then return 0; fi
  done
  return 1
}

check_requirements() {
  local -a missing=()
  local cmd
  if (( BASH_VERSINFO[0] < 4 \
    || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] < 4) )); then
    err "bash >= 4.4 required (found ${BASH_VERSION})"
    exit 1
  fi
  for cmd in python3 curl mktemp grep kill sleep date; do
    command -v "${cmd}" >/dev/null 2>&1 || missing+=("${cmd}")
  done
  if (( ${#missing[@]} > 0 )); then
    for cmd in "${missing[@]}"; do err "missing required command: ${cmd}"; done
    exit 127
  fi
  return 0
}

need_value() {
  if (( $# < 2 )); then err "$1 needs a value"; exit 2; fi
  return 0
}

parse_args() {
  while (( $# > 0 )); do
    case "$1" in
      -n|--dry-run) DRY_RUN=1 ;;
      -v|--verbose) VERBOSE=1 ;;
      --burp-home) need_value "$@"; BURP_HOME="$2"; shift ;;
      --jar) need_value "$@"; SELFTEST_JAR="$2"; shift ;;
      --port) need_value "$@"; PORT="$2"; shift ;;
      --echo-port) need_value "$@"; ECHO_PORT="$2"; shift ;;
      --requests) need_value "$@"; REQUESTS="$2"; shift ;;
      --stats-interval) need_value "$@"; STATS_INTERVAL="$2"; shift ;;
      --timeout) need_value "$@"; TIMEOUT="$2"; shift ;;
      --baseline) BASELINE=1 ;;
      *) err "unknown argument: $1"; show_help >&2; exit 2 ;;
    esac
    shift
  done
  return 0
}

cleanup() {
  local pid
  for pid in "${BURP_PID}" "${ECHO_PID}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill -TERM "${pid}" 2>/dev/null || true
    fi
  done
  if [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]]; then rm -rf -- "${TMP_DIR}"; fi
  return 0
}
trap cleanup EXIT INT TERM

is_port() { [[ "$1" =~ ^[0-9]+$ ]] && (( $1 >= 1024 && $1 <= 65535 )); }

# Validates inputs and resolves defaults. Sets BURP_JAVA, BURP_JAR,
# SELFTEST_JAR, LOG_FILE and CORPUS (array of body files).
resolve_inputs() {
  local candidate number best_number=-1 best='' existing n max=0
  local build_libs="${REPO_ROOT}/build/libs"
  local log_dir="${REPO_ROOT}/build"

  if [[ -z "${BURP_HOME}" ]]; then
    err 'Burp home not set: pass --burp-home DIR or export BURP_HOME'
    exit 2
  fi
  if [[ ! -d "${BURP_HOME}" ]]; then
    err "Burp home is not a directory: ${BURP_HOME}"; exit 2
  fi
  BURP_JAVA="${BURP_HOME}/jre/bin/java"
  if [[ ! -x "${BURP_JAVA}" ]]; then
    err "bundled JRE not found: ${BURP_JAVA}"; exit 2
  fi
  if [[ -f "${BURP_HOME}/burpsuite.jar" ]]; then
    BURP_JAR="${BURP_HOME}/burpsuite.jar"
  elif [[ -f "${BURP_HOME}/burpsuite_pro.jar" ]]; then
    BURP_JAR="${BURP_HOME}/burpsuite_pro.jar"
  else
    err "no burpsuite.jar or burpsuite_pro.jar in ${BURP_HOME}"; exit 2
  fi
  readonly BURP_JAVA BURP_JAR

  if ! is_port "${PORT}"; then err "--port must be 1024..65535"; exit 2; fi
  if ! is_port "${ECHO_PORT}"; then
    err "--echo-port must be 1024..65535"; exit 2
  fi
  if [[ "${PORT}" == "${ECHO_PORT}" ]]; then
    err '--port and --echo-port must differ'; exit 2
  fi
  if [[ ! "${REQUESTS}" =~ ^[0-9]+$ ]] || (( REQUESTS < 1 )); then
    err "--requests must be a positive number (got ${REQUESTS})"; exit 2
  fi
  if [[ ! "${STATS_INTERVAL}" =~ ^[0-9]+$ ]] || (( STATS_INTERVAL < 1 )); then
    err "--stats-interval must be >= 1 (got ${STATS_INTERVAL})"; exit 2
  fi
  if [[ ! "${TIMEOUT}" =~ ^[0-9]+$ ]] || (( TIMEOUT < 60 )); then
    err "--timeout must be >= 60 seconds (got ${TIMEOUT})"; exit 2
  fi

  if [[ -z "${SELFTEST_JAR}" ]]; then
    for candidate in "${build_libs}"/canaryagent-selftest-*.jar; do
      [[ -f "${candidate}" ]] || continue
      number="${candidate##*/canaryagent-selftest-}"
      number="${number%.jar}"
      if [[ "${number}" =~ ^[0-9]+$ ]] && (( number > best_number )); then
        best_number="${number}"; best="${candidate}"
      fi
    done
    if [[ -z "${best}" ]]; then
      err "no canaryagent-selftest-N.jar in ${build_libs}"
      err 'run ./gradlew selfTestJar first'
      exit 2
    fi
    SELFTEST_JAR="${best}"
  fi
  if [[ ! -f "${SELFTEST_JAR}" ]]; then
    err "self-test JAR not found: ${SELFTEST_JAR}"; exit 2
  fi
  local jar_dir
  jar_dir="$(cd "$(dirname "${SELFTEST_JAR}")" && pwd -P)"
  SELFTEST_JAR="${jar_dir}/${SELFTEST_JAR##*/}"
  if [[ "${SELFTEST_JAR}" != "${REPO_ROOT}"/* ]]; then
    err "self-test JAR must live inside the repo: ${SELFTEST_JAR}"; exit 2
  fi

  CORPUS=()
  for candidate in "${CORPUS_DIR}"/*.json; do
    [[ -f "${candidate}" ]] && CORPUS+=("${candidate}")
  done
  if (( ${#CORPUS[@]} == 0 )); then
    err "no .json bodies in ${CORPUS_DIR}"; exit 2
  fi

  for existing in "${log_dir}"/longsession-*.log; do
    [[ -f "${existing}" ]] || continue
    n="${existing##*/longsession-}"; n="${n%.log}"
    if [[ "${n}" =~ ^[0-9]+$ ]] && (( n > max )); then max="${n}"; fi
  done
  LOG_FILE="${log_dir}/longsession-$(( max + 1 )).log"
  return 0
}

write_configs() {
  TMP_DIR="$(mktemp -d)"
  USER_CFG="${TMP_DIR}/user-options.json"
  PROJECT_CFG="${TMP_DIR}/project-options.json"
  readonly USER_CFG PROJECT_CFG
  printf '%s\n' \
    '{' \
    '  "user_options": {' \
    '    "extender": {' \
    '      "extensions": [' \
    '        {' \
    '          "errors": "console",' \
    "          \"extension_file\": \"${SELFTEST_JAR}\"," \
    '          "extension_type": "java",' \
    '          "loaded": true,' \
    "          \"name\": \"${EXT_NAME}\"," \
    '          "output": "console"' \
    '        }' \
    '      ]' \
    '    }' \
    '  }' \
    '}' > "${USER_CFG}"
  printf '%s\n' \
    '{' \
    '  "proxy": {' \
    '    "request_listeners": [' \
    '      {' \
    '        "certificate_mode": "per_host",' \
    '        "enable_http2": true,' \
    '        "listen_mode": "loopback_only",' \
    "        \"listener_port\": ${PORT}," \
    '        "running": true' \
    '      }' \
    '    ]' \
    '  }' \
    '}' > "${PROJECT_CFG}"
  return 0
}

build_command() {
  BURP_CMD=(
    "${BURP_JAVA}"
    '-Djava.awt.headless=true'
    "-D${PROP_PORT}=${PORT}"
    "-D${PROP_STATS}=${STATS_INTERVAL}"
  )
  # Headless settings read unset, so without this the annotator stays off.
  if (( ! BASELINE )); then BURP_CMD+=("-D${PROP_FORCE_MATCHING}=true"); fi
  BURP_CMD+=(
    '-jar' "${BURP_JAR}"
    '--use-defaults'
    "--user-config-file=${USER_CFG}"
    "--config-file=${PROJECT_CFG}"
    '--disable-auto-update'
  )
  readonly BURP_CMD
  return 0
}

print_plan() {
  local part
  log "self-test JAR: ${SELFTEST_JAR}"
  log "Burp: ${BURP_JAR}"
  log "listener: 127.0.0.1:${PORT}  echo: 127.0.0.1:${ECHO_PORT}"
  log "requests: ${REQUESTS}  bodies: ${#CORPUS[@]}"
  log "stats every ${STATS_INTERVAL}s  baseline: ${BASELINE}"
  log "timeout: ${TIMEOUT}s  log: ${LOG_FILE}"
  if (( VERBOSE || DRY_RUN )); then
    printf '%s\n' '--- user options ---'; cat "${USER_CFG}"
    printf '%s\n' '--- project options ---'; cat "${PROJECT_CFG}"
    printf '%s\n' '--- command ---'
    for part in "${BURP_CMD[@]}"; do printf '  %s\n' "${part}"; done
  fi
  return 0
}

start_echo_server() {
  local waited=0
  python3 -I -c "${ECHO_SERVER_PY}" "${ECHO_PORT}" &
  ECHO_PID=$!
  until curl -s -o /dev/null --max-time 2 "http://127.0.0.1:${ECHO_PORT}/"; do
    if ! kill -0 "${ECHO_PID}" 2>/dev/null; then
      err "echo server exited; is port ${ECHO_PORT} free?"; return 1
    fi
    if (( waited >= 10 )); then err 'echo server not answering'; return 1; fi
    sleep 1; waited=$(( waited + 1 ))
  done
  log "echo server up (pid ${ECHO_PID})"
  return 0
}

wait_for_load() {
  local waited=0
  until grep -q 'Loaded on Burp' "${LOG_FILE}" 2>/dev/null; do
    if ! kill -0 "${BURP_PID}" 2>/dev/null; then
      err 'Burp exited before the extension loaded; see the log'; return 1
    fi
    if (( waited >= 120 )); then
      err 'extension did not load within 120s'; return 1
    fi
    sleep "${POLL_SECONDS}"; waited=$(( waited + POLL_SECONDS ))
  done
  log 'extension loaded; starting traffic'
  return 0
}

# Sends REQUESTS corpus bodies through the listener. Counts failures.
generate_traffic() {
  local i body failures=0 started
  started=$(date +%s)
  for (( i = 0; i < REQUESTS; i++ )); do
    body="${CORPUS[$(( i % ${#CORPUS[@]} ))]}"
    if ! curl -s -o /dev/null --max-time 15 \
        -x "http://127.0.0.1:${PORT}" \
        -H 'Content-Type: application/json' \
        --data-binary "@${body}" \
        "http://127.0.0.1:${ECHO_PORT}/echo/$(( i % 97 ))"; then
      failures=$(( failures + 1 ))
    fi
    if (( (i + 1) % 1000 == 0 )); then
      local elapsed=$(( $(date +%s) - started ))
      log "sent $(( i + 1 ))/${REQUESTS}, failures ${failures}, ${elapsed}s"
    fi
    if (( $(date +%s) - started > TIMEOUT )); then
      err "traffic generation exceeded ${TIMEOUT}s"; return 1
    fi
  done
  log "traffic done: ${REQUESTS} requests, ${failures} curl failures"
  if (( failures * 100 > REQUESTS )); then
    err 'more than 1% of requests failed'; return 1
  fi
  return 0
}

# Field N of a STATS line: traffic=, tools=, markers=, heapAfterGcMb=.
stat_field() {
  local line="$1" key="$2" rest
  rest="${line#*"${key}"=}"
  printf '%s' "${rest%% *}"
}

# Reads the STATS lines; checks the cap and the heap plateau.
evaluate() {
  local -a lines heaps
  local line traffic max_traffic=0 hi=0 lo=0 spread rc=0
  # Sample lines only: the sampler's startup line also says STATS.
  mapfile -t lines < <(grep '\[self-test\] STATS t=' "${LOG_FILE}" || true)
  if (( ${#lines[@]} < 4 )); then
    err "only ${#lines[@]} STATS lines; need at least 4"; return 1
  fi
  for line in "${lines[@]}"; do
    traffic="$(stat_field "${line}" traffic)"
    if (( traffic > max_traffic )); then max_traffic="${traffic}"; fi
  done
  heaps=()
  for line in "${lines[@]: -3}"; do
    heaps+=("$(stat_field "${line}" heapAfterGcMb)")
  done
  hi="${heaps[0]}"; lo="${heaps[0]}"
  for traffic in "${heaps[@]}"; do
    (( traffic > hi )) && hi="${traffic}"
    (( traffic < lo )) && lo="${traffic}"
  done
  spread=$(( hi - lo ))
  printf '%s\n' '--- last three STATS ---'
  printf '%s\n' "${lines[@]: -3}"
  printf 'max traffic rows: %s (cap %s)\n' "${max_traffic}" "${TRAFFIC_CAP}"
  printf 'heap after GC, last three: %s..%s MB (spread %s MB)\n' \
    "${lo}" "${hi}" "${spread}"
  if (( BASELINE )); then
    log 'baseline run: cap check skipped (annotation off)'
  elif (( max_traffic > TRAFFIC_CAP )); then
    err 'traffic store exceeded its cap'; rc=1
  elif (( max_traffic < TRAFFIC_CAP )); then
    err 'traffic store never reached its cap; raise --requests'; rc=1
  fi
  if (( spread * 100 > hi * HEAP_TOLERANCE_PERCENT )); then
    err "heap not settled: spread above ${HEAP_TOLERANCE_PERCENT}%"; rc=1
  fi
  return "${rc}"
}

stop_pid() {
  local pid="$1" label="$2" waited=0
  if [[ -z "${pid}" ]] || ! kill -0 "${pid}" 2>/dev/null; then return 0; fi
  log "stopping ${label} (pid ${pid})"
  kill -TERM "${pid}" 2>/dev/null || true
  while kill -0 "${pid}" 2>/dev/null; do
    if (( waited >= STOP_GRACE_SECONDS )); then
      err "${label} still running after ${STOP_GRACE_SECONDS}s; sending KILL"
      kill -KILL "${pid}" 2>/dev/null || true
      break
    fi
    sleep 1; waited=$(( waited + 1 ))
  done
  wait "${pid}" 2>/dev/null || true
  return 0
}

run_task() {
  local rc=0
  debug "run_task: dry_run=${DRY_RUN} verbose=${VERBOSE}"
  resolve_inputs
  write_configs
  build_command
  print_plan
  if (( DRY_RUN )); then log 'dry run: not launching anything'; return 0; fi

  start_echo_server || return 1
  : > "${LOG_FILE}"
  "${BURP_CMD[@]}" >> "${LOG_FILE}" 2>&1 &
  BURP_PID=$!
  log "Burp started headless (pid ${BURP_PID})"
  if wait_for_load && generate_traffic; then
    log "waiting $(( STATS_INTERVAL * 3 + 2 ))s for three settled samples"
    sleep "$(( STATS_INTERVAL * 3 + 2 ))"
    evaluate || rc=1
  else
    rc=1
  fi
  stop_pid "${BURP_PID}" 'Burp'; BURP_PID=''
  stop_pid "${ECHO_PID}" 'echo server'; ECHO_PID=''
  if (( rc == 0 )); then
    log 'RESULT: long session PASS'
  else
    err 'RESULT: long session FAIL'
  fi
  printf 'log: %s\n' "${LOG_FILE}"
  return "${rc}"
}

main() {
  if wants_help "$@"; then show_help; exit 0; fi
  check_requirements
  parse_args "$@"
  run_task
  return $?
}

main "$@"
