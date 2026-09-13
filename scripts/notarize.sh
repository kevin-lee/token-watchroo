#!/usr/bin/env bash
# Submits an app bundle or a disk image to Apple's notary service with notarytool, waits for the verdict, staples the
# ticket, and validates it.
#
# usage: scripts/notarize.sh <path-to.app-or.dmg>
#
# Credentials are an App Store Connect API key read from three environment variables: APPLE_API_KEY_P8_PATH (path to
# the .p8 file), APPLE_API_KEY_ID, and APPLE_API_ISSUER_ID. Run this on the signed app first, then on the disk image
# built from the stapled app (scripts/make-dmg.sh), so both carry a stapled ticket and validate offline.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <path-to.app-or.dmg>" >&2
  exit 2
fi

for required in APPLE_API_KEY_P8_PATH APPLE_API_KEY_ID APPLE_API_ISSUER_ID; do
  if [ -z "${!required:-}" ]; then
    echo "error: $required is not set" >&2
    exit 1
  fi
done

if [ ! -f "$APPLE_API_KEY_P8_PATH" ]; then
  echo "error: API key file not found: $APPLE_API_KEY_P8_PATH" >&2
  exit 1
fi

TARGET="$1"
if [ ! -e "$TARGET" ]; then
  echo "error: not found: $TARGET" >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

case "$TARGET" in
  *.app)
    ditto -c -k --keepParent "$TARGET" "$TMP/upload.zip"
    UPLOAD="$TMP/upload.zip"
    ;;
  *.dmg)
    UPLOAD="$TARGET"
    ;;
  *)
    echo "error: expected a .app or .dmg: $TARGET" >&2
    exit 1
    ;;
esac

echo "==> submitting $(basename "$TARGET") to the notary service"
xcrun notarytool submit "$UPLOAD" \
  --key "$APPLE_API_KEY_P8_PATH" --key-id "$APPLE_API_KEY_ID" --issuer "$APPLE_API_ISSUER_ID" \
  --wait 2>&1 | tee "$TMP/submit.log"

SUBMISSION_ID="$(awk '/^  id: /{print $2; exit}' "$TMP/submit.log")"
if ! grep -q "status: Accepted" "$TMP/submit.log"; then
  if [ -n "$SUBMISSION_ID" ]; then
    echo "==> notary log for $SUBMISSION_ID"
    xcrun notarytool log "$SUBMISSION_ID" \
      --key "$APPLE_API_KEY_P8_PATH" --key-id "$APPLE_API_KEY_ID" --issuer "$APPLE_API_ISSUER_ID" || true
  fi
  echo "error: notarization failed" >&2
  exit 1
fi

echo "==> stapling"
xcrun stapler staple "$TARGET"
xcrun stapler validate "$TARGET"

echo "Done. Notarized and stapled: $TARGET"
