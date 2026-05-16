#!/usr/bin/env bash
set -euo pipefail

# Push personal (and optional work) iCal URLs into app preferences via adb.
# Usage: ./scripts/set-calendar-urls.sh [DEVICE] "PERSONAL_ICS_URL" ["WORK_ICS_URL"]

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/dev-env.sh" 2>/dev/null || true

DEVICE=""
PERSONAL_URL=""
WORK_URL=""

if [[ "${1:-}" == http* ]]; then
  PERSONAL_URL="${1:-}"
  WORK_URL="${2:-}"
else
  DEVICE="${1:-192.168.1.4:5555}"
  PERSONAL_URL="${2:-}"
  WORK_URL="${3:-}"
fi

if [[ -z "$PERSONAL_URL" ]]; then
  echo "Usage: $0 [adb-device] PERSONAL_ICS_URL [WORK_ICS_URL]"
  echo "       $0 PERSONAL_ICS_URL   # uses default device 192.168.1.4:5555"
  exit 1
fi

ADB=(adb)
if [[ -n "$DEVICE" ]]; then
  ADB=(adb -s "$DEVICE")
fi

PKG="com.ambient.tvclock"
PREFS="${PKG}_preferences.xml"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

"${ADB[@]}" shell "run-as $PKG cat /data/data/$PKG/shared_prefs/$PREFS" > "$TMP" 2>/dev/null \
  || echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>' > "$TMP"

python3 - "$TMP" "$PERSONAL_URL" "$WORK_URL" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, personal, work = sys.argv[1], sys.argv[2], sys.argv[3]
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

set_string("personal_calendar_url", personal)
if work:
    set_string("work_calendar_url", work)
set_boolean("show_calendar", True)
tree.write(path, encoding="utf-8", xml_declaration=True)
PY

"${ADB[@]}" push "$TMP" "/data/local/tmp/$PREFS"
"${ADB[@]}" shell "run-as $PKG cp /data/local/tmp/$PREFS /data/data/$PKG/shared_prefs/$PREFS"
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell am start -n "$PKG/.MainActivity"
echo "Calendar URL written on $PKG. App restarted."
