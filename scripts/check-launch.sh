#!/usr/bin/env bash
# For CI. Proves that a bundle starts: core.start returned 0, and the process is still alive 5 seconds later (#87).
#
#   scripts/check-launch.sh <app>
#
# A process that is alive proves nothing, because a hang inside core.start stays alive too. So the script waits for the
# line that AppDelegate writes after core.start returned, with this run's process ID, so that a line from an earlier
# launch cannot pass.
#
# The app gets only HOME and PATH. It does not prove that a refresh works, because a runner has no credentials. It uses
# the real state and log folders of the user, so on a Mac it reads the real credentials. It refuses to run beside a
# running copy.
set -u

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <app>" >&2
  exit 2
fi

EXE="$1/Contents/MacOS/TokenWatchroo"
LOG="$HOME/Library/Logs/Token Watchroo/token-watchroo.log"
START_LIMIT=60
ALIVE_AFTER=5

if [ ! -x "$EXE" ]; then
  echo "error: no executable at $EXE"
  exit 1
fi
if pgrep -x TokenWatchroo >/dev/null; then
  echo "error: a TokenWatchroo process is running. Quit it first."
  exit 1
fi

OUT="$(mktemp)"
SAMPLE="$(mktemp)"
env -i HOME="$HOME" PATH=/usr/bin:/bin "$EXE" </dev/null >"$OUT" 2>&1 &
PID=$!
trap 'kill -KILL "$PID" 2>/dev/null' EXIT

print_evidence() {
  echo '--- process output'
  cat "$OUT"
  echo '--- log'
  cat "$LOG" 2>/dev/null
}

crashed() {
  wait "$PID"
  echo "CRASHED exit=$? after $SECONDS s"
  print_evidence
  exit 1
}

SECONDS=0
while ! grep -qF "tw_start returned 0 pid=$PID" "$LOG" 2>/dev/null; do
  kill -0 "$PID" 2>/dev/null || crashed
  if [ "$SECONDS" -ge "$START_LIMIT" ]; then
    sample "$PID" 2 -file "$SAMPLE" >/dev/null 2>&1
    echo "HUNG: no start line after $START_LIMIT s"
    print_evidence
    echo '--- sample'
    head -n 60 "$SAMPLE"
    exit 1
  fi
  sleep 1
done
STARTED=$SECONDS

sleep "$ALIVE_AFTER"
kill -0 "$PID" 2>/dev/null || crashed

kill -TERM "$PID" 2>/dev/null
for _ in 1 2 3 4 5; do
  kill -0 "$PID" 2>/dev/null || break
  sleep 1
done
echo "ok started in $STARTED s and alive $ALIVE_AFTER s later"
exit 0
