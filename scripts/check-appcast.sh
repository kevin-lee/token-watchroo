#!/usr/bin/env bash
# Checks a feed written by scripts/generate-appcast.sh (#71) without the private key. In particular it fails on an
# enclosure without sparkle:edSignature, which is what generate_appcast writes, with exit code 0, for a key that does not
# match the app's SUPublicEDKey.
#
# usage: scripts/check-appcast.sh <feed.xml> <dmg> <version> <arm64|x64> <download-url-prefix>
set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "usage: $0 <feed.xml> <dmg> <version> <arm64|x64> <download-url-prefix>" >&2
  exit 2
fi

FEED="$1"
DMG="$2"
VERSION="$3"
ARCH="$4"
PREFIX="$5"

fail() {
  echo "error: $FEED: $1" >&2
  exit 1
}

ITEMS="$(grep -c '<item>' "$FEED" || true)"
[ "$ITEMS" = "1" ] || fail "expected exactly one item, found $ITEMS"

grep -q "<sparkle:version>$VERSION</sparkle:version>" "$FEED" || fail "no sparkle:version $VERSION"
grep -q '<sparkle:minimumSystemVersion>14.0</sparkle:minimumSystemVersion>' "$FEED" || fail "no minimumSystemVersion 14.0"

ENCLOSURE="$(grep '<enclosure ' "$FEED" || true)"
[ -n "$ENCLOSURE" ] || fail "no enclosure"
grep -q -F "url=\"$PREFIX$(basename "$DMG")\"" <<<"$ENCLOSURE" || fail "the enclosure URL is not $PREFIX$(basename "$DMG")"
grep -q "length=\"$(stat -f %z "$DMG")\"" <<<"$ENCLOSURE" || fail "the enclosure length is not the size of $DMG"
grep -q 'sparkle:edSignature="' <<<"$ENCLOSURE" || fail "the enclosure has no sparkle:edSignature, is the key the one in the app?"

grep -q 'sparkle-signatures:' "$FEED" || fail "the feed is not signed"

case "$ARCH" in
  arm64)
    grep -q '<sparkle:hardwareRequirements>arm64</sparkle:hardwareRequirements>' "$FEED" || fail "no arm64 hardware requirement"
    ;;
  x64)
    if grep -q 'sparkle:hardwareRequirements' "$FEED"; then
      fail "an x64 feed must have no hardware requirement"
    fi
    ;;
  *)
    echo "error: unsupported architecture: $ARCH" >&2
    exit 2
    ;;
esac

echo "Feed is correct for $ARCH: $FEED"
