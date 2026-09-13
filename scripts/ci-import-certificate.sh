#!/usr/bin/env bash
# For GitHub Actions. Decodes the base64 Developer ID certificate into a temporary keychain, allows codesign to use it
# without a prompt, and exports TW_SIGNING_IDENTITY and TW_SIGNING_KEYCHAIN for the later steps of the job.
#
# usage: scripts/ci-import-certificate.sh
#
# Reads APPLE_CERTIFICATE_P12 (base64 of the .p12) and APPLE_CERTIFICATE_PASSWORD. The set-key-partition-list step
# takes the keychain password, not the .p12 password: on macOS 26 the wrong one fails with "The user name or passphrase
# you entered is not correct" (claude-proxymate v0.4.0). The workflow deletes the keychain in an always() step.
set -euo pipefail

for required in APPLE_CERTIFICATE_P12 APPLE_CERTIFICATE_PASSWORD; do
  if [ -z "${!required:-}" ]; then
    echo "error: $required is not set" >&2
    exit 1
  fi
done

RUNNER_TEMP="${RUNNER_TEMP:-$(mktemp -d)}"
KEYCHAIN="$RUNNER_TEMP/token-watchroo-signing.keychain-db"
KEYCHAIN_PASSWORD="$(uuidgen)"
P12="$RUNNER_TEMP/certificate.p12"

printf '%s' "$APPLE_CERTIFICATE_P12" | base64 --decode > "$P12"

security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security set-keychain-settings -lut 21600 "$KEYCHAIN"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"

security import "$P12" -k "$KEYCHAIN" -P "$APPLE_CERTIFICATE_PASSWORD" -T /usr/bin/codesign -T /usr/bin/security
rm -f "$P12"

security set-key-partition-list -S apple-tool:,apple: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN" > /dev/null
security list-keychains -d user -s "$KEYCHAIN" login.keychain-db

IDENTITY="$(security find-identity -v -p codesigning "$KEYCHAIN" \
  | sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p' | head -n 1)"
if [ -z "$IDENTITY" ]; then
  echo "error: no Developer ID Application identity in the certificate" >&2
  exit 1
fi

if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "TW_SIGNING_IDENTITY=$IDENTITY"
    echo "TW_SIGNING_KEYCHAIN=$KEYCHAIN"
  } >> "$GITHUB_ENV"
fi

echo "Imported: $IDENTITY"
echo "Keychain: $KEYCHAIN"
