#!/usr/bin/env bash
set -euo pipefail

# Point the Dock at my-life: write the API URL and DOCK_KEY into app preferences via adb.
# Usage: ./scripts/set-mylife.sh [DEVICE] MYLIFE_URL DOCK_KEY
#        ./scripts/set-mylife.sh https://fix.mrinal.dev/api "$DOCK_KEY"   # default device 192.168.1.4:5555

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/dev-env.sh" 2>/dev/null || true

DEVICE=""
if [[ "${1:-}" == http* ]]; then
  URL="${1:-}"; KEY="${2:-}"
else
  DEVICE="${1:-192.168.1.4:5555}"; URL="${2:-}"; KEY="${3:-}"
fi

if [[ -z "$URL" || -z "$KEY" ]]; then
  echo "Usage: $0 [adb-device] MYLIFE_URL DOCK_KEY"
  exit 1
fi

ADB=(adb)
if [[ -n "$DEVICE" ]]; then
  ADB=(adb -s "$DEVICE")
fi

PKG="com.ambient.tvclock.firetv"
ACTIVITY="com.ambient.tvclock.MainActivity"
PREFS="${PKG}_preferences.xml"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

"${ADB[@]}" shell "run-as $PKG cat /data/data/$PKG/shared_prefs/$PREFS" > "$TMP" 2>/dev/null \
  || echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>' > "$TMP"

python3 - "$TMP" "$URL" "$KEY" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, url, key = sys.argv[1], sys.argv[2].rstrip("/"), sys.argv[3]
tree = ET.parse(path)
root = tree.getroot()

def set_string(name, value):
    for child in root.findall("string"):
        if child.get("name") == name:
            child.text = value
            return
    el = ET.SubElement(root, "string", name=name)
    el.text = value

def set_boolean(name, value: bool):
    val = "true" if value else "false"
    for child in root.findall("boolean"):
        if child.get("name") == name:
            child.set("value", val)
            return
    ET.SubElement(root, "boolean", name=name, value=val)

set_string("mylife_url", url)
set_string("mylife_key", key)
set_boolean("show_calendar", True)
# The old on-device feed URLs are gone; clear them if an older build left them behind.
for child in list(root.findall("string")):
    if child.get("name") in ("personal_calendar_url", "work_calendar_url"):
        root.remove(child)
tree.write(path, encoding="utf-8", xml_declaration=True)
PY

"${ADB[@]}" push "$TMP" "/data/local/tmp/$PREFS"
"${ADB[@]}" shell "run-as $PKG cp /data/local/tmp/$PREFS /data/data/$PKG/shared_prefs/$PREFS"
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell am start -n "$PKG/$ACTIVITY"
echo "my-life URL and key written on $PKG. App restarted."
