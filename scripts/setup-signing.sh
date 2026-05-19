#!/usr/bin/env bash
# Generate an Android release keystore and register it with GitHub
# as repo secrets, so .github/workflows/package.yml can produce
# production-signed APKs.
#
#   scripts/setup-signing.sh [keystore-path] [alias]
#
# Defaults: ./release.keystore, alias "firetv"
#
# Requirements: keytool (bundled with the JDK) and gh (authed to the repo).

set -euo pipefail

KEYSTORE="${1:-release.keystore}"
ALIAS="${2:-firetv}"

if ! command -v keytool >/dev/null 2>&1; then
    echo "error: keytool not found. Install a JDK (e.g. brew install --cask temurin)." >&2
    exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
    echo "error: gh not found. Install GitHub CLI (brew install gh)." >&2
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "error: gh is not authenticated. Run 'gh auth login' first." >&2
    exit 1
fi

if [ -f "$KEYSTORE" ]; then
    echo "Keystore already exists at $KEYSTORE — re-using it."
    echo "Delete the file first if you want to regenerate."
else
    echo "Generating release keystore at $KEYSTORE (alias=$ALIAS, RSA 2048, 27-year validity)."
    echo "You will be prompted for a keystore password, a key password, and a Distinguished Name."
    echo "Pick strong passwords; they cannot be changed after publishing."
    echo
    keytool -genkey -v \
        -keystore "$KEYSTORE" \
        -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 \
        -validity 10000
fi

echo
echo "Saving keystore + credentials as repo secrets on $(gh repo view --json nameWithOwner -q .nameWithOwner)."
echo "You'll be re-prompted for the keystore + key passwords so they can be stored as secrets."
echo

read -r -s -p "Keystore password: " STORE_PASS
echo
read -r -s -p "Key password (often the same): " KEY_PASS
echo

base64 < "$KEYSTORE" | gh secret set ANDROID_KEYSTORE_BASE64
printf '%s' "$STORE_PASS" | gh secret set ANDROID_KEYSTORE_PASSWORD
printf '%s' "$ALIAS" | gh secret set ANDROID_KEY_ALIAS
printf '%s' "$KEY_PASS" | gh secret set ANDROID_KEY_PASSWORD

echo
echo "Secrets set:"
echo "  ANDROID_KEYSTORE_BASE64"
echo "  ANDROID_KEYSTORE_PASSWORD"
echo "  ANDROID_KEY_ALIAS"
echo "  ANDROID_KEY_PASSWORD"
echo
echo "Keep a secure backup of $KEYSTORE outside the repo — it is the only way"
echo "to publish updates for the same applicationId. Recommended: encrypted"
echo "password manager (1Password, Bitwarden) or hardware-encrypted USB drive."
echo
echo "You can now build signed APKs with:"
echo "  gh workflow run package.yml -f tag=vX.Y.Z"
