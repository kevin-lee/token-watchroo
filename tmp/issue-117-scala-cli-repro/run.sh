#!/usr/bin/env bash
# Run a reproducer binary many times and count the faults.
#   run.sh <binary> [runs=50] [seconds=20] [timeout=120] [roles]
# roles is passed to the binary, see Repro.scala. Without it the binary uses its default.
# A run is a hang when it is still alive at the timeout. The script then samples it (macOS sample) and kills it.
set -uo pipefail

bin=${1:?binary}
runs=${2:-50}
seconds=${3:-20}
timeout=${4:-120}
roles=${5:-}
logs="$(dirname "$bin")/../logs/$(basename "$bin")-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$logs"

pass=0; sigill=0; hang=0; other=0
for i in $(seq 1 "$runs"); do
  start=$(date +%s)
  "$bin" "$seconds" ${roles:+"$roles"} > "$logs/run-$i.log" 2>&1 &
  pid=$!
  while kill -0 "$pid" 2>/dev/null && [ $(( $(date +%s) - start )) -lt "$timeout" ]; do sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then
    sample "$pid" 5 -file "$logs/sample-$i.txt" >/dev/null 2>&1
    kill -9 "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    hang=$((hang + 1)); result="hang"
  else
    wait "$pid"; code=$?
    case "$code" in
      0) pass=$((pass + 1)); result="pass" ;;
      132) sigill=$((sigill + 1)); result="SIGILL" ;;
      *) other=$((other + 1)); result="exit $code" ;;
    esac
  fi
  echo "run $i: $result after $(( $(date +%s) - start )) s"
done
echo "summary $(basename "$bin") roles=${roles:-default}: runs=$runs pass=$pass sigill=$sigill hang=$hang other=$other logs=$logs"
