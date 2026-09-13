#!/usr/bin/env bash
# Assembles dist/Token Watchroo.app from the SwiftPM executable and ad-hoc signs it.
#
# usage: scripts/bundle-app.sh <executable> <out-dir> <version> <bundle-id>
#
# A real .app bundle is mandatory: UNUserNotificationCenter aborts the process outside a bundle, and
# SMAppService (Launch at Login) needs one too. Signing: with TW_SIGNING_IDENTITY set to a Developer ID Application
# identity the bundle is signed with hardened runtime and a timestamp (releases, see scripts/notarize.sh). Unset, the
# bundle is ad-hoc signed, which re-asks the keychain "Always Allow" grant after every rebuild (local builds, see the
# design doc, section 9). The app icon comes from assets/, regenerated with scripts/generate-icons.sh.
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <executable> <out-dir> <version> <bundle-id>" >&2
  exit 2
fi

EXECUTABLE="$1"
OUT_DIR="$2"
VERSION="$3"
BUNDLE_ID="$4"

APP_NAME="Token Watchroo"
EXEC_NAME="TokenWatchroo"
MIN_OS="14.0"
APP="$OUT_DIR/$APP_NAME.app"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ICON_ICNS="$ROOT/assets/AppIcon.icns"
ICON_CAR="$ROOT/assets/Assets.car"
for icon in "$ICON_ICNS" "$ICON_CAR"; do
  if [ ! -f "$icon" ]; then
    echo "error: app icon missing, run scripts/generate-icons.sh: $icon" >&2
    exit 1
  fi
done

# CFBundleShortVersionString must be dotted numbers: strip a leading v and any dynver suffix.
SHORT_VERSION="${VERSION#v}"
SHORT_VERSION="${SHORT_VERSION%%+*}"
SHORT_VERSION="${SHORT_VERSION%%-*}"
if ! [[ "$SHORT_VERSION" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]]; then
  SHORT_VERSION="0.0.0"
fi

if [ ! -x "$EXECUTABLE" ]; then
  echo "error: executable not found or not executable: $EXECUTABLE" >&2
  exit 1
fi

echo "==> assembling $APP ($SHORT_VERSION, $BUNDLE_ID)"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$EXECUTABLE" "$APP/Contents/MacOS/$EXEC_NAME"
chmod +x "$APP/Contents/MacOS/$EXEC_NAME"

echo "==> app icon"
cp "$ICON_ICNS" "$APP/Contents/Resources/AppIcon.icns"
cp "$ICON_CAR" "$APP/Contents/Resources/Assets.car"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>$EXEC_NAME</string>
    <key>CFBundleIdentifier</key>
    <string>$BUNDLE_ID</string>
    <key>CFBundleName</key>
    <string>$APP_NAME</string>
    <key>CFBundleDisplayName</key>
    <string>$APP_NAME</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleIconName</key>
    <string>AppIcon</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>$SHORT_VERSION</string>
    <key>CFBundleVersion</key>
    <string>$SHORT_VERSION</string>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>LSMinimumSystemVersion</key>
    <string>$MIN_OS</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHumanReadableCopyright</key>
    <string>MIT License</string>
</dict>
</plist>
PLIST

if [ -n "${TW_SIGNING_IDENTITY:-}" ]; then
  echo "==> codesign with hardened runtime: $TW_SIGNING_IDENTITY"
  codesign --force --options runtime --timestamp --sign "$TW_SIGNING_IDENTITY" --identifier "$BUNDLE_ID" "$APP"
  codesign --verify --deep --strict "$APP"
else
  echo "==> ad-hoc codesign"
  codesign --force --deep --sign - --identifier "$BUNDLE_ID" "$APP"
fi

echo "==> done: $APP"
