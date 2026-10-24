#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Script:       burp-headless-selftest.sh
# Description:  Runs the CanaryAgent self-test JAR in a headless Burp
#               Suite Pro, waits for the RESULT line, stops that Burp,
#               and reports. Dev helper; never part of the extension.
# Usage:        burp-headless-selftest.sh [-h|--help] [-n|--dry-run]
#                 [-v|--verbose] [--burp-home DIR] [--jar FILE]
#                 [--port N] [--timeout SECONDS]
#               BURP_HOME may be set instead of --burp-home.
# Dependencies: bash >= 4.4, mktemp, grep, kill, sleep, date
# Notes:        Flags confirmed from `burpsuite.jar --help` (2026-10-22):
#               --use-defaults --user-config-file --config-file
#               --disable-auto-update. Not used yet: --data-dir (would
#               isolate the run from ~/.BurpSuite) and
#               --developer-extension-class-name (classpath loading).
#               The listener JSON shape was verified against a project
#               settings export on 2026-10-22 ("proxy" is top-level).
#               Burp prints its own "no ComponentUI class" errors and a
#               NullPointerException while initialising its tools UI under
#               the headless flag; they are Burp's, and the run proceeds.
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
readonly PROP_AUTORUN='canaryagent.selftest.autorun'
readonly PROP_PORT='canaryagent.selftest.proxyPort'
# Headless Burp never renders the settings panel, so "Automatically flag"
# reads as unset (false) and A5/A8 would skip; the self-test entry point
# forces it on when this property is true. All other settings read live.
readonly PROP_FORCE_MATCHING='canaryagent.selftest.forceMatching'
# --unload-check: the self-test unloads its own extension after RESULT so
# the unloading handlers really run (SIGTERM alone never runs them); the
# script then waits for "Unloaded.", sends SIGQUIT for a JVM thread dump
# into the log, and fails if any "CanaryAgent-" thread is still alive.
readonly PROP_UNLOAD_AFTER_RUN='canaryagent.selftest.unloadAfterRun'
readonly UNLOAD_SETTLE_SECONDS=3
readonly DUMP_WAIT_SECONDS=15
readonly POLL_SECONDS=2
readonly STOP_GRACE_SECONDS=30

RED=$'\033[0;31m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'
if [[ ! -t 1 || ! -t 2 ]]; then RED=''; CYAN=''; NC=''; fi

DEBUG="${DEBUG:-0}"
VERBOSE=0
DRY_RUN=0
TMP_DIR=''
BURP_PID=''
BURP_HOME="${BURP_HOME:-}"
SELFTEST_JAR=''
PORT=8089
TIMEOUT=300
LOG_FILE=''
USE_DEFAULTS=1
PROJECT_FILE=''
UNLOAD_CHECK=0

err()   { printf '%b[ERROR]%b %s\n' "${RED}" "${NC}" "$*" >&2; }
log()   { printf '%b[INFO]%b %s\n' "${CYAN}" "${NC}" "$*"; }
debug() {
  if [[ "${DEBUG}" == 1 ]]; then printf '[DEBUG] %s\n' "$*" >&2; fi
  return 0
}

show_help() {
  printf '%s\n' \
    "Usage: ${SCRIPT_NAME} [-h|--help] [-n|--dry-run] [-v|--verbose]" \
    "         [--burp-home DIR] [--jar FILE] [--port N] [--timeout SECONDS]" \
    '         [--no-use-defaults] [--project-file FILE] [--unload-check]' \
    '' \
    '--unload-check     after RESULT, have the extension unload itself,' \
    '                   wait for "Unloaded.", take a JVM thread dump' \
    '                   (SIGQUIT) into the log, fail if a CanaryAgent-' \
    '                   thread survives' \
    '--no-use-defaults  omit --use-defaults (Burp then also applies its' \
    '                   own stored user settings, GUI extension list too)' \
    '--project-file     open or create this Burp project file (inside the' \
    '                   repo; build/ is gitignored) instead of a temporary' \
    '                   project, so project settings survive between runs' \
    '' \
    'Runs the newest build/libs/canaryagent-selftest-N.jar (or --jar) in a' \
    'headless Burp Suite Pro found at --burp-home (or BURP_HOME), with a' \
    'Proxy listener on 127.0.0.1:PORT (default 8089), waits for the' \
    'self-test RESULT line (default timeout 300 s), stops that Burp and' \
    'prints the RESULT line and the log path (build/harness-N.log).' \
    '' \
    'Exit: 0 = RESULT with 0 fail, 1 = failures, timeout or Burp exited,' \
    '      2 = bad arguments, 127 = missing command.'
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
  for cmd in mktemp grep kill sleep date; do
    command -v "${cmd}" >/dev/null 2>&1 || missing+=("${cmd}")
  done
  if (( ${#missing[@]} > 0 )); then
    for cmd in "${missing[@]}"; do err "missing required command: ${cmd}"; done
    exit 127
  fi
  return 0
}

parse_args() {
  while (( $# > 0 )); do
    case "$1" in
      -n|--dry-run) DRY_RUN=1 ;;
      -v|--verbose) VERBOSE=1 ;;
      --burp-home)
        if (( $# < 2 )); then err '--burp-home needs a directory'; exit 2; fi
        BURP_HOME="$2"; shift ;;
      --jar)
        if (( $# < 2 )); then err '--jar needs a file'; exit 2; fi
        SELFTEST_JAR="$2"; shift ;;
      --port)
        if (( $# < 2 )); then err '--port needs a number'; exit 2; fi
        PORT="$2"; shift ;;
      --timeout)
        if (( $# < 2 )); then err '--timeout needs seconds'; exit 2; fi
        TIMEOUT="$2"; shift ;;
      --no-use-defaults) USE_DEFAULTS=0 ;;
      --unload-check) UNLOAD_CHECK=1 ;;
      --project-file)
        if (( $# < 2 )); then err '--project-file needs a file'; exit 2; fi
        PROJECT_FILE="$2"; shift ;;
      *) err "unknown argument: $1"; show_help >&2; exit 2 ;;
    esac
    shift
  done
  return 0
}

cleanup() {
  if [[ -n "${BURP_PID}" ]] && kill -0 "${BURP_PID}" 2>/dev/null; then
    log "stopping Burp (pid ${BURP_PID})"
    kill -TERM "${BURP_PID}" 2>/dev/null || true
  fi
  if [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]]; then rm -rf -- "${TMP_DIR}"; fi
  return 0
}
trap cleanup EXIT INT TERM

# Validates inputs and resolves defaults. Sets BURP_JAVA, BURP_JAR,
# SELFTEST_JAR and LOG_FILE.
resolve_inputs() {
  local candidate number best_number=-1 best=''
  local build_libs="${REPO_ROOT}/build/libs"
  local log_dir="${REPO_ROOT}/build"
  local existing n max=0

  if [[ -z "${BURP_HOME}" ]]; then
    err 'Burp home not set: pass --burp-home DIR or export BURP_HOME'
    exit 2
  fi
  if [[ ! -d "${BURP_HOME}" ]]; then
    err "Burp home is not a directory: ${BURP_HOME}"
    exit 2
  fi
  BURP_JAVA="${BURP_HOME}/jre/bin/java"
  if [[ ! -x "${BURP_JAVA}" ]]; then
    err "bundled JRE not found: ${BURP_JAVA}"
    exit 2
  fi
  if [[ -f "${BURP_HOME}/burpsuite.jar" ]]; then
    BURP_JAR="${BURP_HOME}/burpsuite.jar"
  elif [[ -f "${BURP_HOME}/burpsuite_pro.jar" ]]; then
    BURP_JAR="${BURP_HOME}/burpsuite_pro.jar"
  else
    err "no burpsuite.jar or burpsuite_pro.jar in ${BURP_HOME}"
    exit 2
  fi
  readonly BURP_JAVA BURP_JAR

  if [[ ! "${PORT}" =~ ^[0-9]+$ ]] || (( PORT < 1024 || PORT > 65535 )); then
    err "--port must be 1024..65535 (got ${PORT})"
    exit 2
  fi
  if [[ ! "${TIMEOUT}" =~ ^[0-9]+$ ]] || (( TIMEOUT < 30 )); then
    err "--timeout must be a number of seconds >= 30 (got ${TIMEOUT})"
    exit 2
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
    err "self-test JAR not found: ${SELFTEST_JAR}"
    exit 2
  fi
  local jar_dir
  jar_dir="$(cd "$(dirname "${SELFTEST_JAR}")" && pwd -P)"
  SELFTEST_JAR="${jar_dir}/${SELFTEST_JAR##*/}"
  if [[ "${SELFTEST_JAR}" != "${REPO_ROOT}"/* ]]; then
    err "self-test JAR must live inside the repo: ${SELFTEST_JAR}"
    exit 2
  fi

  if [[ -n "${PROJECT_FILE}" ]]; then
    local project_dir
    project_dir="$(cd "$(dirname "${PROJECT_FILE}")" 2>/dev/null && pwd -P)" \
      || { err "project file directory missing: ${PROJECT_FILE}"; exit 2; }
    PROJECT_FILE="${project_dir}/${PROJECT_FILE##*/}"
    if [[ "${PROJECT_FILE}" != "${REPO_ROOT}"/* ]]; then
      err "project file must live inside the repo: ${PROJECT_FILE}"
      exit 2
    fi
  fi

  for existing in "${log_dir}"/harness-*.log; do
    [[ -f "${existing}" ]] || continue
    n="${existing##*/harness-}"; n="${n%.log}"
    if [[ "${n}" =~ ^[0-9]+$ ]] && (( n > max )); then max="${n}"; fi
  done
  LOG_FILE="${log_dir}/harness-$(( max + 1 )).log"
  return 0
}

# Writes the two Burp settings files into TMP_DIR.
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
  # Shape verified against a Burp Pro 2026.9.2 project-settings export:
  # "proxy" is a top-level key, beside "project_options", not inside it.
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
    "-D${PROP_AUTORUN}=true"
    "-D${PROP_PORT}=${PORT}"
    "-D${PROP_FORCE_MATCHING}=true"
  )
  # JVM properties must precede -jar; anything after it is an argument to Burp.
  if (( UNLOAD_CHECK )); then BURP_CMD+=("-D${PROP_UNLOAD_AFTER_RUN}=true"); fi
  BURP_CMD+=('-jar' "${BURP_JAR}")
  if (( USE_DEFAULTS )); then BURP_CMD+=('--use-defaults'); fi
  if [[ -n "${PROJECT_FILE}" ]]; then
    BURP_CMD+=("--project-file=${PROJECT_FILE}")
  fi
  BURP_CMD+=(
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
  log "listener: 127.0.0.1:${PORT}  timeout: ${TIMEOUT}s"
  log "use-defaults: ${USE_DEFAULTS}"
  log "project file: ${PROJECT_FILE:-(temporary)}"
  log "unload check: ${UNLOAD_CHECK}"
  log "log: ${LOG_FILE}"
  if (( VERBOSE || DRY_RUN )); then
    printf '%s\n' '--- user options ---'; cat "${USER_CFG}"
    printf '%s\n' '--- project options ---'; cat "${PROJECT_CFG}"
    printf '%s\n' '--- command ---'
    for part in "${BURP_CMD[@]}"; do printf '  %s\n' "${part}"; done
  fi
  return 0
}

# Waits for the RESULT line; returns 0 when found, 1 on timeout or exit.
wait_for_result() {
  local deadline now
  deadline=$(( $(date +%s) + TIMEOUT ))
  while :; do
    if grep -q 'RESULT:' "${LOG_FILE}" 2>/dev/null; then return 0; fi
    if ! kill -0 "${BURP_PID}" 2>/dev/null; then
      err 'Burp exited before a RESULT line; see the log'
      return 1
    fi
    now=$(date +%s)
    if (( now >= deadline )); then
      err "no RESULT line after ${TIMEOUT}s; see the log"
      return 1
    fi
    sleep "${POLL_SECONDS}"
  done
}

# Waits for "Unloaded.", dumps threads via SIGQUIT, checks for survivors.
# Returns 0 when the handler ran and no CanaryAgent- thread remains.
unload_check() {
  local waited=0 survivors
  log 'waiting for the extension to unload itself'
  until grep -q '\[CanaryAgent\] Unloaded\.' "${LOG_FILE}" 2>/dev/null; do
    if ! kill -0 "${BURP_PID}" 2>/dev/null; then
      err 'Burp exited before "Unloaded."; the unload handler did not run'
      return 1
    fi
    if (( waited >= TIMEOUT )); then
      err "no \"Unloaded.\" line after ${TIMEOUT}s"
      return 1
    fi
    sleep "${POLL_SECONDS}"; waited=$(( waited + POLL_SECONDS ))
  done
  log "unload handler ran; settling ${UNLOAD_SETTLE_SECONDS}s, then thread dump"
  sleep "${UNLOAD_SETTLE_SECONDS}"
  kill -QUIT "${BURP_PID}" 2>/dev/null || true
  waited=0
  until grep -q 'Full thread dump' "${LOG_FILE}" 2>/dev/null; do
    if (( waited >= DUMP_WAIT_SECONDS )); then
      err "no thread dump in the log ${DUMP_WAIT_SECONDS}s after SIGQUIT"
      return 1
    fi
    sleep 1; waited=$(( waited + 1 ))
  done
  sleep 1
  survivors="$(grep -E '^"CanaryAgent-' "${LOG_FILE}" || true)"
  if [[ -n "${survivors}" ]]; then
    err 'threads still alive after unload:'
    printf '%s\n' "${survivors}" >&2
    return 1
  fi
  log 'thread dump taken: no CanaryAgent- thread survives the unload'
  return 0
}

stop_burp() {
  local waited=0
  if ! kill -0 "${BURP_PID}" 2>/dev/null; then BURP_PID=''; return 0; fi
  log "stopping Burp (pid ${BURP_PID})"
  kill -TERM "${BURP_PID}" 2>/dev/null || true
  while kill -0 "${BURP_PID}" 2>/dev/null; do
    if (( waited >= STOP_GRACE_SECONDS )); then
      err "Burp alive ${STOP_GRACE_SECONDS}s after SIGTERM; sending KILL"
      kill -KILL "${BURP_PID}" 2>/dev/null || true
      break
    fi
    sleep 1; waited=$(( waited + 1 ))
  done
  wait "${BURP_PID}" 2>/dev/null || true
  BURP_PID=''
  return 0
}

report() {
  local result
  result="$(grep -m1 'RESULT:' "${LOG_FILE}" || true)"
  printf '%s\n' "${result:-RESULT: (none)}"
  printf 'log: %s\n' "${LOG_FILE}"
  if [[ "${result}" == *' 0 fail,'* ]]; then return 0; fi
  return 1
}

run_task() {
  local rc=0
  debug "run_task: dry_run=${DRY_RUN} verbose=${VERBOSE}"
  resolve_inputs
  write_configs
  build_command
  print_plan
  if (( DRY_RUN )); then log 'dry run: not launching Burp'; return 0; fi

  : > "${LOG_FILE}"
  "${BURP_CMD[@]}" >> "${LOG_FILE}" 2>&1 &
  BURP_PID=$!
  log "Burp started headless (pid ${BURP_PID}); waiting for RESULT"
  wait_for_result || rc=1
  if (( UNLOAD_CHECK && rc == 0 )); then unload_check || rc=1; fi
  stop_burp
  report || rc=1
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
