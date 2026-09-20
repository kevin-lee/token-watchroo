#!/usr/bin/env bash
# Proves the GC yieldpoint mode of Scala Native archives and executables with nm.
#
#   scripts/check-yieldpoints.sh <conditional|trap> <file>...
#
# Scala Native 0.5.12 picks the mode at link time from SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS, and its incremental
# check ignores the variable, so a stale target keeps the old mode. Every binary of this build is linked with
# conditional yieldpoints because the 0.5.12 GC loses a root of a thread stopped by a trap-based yieldpoint and frees
# live objects (#44, upstream scala-native/scala-native#5046). Remove this script together with the onLoad check and
# the gates in build.sbt and the CI env when a Scala Native release fixes #5046.
#
# trap: scalanative_gc_safepoint_poll_trampoline present.
# conditional: that symbol, the scalanative_GC_yieldpoint_trap thread-local and SafepointTrapHandler all absent, and
#   the GC present (Synchronizer_stopThreads, or the scalanative_gc_safepoint_trampoline_<arch>_skipped stub), so an
#   empty or unrelated file cannot pass. The stub survives in archives and in Scala Native's own test executables,
#   but SwiftPM dead-strips it from the bundle executable, which is why either GC symbol is accepted.
#
# grep -c prints 0 and exits 1 on zero matches, so the script runs without set -e and keeps its own exit code.
set -u

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <conditional|trap> <file>..." >&2
  exit 2
fi

mode="$1"
shift
case "$mode" in
  conditional | trap) ;;
  *)
    echo "error: unknown mode '$mode', expected conditional or trap" >&2
    exit 2
    ;;
esac

rc=0
for file in "$@"; do
  if [ ! -f "$file" ]; then
    echo "MISSING $file"
    rc=1
    continue
  fi
  symbols=$(nm "$file" 2>/dev/null)
  poll=$(printf '%s\n' "$symbols" | grep -c 'scalanative_gc_safepoint_poll_trampoline')
  tls=$(printf '%s\n' "$symbols" | grep -c 'scalanative_GC_yieldpoint_trap$')
  handler=$(printf '%s\n' "$symbols" | grep -c 'SafepointTrapHandler$')
  skipped=$(printf '%s\n' "$symbols" | grep -c 'scalanative_gc_safepoint_trampoline_.*_skipped$')
  gc=$(printf '%s\n' "$symbols" | grep -c 'Synchronizer_stopThreads$')
  case "$mode" in
    trap)
      if [ "$poll" -gt 0 ]; then verdict=ok; else verdict=MISMATCH; rc=1; fi
      ;;
    conditional)
      if [ "$poll" -eq 0 ] && [ "$tls" -eq 0 ] && [ "$handler" -eq 0 ] && { [ "$gc" -gt 0 ] || [ "$skipped" -gt 0 ]; }; then
        verdict=ok
      else
        verdict=MISMATCH
        rc=1
      fi
      ;;
  esac
  echo "$verdict mode=$mode poll_trampoline=$poll yieldpoint_trap_tls=$tls trap_handler=$handler skipped_stub=$skipped gc=$gc $file"
done
exit "$rc"
