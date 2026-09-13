#!/usr/bin/env bash
# Builds Token-Watchroo-<version>-<arch>.dmg holding the app and an Applications symlink with hdiutil and signs the
# image when TW_SIGNING_IDENTITY is set.
#
# usage: scripts/make-dmg.sh <app-bundle> <out-dir>
#
# The app should already be signed and stapled (scripts/notarize.sh). The version comes from the bundle's
# CFBundleShortVersionString and the architecture from the executable (arm64 or x64), so the name matches what the
# Homebrew cask expects. No checksum is written here: the image is notarized next and xcrun stapler rewrites it, so a
# checksum taken now would never match what is published. Take it with scripts/checksum.sh after stapling.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <app-bundle> <out-dir>" >&2
  exit 2
fi

APP="$1"
OUT_DIR="$2"

if [ ! -d "$APP" ] || [ ! -f "$APP/Contents/Info.plist" ]; then
  echo "error: not an app bundle: $APP" >&2
  exit 1
fi
mkdir -p "$OUT_DIR"

VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"
EXECUTABLE="$APP/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")"

RAW_ARCH="$(lipo -archs "$EXECUTABLE")"
case "$RAW_ARCH" in
  arm64) ARCH="arm64" ;;
  x86_64) ARCH="x64" ;;
  *)
    echo "error: unsupported architecture: $RAW_ARCH" >&2
    exit 1
    ;;
esac

DMG="$OUT_DIR/Token-Watchroo-$VERSION-$ARCH.dmg"
VOLUME_NAME="Token Watchroo"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

ditto "$APP" "$STAGE/$(basename "$APP")"
ln -s /Applications "$STAGE/Applications"

rm -f "$DMG"
echo "==> creating $DMG"
hdiutil create -volname "$VOLUME_NAME" -srcfolder "$STAGE" -ov -format UDZO "$DMG"

if [ -n "${TW_SIGNING_IDENTITY:-}" ]; then
  echo "==> codesign $DMG"
  codesign --force --timestamp --sign "$TW_SIGNING_IDENTITY" "$DMG"
  codesign --verify --verbose=2 "$DMG"
else
  echo "==> TW_SIGNING_IDENTITY is not set: the image is not signed"
fi

echo "Done. Generated: $DMG"
