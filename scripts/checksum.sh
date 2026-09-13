#!/usr/bin/env bash
# Writes <file>.sha256 next to a file, in the format shasum -c reads.
#
# usage: scripts/checksum.sh <file>
#
# Run this on the final bytes of an artifact. For a disk image that means after scripts/notarize.sh, because xcrun
# stapler rewrites the image to embed the ticket: a checksum taken before stapling does not match what is published.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <file>" >&2
  exit 2
fi

TARGET="$1"
if [ ! -f "$TARGET" ]; then
  echo "error: not a file: $TARGET" >&2
  exit 1
fi

DIR="$(cd "$(dirname "$TARGET")" && pwd)"
NAME="$(basename "$TARGET")"

(cd "$DIR" && shasum -a 256 "$NAME" > "$NAME.sha256" && shasum -a 256 -c "$NAME.sha256")

echo "Done. Generated: $DIR/$NAME.sha256"
