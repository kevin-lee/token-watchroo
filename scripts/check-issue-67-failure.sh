#!/usr/bin/env bash
# Decides whether a failed x86_64 release Test step failed only through #67, so that release.yml may rerun BridgeSpec
# once instead of failing the release.
#
#   scripts/check-issue-67-failure.sh <test-log> <reports-root>
#
# <test-log> is the whole output of `sbt --server "core/testFull; providers/testFull; app/testFull"`, which keeps the
# test processes' stderr. <reports-root> is searched for the JUnit reports (TEST-*.xml). It exits 0 only when all of
# these hold:
#   - the log has the three module summaries, and across them exactly one test failed or errored
#   - the BridgeSpec report exists, and every other report has no failure and no error
#   - the one BridgeSpec failure is one of #67's recorded shapes (runs 35631519713 and 35640141096):
#       - a NullPointerException with no frame beyond its constructor
#       - munit's "test timed out after 120 seconds"
#       - the test process killed by SIGSEGV at address 0x0: a RunTerminatedException error in the report, and in the
#         log exactly one "Unhandled signal" line, for signal 11 at si_addr=0x0, and exactly one process exit, the app
#         test binary's with value 11
# Anything else exits 1, so a new shape, a changed report or log format, or a failure in another suite fails the
# release. Remove this script and its steps in release.yml with build.yml's x86_64 tolerance when #67 is fixed.
#
# grep -c prints 0 and exits 1 on zero matches, so the script runs without set -e and keeps its own exit code.
set -u

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <test-log> <reports-root>" >&2
  exit 2
fi

LOG="$1"
ROOT="$2"
BRIDGE_NAME="TEST-tokenwatchroo.app.BridgeSpec.xml"

reject() {
  echo "not-67: $*"
  exit 1
}

[ -f "$LOG" ] || reject "no test log at $LOG"
[ -d "$ROOT" ] || reject "no report directory at $ROOT"

# The summaries carry ANSI colour codes around them, so only the counts are matched.
summaries=$(grep -o -E 'Total [0-9]+, Failed [0-9]+, Errors [0-9]+' "$LOG")
summary_count=$(printf '%s\n' "$summaries" | grep -c 'Total')
[ "$summary_count" -eq 3 ] || reject "expected 3 module summaries in the log, found $summary_count"
failed_total=$(printf '%s\n' "$summaries" | awk -F'[ ,]+' '{ sum += $4 + $6 } END { print sum + 0 }')
[ "$failed_total" -eq 1 ] || reject "expected exactly 1 failed or errored test, found $failed_total"

bridge=""
while IFS= read -r -d '' report; do
  tag=$(grep -m 1 -o '<testsuite [^>]*>' "$report")
  failures=$(printf '%s' "$tag" | sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p')
  errors=$(printf '%s' "$tag" | sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p')
  { [ -n "$failures" ] && [ -n "$errors" ]; } || reject "no failure and error counts in $report"
  if [ "$(basename "$report")" = "$BRIDGE_NAME" ]; then
    [ -z "$bridge" ] || reject "more than one $BRIDGE_NAME under $ROOT"
    bridge="$report"
    [ $((failures + errors)) -eq 1 ] || reject "BridgeSpec has $failures failures and $errors errors"
  elif [ $((failures + errors)) -ne 0 ]; then
    reject "$(basename "$report") has $failures failures and $errors errors"
  fi
done < <(find "$ROOT" -name 'TEST-*.xml' -print0)
[ -n "$bridge" ] || reject "no $BRIDGE_NAME under $ROOT"

body=$(sed -n -E '/<(failure|error)[ >]/,/<\/(failure|error)>/p' "$bridge")
body_lines=$(printf '%s\n' "$body" | grep -c '')
line() { printf '%s\n' "$body" | sed -n "${1}p"; }

if [ "$body_lines" -eq 3 ] &&
  line 1 | grep -q -E '<failure [^>]*>java\.lang\.NullPointerException$' &&
  line 2 | grep -q -E '^[[:space:]]*at java\.lang\.NullPointerException\.&lt;init&gt;\(Unknown Source\)$' &&
  line 3 | grep -q -E '^[[:space:]]*</failure>$'; then
  echo "ok issue67=NullPointerException $bridge"
  exit 0
fi

if line 1 | grep -q -F '<failure message="test timed out after 120 seconds"' &&
  line 1 | grep -q -F '>java.util.concurrent.TimeoutException: test timed out after 120 seconds'; then
  echo "ok issue67=timeout $bridge"
  exit 0
fi

# The $ is part of the class name NativeRunnerRPC$RunTerminatedException, not an expansion.
# TODO: REVIEWME: It should be reviewed by Kevin.
# shellcheck disable=SC2016
if line 1 | grep -q -F '<error message="scala.scalanative.testinterface.NativeRunnerRPC$RunTerminatedException"'; then
  signals=$(grep -c 'Unhandled signal' "$LOG")
  at_null=$(grep -c -E 'Unhandled signal 11, si_addr=0x0([^0-9a-fA-Fx]|$)' "$LOG")
  exits=$(grep -c 'finished with non-zero value' "$LOG")
  app_exit=$(grep -c -F 'tokenwatchroo-test finished with non-zero value 11 (0xb)' "$LOG")
  if [ "$signals" -eq 1 ] && [ "$at_null" -eq 1 ] && [ "$exits" -eq 1 ] && [ "$app_exit" -eq 1 ]; then
    echo "ok issue67=SIGSEGV-at-0x0 $bridge"
    exit 0
  fi
  reject "BridgeSpec's process died, but not by one SIGSEGV at 0x0: signals=$signals at_0x0=$at_null exits=$exits app_exit_11=$app_exit"
fi

reject "BridgeSpec failed with an unrecorded shape: $(line 1 | sed -E 's/^[[:space:]]+//' | cut -c1-200)"
