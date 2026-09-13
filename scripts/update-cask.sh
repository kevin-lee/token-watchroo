#!/usr/bin/env bash
# Writes Casks/token-watchroo.rb in a checkout of kevin-lee/homebrew-tap and, when a cask already exists, keeps the
# previous release as Casks/token-watchroo@<previous>.rb with livecheck skipped, the convention of the existing casks
# in that tap. Used by .github/workflows/release.yml and by hand. Does not commit.
#
# usage: scripts/update-cask.sh <version> <arm64-sha256> <x64-sha256> <tap-checkout>
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <version> <arm64-sha256> <x64-sha256> <tap-checkout>" >&2
  exit 2
fi

VERSION="$1"
ARM_SHA="$2"
INTEL_SHA="$3"
TAP="$4"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: version must be X.Y.Z: $VERSION" >&2
  exit 1
fi
for sha in "$ARM_SHA" "$INTEL_SHA"; do
  if ! [[ "$sha" =~ ^[0-9a-f]{64}$ ]]; then
    echo "error: not a SHA-256 hex digest: $sha" >&2
    exit 1
  fi
done

CASKS="$TAP/Casks"
if [ ! -d "$CASKS" ]; then
  echo "error: Casks directory not found: $CASKS" >&2
  exit 1
fi

# write_cask <file> <cask-token> <version> <arm-sha> <intel-sha> <latest|pinned>
write_cask() {
  local file="$1" token="$2" version="$3" arm_sha="$4" intel_sha="$5" livecheck="$6"
  local livecheck_body
  if [ "$livecheck" = "pinned" ]; then
    livecheck_body="    skip \"Versioned cask; pinned to $version\""
  else
    livecheck_body="    url :url
    strategy :github_latest"
  fi
  cat > "$file" <<CASK
cask "$token" do
  arch arm: "arm64", intel: "x64"

  version "$version"
  sha256 arm:   "$arm_sha",
         intel: "$intel_sha"

  url "https://github.com/kevin-lee/token-watchroo/releases/download/v#{version}/Token-Watchroo-#{version}-#{arch}.dmg"
  name "Token Watchroo"
  desc "Menubar app that watches Claude Code and Codex usage windows"
  homepage "https://github.com/kevin-lee/token-watchroo"

  livecheck do
$livecheck_body
  end

  depends_on macos: :sonoma

  app "Token Watchroo.app"

  uninstall quit:       "io.kevinlee.tokenwatchroo",
            login_item: "Token Watchroo"

  zap trash: [
    "~/Library/Application Support/Token Watchroo",
    "~/Library/Caches/io.kevinlee.tokenwatchroo",
    "~/Library/HTTPStorages/io.kevinlee.tokenwatchroo",
    "~/Library/Preferences/io.kevinlee.tokenwatchroo.plist",
  ]
end
CASK
}

CURRENT="$CASKS/token-watchroo.rb"
PREV_VERSION=""
if [ -f "$CURRENT" ]; then
  PREV_VERSION="$(sed -n 's/^  version "\(.*\)"$/\1/p' "$CURRENT")"
  PREV_ARM="$(sed -n 's/^  sha256 arm:   "\(.*\)",$/\1/p' "$CURRENT")"
  PREV_INTEL="$(sed -n 's/^         intel: "\(.*\)"$/\1/p' "$CURRENT")"
  if [ -z "$PREV_VERSION" ] || [ -z "$PREV_ARM" ] || [ -z "$PREV_INTEL" ]; then
    echo "error: could not parse the current cask: $CURRENT" >&2
    exit 1
  fi
  if [ "$PREV_VERSION" = "$VERSION" ]; then
    echo "error: the cask is already at $VERSION" >&2
    exit 1
  fi
  write_cask "$CASKS/token-watchroo@$PREV_VERSION.rb" "token-watchroo@$PREV_VERSION" \
    "$PREV_VERSION" "$PREV_ARM" "$PREV_INTEL" pinned
  rm -f "$CURRENT"
fi

write_cask "$CURRENT" token-watchroo "$VERSION" "$ARM_SHA" "$INTEL_SHA" latest

echo "Done. Generated:"
echo "  - $CURRENT"
if [ -n "$PREV_VERSION" ]; then
  echo "  - $CASKS/token-watchroo@$PREV_VERSION.rb"
  echo "Previous: $PREV_VERSION"
fi
