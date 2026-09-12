#!/usr/bin/env bash
# Regenerates the app icon from the kangaroo sources under design/logo.
#
# usage: scripts/generate-icons.sh
#
# The sources are resized to 1024 px into the Icon Composer package design/AppIcon.icon with sips, and the package is
# compiled with actool (full Xcode 26) into assets/Assets.car (light, dark, and tintable icon stacks for macOS 26) and
# assets/AppIcon.icns (the light appearance, used by macOS 14 and 15). Both outputs are committed, so the build never
# runs this script and needs no Xcode.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGO_DIR="$ROOT/design/logo"
ICON="$ROOT/design/AppIcon.icon"
ASSETS="$ROOT/assets"

if ! xcrun --find actool >/dev/null 2>&1; then
  echo "actool not found: install Xcode 26 and select it with xcode-select" >&2
  exit 1
fi

for required in "$LOGO_DIR/token-watchroo-logo.png" "$LOGO_DIR/token-watchroo-logo-dark.png" "$ICON/icon.json"; do
  if [ ! -f "$required" ]; then
    echo "error: missing $required" >&2
    exit 1
  fi
done

mkdir -p "$ICON/Assets" "$ASSETS"

echo "==> resizing sources to 1024 px"
sips -z 1024 1024 "$LOGO_DIR/token-watchroo-logo.png" --out "$ICON/Assets/light.png" >/dev/null
sips -z 1024 1024 "$LOGO_DIR/token-watchroo-logo-dark.png" --out "$ICON/Assets/dark.png" >/dev/null

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> compiling $ICON with actool"
xcrun actool "$ICON" --compile "$TMP" --platform macosx --minimum-deployment-target 14.0 --app-icon AppIcon \
  --include-all-app-icons --enable-on-demand-resources NO --development-region en --target-device mac \
  --output-partial-info-plist "$TMP/partial.plist" --output-format human-readable-text

if [ ! -f "$TMP/Assets.car" ] || [ ! -f "$TMP/AppIcon.icns" ]; then
  echo "error: actool produced no icon" >&2
  exit 1
fi

if ! xcrun --sdk macosx assetutil --info "$TMP/Assets.car" | grep -q NSAppearanceNameDarkAqua; then
  echo "error: Assets.car has no dark appearance" >&2
  exit 1
fi

cp "$TMP/Assets.car" "$ASSETS/Assets.car"
cp "$TMP/AppIcon.icns" "$ASSETS/AppIcon.icns"

echo "Done. Generated:"
echo "  - $ICON/Assets/light.png"
echo "  - $ICON/Assets/dark.png"
echo "  - $ASSETS/Assets.car"
echo "  - $ASSETS/AppIcon.icns"
