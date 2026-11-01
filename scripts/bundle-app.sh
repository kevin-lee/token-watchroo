#!/usr/bin/env bash
# Assembles dist/Token Watchroo.app from the SwiftPM executable and ad-hoc signs it.
#
# usage: scripts/bundle-app.sh <executable> <out-dir> <version> <bundle-id>
#
# A real .app bundle is mandatory: UNUserNotificationCenter aborts the process outside a bundle, and
# SMAppService (Launch at Login) needs one too. Signing: with TW_SIGNING_IDENTITY set to a Developer ID Application
# identity the bundle is signed with hardened runtime and a timestamp (releases, see scripts/notarize.sh). Unset, the
# bundle is ad-hoc signed, which re-asks the keychain "Always Allow" grant after every rebuild (local builds, see the
# design doc, section 9). The app icon and the About panel icons (#77) come from assets/, regenerated with
# scripts/generate-icons.sh.
#
# Sparkle (#71): Sparkle.framework comes from the folder of <executable>, where SwiftPM puts it, and goes to
# Contents/Frameworks without its XPC services, which a non-sandboxed app does not use. Its nested code is signed
# inside-out before the app: Autoupdate, Updater.app, the framework. The plist names the feed of the architecture of
# <executable>, because the arm64 and x64 disk images are separate builds. Copy with ditto, which keeps the framework's
# symbolic links.
#
# Debug only: TW_DEBUG_FEED_URL and TW_DEBUG_ED_PUBLIC_KEY replace SUFeedURL and SUPublicEDKey at bundle time, to test
# updates against a local feed with a throwaway key. The app has no override code. They are refused with
# TW_SIGNING_IDENTITY, and scripts/check-sparkle-bundle.sh refuses a bundle that carries them.
#
# Versions: <version> is the sbt-dynver version. CFBundleShortVersionString and CFBundleVersion get it stripped to
# integers, because Apple documents both keys as period-separated integers only and Sparkle (#71) compares
# CFBundleVersion. The full version goes to TokenWatchrooVersion, which the About panel shows (#77).
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
FEED_URL_BASE="https://github.com/kevin-lee/token-watchroo/releases/latest/download"
SPARKLE_PUBLIC_KEY="iv5/vKHASdo08/ztl7Jkz2UkODe+k1t1G4J2aQu0EX0="

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ICON_ICNS="$ROOT/assets/AppIcon.icns"
ICON_CAR="$ROOT/assets/Assets.car"
ABOUT_ICON_LIGHT="$ROOT/assets/AboutIcon-light.png"
ABOUT_ICON_DARK="$ROOT/assets/AboutIcon-dark.png"
for icon in "$ICON_ICNS" "$ICON_CAR" "$ABOUT_ICON_LIGHT" "$ABOUT_ICON_DARK"; do
  if [ ! -f "$icon" ]; then
    echo "error: app icon missing, run scripts/generate-icons.sh: $icon" >&2
    exit 1
  fi
done

# The full version for TokenWatchrooVersion (#77). It goes into the plist heredoc unescaped, so only the characters of
# an sbt-dynver version are accepted, for example 1.2.3-beta or 0.1.2+5-0ee2f7d5+20260929-1530.
FULL_VERSION="${VERSION#v}"
if ! [[ "$FULL_VERSION" =~ ^[0-9A-Za-z.+-]+$ ]]; then
  echo "error: version has characters a plist string cannot take as is: $FULL_VERSION" >&2
  exit 1
fi

# CFBundleShortVersionString and CFBundleVersion must be period-separated integers (Apple's format, and Sparkle (#71)
# compares CFBundleVersion): strip a leading v and any dynver suffix.
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

RAW_ARCH="$(lipo -archs "$EXECUTABLE")"
case "$RAW_ARCH" in
  arm64) ARCH="arm64" ;;
  x86_64) ARCH="x64" ;;
  *)
    echo "error: unsupported architecture: $RAW_ARCH" >&2
    exit 1
    ;;
esac

SPARKLE_FRAMEWORK="$(dirname "$EXECUTABLE")/Sparkle.framework"
if [ ! -d "$SPARKLE_FRAMEWORK" ]; then
  echo "error: Sparkle.framework not found next to the executable: $SPARKLE_FRAMEWORK" >&2
  exit 1
fi

if [ -n "${TW_SIGNING_IDENTITY:-}" ] && { [ -n "${TW_DEBUG_FEED_URL:-}" ] || [ -n "${TW_DEBUG_ED_PUBLIC_KEY:-}" ]; }; then
  echo "error: TW_DEBUG_FEED_URL and TW_DEBUG_ED_PUBLIC_KEY are refused for a Developer ID build" >&2
  exit 1
fi

FEED_URL="${TW_DEBUG_FEED_URL:-$FEED_URL_BASE/appcast-$ARCH.xml}"
PUBLIC_KEY="${TW_DEBUG_ED_PUBLIC_KEY:-$SPARKLE_PUBLIC_KEY}"
# Both go into the plist heredoc unescaped.
if ! [[ "$FEED_URL" =~ ^[0-9A-Za-z:/.%_~-]+$ ]]; then
  echo "error: feed URL has characters a plist string cannot take as is: $FEED_URL" >&2
  exit 1
fi
if ! [[ "$PUBLIC_KEY" =~ ^[0-9A-Za-z+/=]+$ ]]; then
  echo "error: public key is not base64: $PUBLIC_KEY" >&2
  exit 1
fi

echo "==> assembling $APP ($FULL_VERSION, bundle version $SHORT_VERSION, $BUNDLE_ID)"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/Frameworks"
cp "$EXECUTABLE" "$APP/Contents/MacOS/$EXEC_NAME"
chmod +x "$APP/Contents/MacOS/$EXEC_NAME"

echo "==> Sparkle.framework ($ARCH feed: $FEED_URL)"
FRAMEWORK="$APP/Contents/Frameworks/Sparkle.framework"
ditto "$SPARKLE_FRAMEWORK" "$FRAMEWORK"
rm -rf "$FRAMEWORK/Versions/B/XPCServices"
rm -f "$FRAMEWORK/XPCServices"

echo "==> app icon"
cp "$ICON_ICNS" "$APP/Contents/Resources/AppIcon.icns"
cp "$ICON_CAR" "$APP/Contents/Resources/Assets.car"
cp "$ABOUT_ICON_LIGHT" "$ABOUT_ICON_DARK" "$APP/Contents/Resources/"

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
    <key>TokenWatchrooVersion</key>
    <string>$FULL_VERSION</string>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>LSMinimumSystemVersion</key>
    <string>$MIN_OS</string>
    <key>LSUIElement</key>
    <true/>
    <key>SUFeedURL</key>
    <string>$FEED_URL</string>
    <key>SUPublicEDKey</key>
    <string>$PUBLIC_KEY</string>
    <key>SUEnableAutomaticChecks</key>
    <true/>
    <key>SUScheduledCheckInterval</key>
    <integer>86400</integer>
    <key>SUVerifyUpdateBeforeExtraction</key>
    <true/>
    <key>SURequireSignedFeed</key>
    <true/>
    <key>NSHumanReadableCopyright</key>
    <string>Copyright © 2026 Kevin Lee. MIT License.</string>
</dict>
</plist>
PLIST

NESTED_CODE=("$FRAMEWORK/Versions/B/Autoupdate" "$FRAMEWORK/Versions/B/Updater.app" "$FRAMEWORK")

if [ -n "${TW_SIGNING_IDENTITY:-}" ]; then
  echo "==> codesign with hardened runtime: $TW_SIGNING_IDENTITY"
  for code in "${NESTED_CODE[@]}"; do
    codesign --force --options runtime --timestamp --sign "$TW_SIGNING_IDENTITY" "$code"
  done
  codesign --force --options runtime --timestamp --sign "$TW_SIGNING_IDENTITY" --identifier "$BUNDLE_ID" "$APP"
  codesign --verify --deep --strict "$APP"
else
  echo "==> ad-hoc codesign"
  for code in "${NESTED_CODE[@]}"; do
    codesign --force --options runtime --sign - "$code"
  done
  codesign --force --sign - --identifier "$BUNDLE_ID" "$APP"
fi

echo "==> done: $APP"
