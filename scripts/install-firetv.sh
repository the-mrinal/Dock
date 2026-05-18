#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/dev-env.sh"

DEVICE="${1:-192.168.1.4:5555}"
PKG="com.ambient.tvclock.firetv"
COMPONENT="${PKG}/com.ambient.tvclock.MediaNotificationListener"

cd "$ROOT"
./gradlew assembleFiretvDebug
adb -s "$DEVICE" install -r app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
adb -s "$DEVICE" shell settings put secure enabled_notification_listeners "$COMPONENT"
adb -s "$DEVICE" shell cmd notification allow_listener "$COMPONENT"
adb -s "$DEVICE" shell am start -n "${PKG}/com.ambient.tvclock.MainActivity"

echo "Installed. Play Spotify, then open Dock."
