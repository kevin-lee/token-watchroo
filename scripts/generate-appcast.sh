#!/usr/bin/env bash
# Signs one DMG and writes its one-item signed feed with Sparkle's generate_appcast (#71). One feed per DMG, because the
# arm64 and x64 images carry the same version.
#
# usage: printf '%s' "$KEY" | scripts/generate-appcast.sh <tools-bin> <dmg> <notes.md> <download-url-prefix> <out.xml>
#
# The private key comes from standard input only and passes straight through to generate_appcast. Never add set -x here.
# generate_appcast only warns about a key that does not match the app's SUPublicEDKey and exits with 0, so always run
# scripts/check-appcast.sh on the result.
set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "usage: $0 <tools-bin> <dmg> <notes.md> <download-url-prefix> <out.xml>" >&2
  exit 2
fi

TOOLS="$1"
DMG="$2"
NOTES="$3"
PREFIX="$4"
OUT="$5"

for file in "$TOOLS/generate_appcast" "$DMG" "$NOTES"; do
  if [ ! -f "$file" ]; then
    echo "error: not found: $file" >&2
    exit 1
  fi
done

# -o is resolved by the tool, so give it an absolute path.
OUT_ABSOLUTE="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

# The notes are found by having the DMG's base name.
BASE="$(basename "$DMG" .dmg)"
cp "$DMG" "$STAGE/$BASE.dmg"
cp "$NOTES" "$STAGE/$BASE.md"

"$TOOLS/generate_appcast" --ed-key-file - --embed-release-notes --maximum-deltas 0 \
  --download-url-prefix "$PREFIX" --link https://github.com/kevin-lee/token-watchroo \
  -o "$OUT_ABSOLUTE" "$STAGE"
