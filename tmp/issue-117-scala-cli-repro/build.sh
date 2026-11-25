#!/usr/bin/env bash
# Build one arm of the reproducer.
#   build.sh trap|conditional [host|x86_64]
# x86_64 cross-builds on an arm64 Mac through clang wrappers that add -target, for Rosetta 2.
# Each arm and architecture has its own workspace, because a change of the environment variable alone does not relink.
set -euo pipefail

arm=${1:?trap or conditional}
arch=${2:-host}
here=$(cd "$(dirname "$0")" && pwd)

case "$arm" in
  trap) mode=1 ;;
  conditional) mode=0 ;;
  *) echo "unknown arm: $arm" >&2; exit 2 ;;
esac

work="$here/work/$arm-$arch"
mkdir -p "$work" "$here/bin"
cp "$here/Repro.scala" "$work/Repro.scala"

clang_opts=()
if [ "$arch" = x86_64 ]; then
  wrappers="$here/work/clang-x86_64"
  mkdir -p "$wrappers"
  for tool in clang clang++; do
    printf '#!/bin/sh\nexec /usr/bin/xcrun %s -target x86_64-apple-macos14 "$@"\n' "$tool" > "$wrappers/$tool"
    chmod +x "$wrappers/$tool"
  done
  clang_opts=(--native-clang "$wrappers/clang" --native-clangpp "$wrappers/clang++")
fi

out="$here/bin/repro-$arm-$arch"
SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=$mode \
  scala-cli --power package "$work/Repro.scala" --server=false --workspace "$work" \
  ${clang_opts[@]+"${clang_opts[@]}"} -o "$out" -f

# The trap mode links scalanative_GC_yieldpoint_trap, the conditional mode does not.
if nm "$out" | grep -q scalanative_GC_yieldpoint_trap; then linked=trap; else linked=conditional; fi
echo "$out: $(file -b "$out" | cut -d' ' -f1-3), yieldpoints $linked"
[ "$linked" = "$arm" ] || { echo "mode check failed: wanted $arm" >&2; exit 1; }
