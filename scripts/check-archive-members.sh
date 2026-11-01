#!/usr/bin/env bash
# Looks for signs of stale members in the Scala Native static library (#87).
#
#   scripts/check-archive-members.sh <archive>
#
# On macOS without llvm-ar, Scala Native 0.5.12 links the archive with `ar rc`, which adds to an existing file, so old
# members stay. curl_write_buffer.c.o has the sbt-dynver version in its name, so each version adds a copy, and a stale
# copy of code defines the same symbols as the current one. Archives with stale generated_*.ll.o members crashed or hung
# at launch.
#
# Both results are signs of stale members, not a prediction of a crash, and why a stale archive fails is not proved:
# archives with 2 and 3 curl copies launched fine. A stale member with unique symbols and no new curl copy is not found
# here. scripts/check-launch.sh is what proves that the app starts.
#
# The script runs without set -e and keeps its own exit code. The tools run before their output goes through a pipe,
# because a pipe would hide their exit codes.
set -u

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <archive>" >&2
  exit 2
fi

file="$1"
if [ ! -f "$file" ]; then
  echo "MISSING $file"
  exit 1
fi

if ! members=$(ar t "$file" 2>/dev/null); then
  echo "FAILED ar t $file"
  exit 1
fi
if ! symbols=$(nm -g -U "$file" 2>/dev/null); then
  echo "FAILED nm $file"
  exit 1
fi

curl=$(printf '%s\n' "$members" | grep -c 'curl_write_buffer\.c\.o')
duplicates=$(printf '%s\n' "$symbols" | awk 'NF==3{print $3}' | sort | uniq -d | wc -l | tr -d ' ')

# No copy at all is refused too: then the file is not this library.
if [ "$curl" -eq 1 ] && [ "$duplicates" -eq 0 ]; then
  verdict=ok
  rc=0
else
  verdict=STALE
  rc=1
fi
echo "$verdict curl_write_buffer=$curl duplicate_symbols=$duplicates $file"
exit "$rc"
