#!/usr/bin/env bash
# Checks that an app bundle has the release Sparkle setup (#71): the feed of its architecture, the release public key
# and the security keys in the plist, the framework without its XPC services, and the rpath. It also refuses a bundle
# built with TW_DEBUG_FEED_URL or TW_DEBUG_ED_PUBLIC_KEY, because their values differ from the exact ones checked here.
#
# usage: scripts/check-sparkle-bundle.sh <app-bundle> <arm64|x64> [developer-id]
#
# With developer-id the nested code must also carry the Developer ID authority, the hardened runtime and a timestamp.
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ] || { [ "$#" -eq 3 ] && [ "$3" != "developer-id" ]; }; then
  echo "usage: $0 <app-bundle> <arm64|x64> [developer-id]" >&2
  exit 2
fi

APP="$1"
ARCH="$2"
SIGNATURE="${3:-}"

case "$ARCH" in
  arm64 | x64) ;;
  *)
    echo "error: unsupported architecture: $ARCH" >&2
    exit 2
    ;;
esac

PLIST="$APP/Contents/Info.plist"
FRAMEWORK="$APP/Contents/Frameworks/Sparkle.framework"
EXECUTABLE="$APP/Contents/MacOS/TokenWatchroo"

# expect_key <key> <expected, as plutil -extract raw prints it>
expect_key() {
  local actual
  actual="$(plutil -extract "$1" raw "$PLIST")" || {
    echo "error: $1 is missing from $PLIST" >&2
    exit 1
  }
  if [ "$actual" != "$2" ]; then
    echo "error: $1 is '$actual', expected '$2'" >&2
    exit 1
  fi
}

expect_key SUFeedURL "https://github.com/kevin-lee/token-watchroo/releases/latest/download/appcast-$ARCH.xml"
expect_key SUPublicEDKey "iv5/vKHASdo08/ztl7Jkz2UkODe+k1t1G4J2aQu0EX0="
expect_key SUEnableAutomaticChecks true
expect_key SUScheduledCheckInterval 86400
expect_key SUVerifyUpdateBeforeExtraction true
expect_key SURequireSignedFeed true

if [ ! -f "$FRAMEWORK/Versions/B/Sparkle" ] || [ "$(readlink "$FRAMEWORK/Versions/Current")" != "B" ]; then
  echo "error: Sparkle.framework is missing or lost its symbolic links: $FRAMEWORK" >&2
  exit 1
fi
if [ -e "$FRAMEWORK/XPCServices" ] || [ -e "$FRAMEWORK/Versions/B/XPCServices" ]; then
  echo "error: Sparkle.framework still has its XPC services: $FRAMEWORK" >&2
  exit 1
fi

if ! otool -l "$EXECUTABLE" | grep -q 'path @executable_path/../Frameworks '; then
  echo "error: the executable has no @executable_path/../Frameworks rpath" >&2
  exit 1
fi
if ! otool -L "$EXECUTABLE" | grep -q '@rpath/Sparkle.framework/'; then
  echo "error: the executable does not link @rpath/Sparkle.framework" >&2
  exit 1
fi

if [ "$SIGNATURE" = "developer-id" ]; then
  for code in "$FRAMEWORK/Versions/B/Autoupdate" "$FRAMEWORK/Versions/B/Updater.app" "$FRAMEWORK"; do
    INFO="$(codesign -dvvv "$code" 2>&1)"
    for pattern in "Authority=Developer ID Application" "flags=.*runtime" "^Timestamp="; do
      if ! grep -q "$pattern" <<<"$INFO"; then
        echo "error: $code does not match '$pattern'" >&2
        exit 1
      fi
    done
  done
fi

echo "Sparkle setup is correct for $ARCH: $APP"
