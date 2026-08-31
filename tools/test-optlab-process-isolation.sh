#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$PROJECT_DIR" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
activities = {
    node.attrib.get(android+'name'): node
    for node in manifest.findall('./application/activity')
}
game = activities['.GameActivity']
restart = activities['.ProcessRestartActivity']
assert game.attrib.get(android+'process') == ':game'
assert restart.attrib.get(android+'process') == ':restart'
assert restart.attrib.get(android+'exported') == 'false'
assert restart.attrib.get(android+'noHistory') == 'true'
assert restart.attrib.get(android+'taskAffinity') == ''

application = (root/'app/src/main/java/com/zomdroid/ZomdroidApplication.java').read_text()
activity = (root/'app/src/main/java/com/zomdroid/GameActivity.java').read_text()
restarter = (root/'app/src/main/java/com/zomdroid/AppProcessRestarter.java').read_text()
helper = (root/'app/src/main/java/com/zomdroid/ProcessRestartActivity.java').read_text()
launcher = (root/'app/src/main/java/com/zomdroid/GameLauncher.java').read_text()
fragment = (root/'app/src/main/java/com/zomdroid/fragments/OptLabFragment.java').read_text()

assert 'Application.getProcessName()' in application
assert 'processName.endsWith(":game")' in application
assert 'processName.endsWith(":restart")' in application
assert 'AppStorage.init(this);' in application
assert 'init(gameProcess);' in application
assert 'if (!gameProcess)' in application

assert 'GameLauncher.launch(gameInstance, GameActivity.this);' in activity
assert 'finishGameProcess();' in activity
assert 'Process.killProcess(Process.myPid())' in activity
assert 'finish();' in activity

assert 'EXTRA_OLD_PID' in restarter
assert 'FLAG_ACTIVITY_MULTIPLE_TASK' in restarter
assert 'Process.killProcess(oldPid)' in helper
assert 'getLaunchIntentForPackage' in helper
assert 'FLAG_ACTIVITY_CLEAR_TASK' in helper
assert 'AppProcessRestarter.restart(requireContext())' in fragment
assert 'opt_lab_apply_restart' in fragment

for removed in ('zomdroid.optlab.fbo.budget',
                'zomdroid.optlab.fbo.urgent.budget',
                'zomdroid.optlab.fbo.max.defer.frames'):
    assert removed not in launcher, f'removed FBO governor tuning still emitted: {removed}'

print('OPTLAB_PROCESS_ISOLATION PASS game_process=fresh restart_helper=separate '
      'game_exit=finish_then_kill fbo_governor_tuning=removed')
PY
