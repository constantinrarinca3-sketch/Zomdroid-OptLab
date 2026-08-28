#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

mapfile -d '' STUBS < <(find "$PROJECT_DIR/tools/ui-stubs" -type f -name '*.java' \
  -print0 | LC_ALL=C sort -z)
java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -d "$WORK_DIR/classes" \
  "${STUBS[@]}" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/fragments/NativeModulesDialog.java"

python3 - "$PROJECT_DIR" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root=Path(sys.argv[1])
general=(root/'app/src/main/java/com/zomdroid/fragments/OptLabDialog.java').read_text()
build42=(root/'app/src/main/java/com/zomdroid/fragments/Build42OptLabDialog.java').read_text()
native=(root/'app/src/main/java/com/zomdroid/fragments/NativeModulesDialog.java').read_text()
profiles=re.search(r'GENERAL_PROFILES\s*=\s*\{(.*?)\};', general, re.S)
assert profiles, 'GENERAL_PROFILES missing'
for name in ('STREAM_ALL','FBO_ALL','FULL_CANDIDATE'):
    assert name not in profiles.group(1), f'{name} leaked into general selector'
    assert f'Profile.{name}' in build42, f'{name} missing from Build42 dialog'
assert 'NativeModulesDialog.show' in general, 'Native Modules separate button missing'
assert 'Build42OptLabDialog.show' in general, 'Build42 separate button missing'
native_pos=general.index('R.string.native_modules_section')
build42_pos=general.index('R.string.opt_lab_build42_section')
runtime_pos=general.index('R.string.opt_lab_runtime_section')
assert native_pos < build42_pos < runtime_pos, \
    'Build 42 must be immediately after Native Modules and before Runtime/JVM'
assert 'popMan.setEnabled(false)' not in native, 'PopMan toggle must be usable after CP2'
for setter in ('setLighting64Enabled','setPzClipperEnabled','setPathfindingEnabled',
               'setPopManEnabled'):
    assert setter in native, f'{setter} not wired'
xml=root/'app/src/main/res/values/strings.xml'
tree=ET.parse(xml)
values={item.attrib.get('name'): ''.join(item.itertext()) for item in tree.getroot()}
assert 'Restart game required' in values['native_modules_restart']
assert '42.20' in values['native_modules_popman_unavailable']
print('NATIVE_MODULES_UI_SOURCE PASS separate_dialogs=2 real_toggles=4 build42_after_native=1')
PY
