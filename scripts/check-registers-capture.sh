#!/usr/bin/env bash
# Proves with nm that Scala Native archives and executables were compiled with this repository's patched
# RegistersCapture.h (#63).
#
#   scripts/check-registers-capture.sh <file>...
#
# Scala Native 0.5.12's RegistersCapture.h takes its buffer by value on x86 and x86_64, so the GC never scans the
# callee-saved registers of a thread that goes Unmanaged and frees objects that only a register refers to (#63,
# upstream scala-native/scala-native#5048). build.sbt puts native-overrides/scala-native-0.5.12 on the C include path
# ahead of nativelib's own directories, and the copy there defines the static symbol tw_registers_capture_override in
# every GC file that includes it. A file without that symbol was compiled with the stock header, or has no GC. Remove
# this script with native-overrides/, the -I option and the checks in build.sbt, and the CI steps when a Scala Native
# release includes scala-native#5048.
#
# grep -c prints 0 and exits 1 on zero matches, so the script runs without set -e and keeps its own exit code.
set -u

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <file>..." >&2
  exit 2
fi

rc=0
for file in "$@"; do
  if [ ! -f "$file" ]; then
    echo "MISSING $file"
    rc=1
    continue
  fi
  marker=$(nm "$file" 2>/dev/null | grep -c 'tw_registers_capture_override$')
  if [ "$marker" -gt 0 ]; then verdict=ok; else verdict=MISMATCH; rc=1; fi
  echo "$verdict registers_capture_override=$marker $file"
done
exit "$rc"
