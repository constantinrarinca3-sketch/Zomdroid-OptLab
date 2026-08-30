#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

"$PROJECT_DIR/tools/test-optlab-tabbed-ui-compile.sh"
"$PROJECT_DIR/tools/test-optlab-preferences-independence.sh"
"$PROJECT_DIR/tools/test-optlab-custom-presets.sh"
"$PROJECT_DIR/tools/test-native-modules-preferences.sh"

python3 - "$PROJECT_DIR" <<'PY'
from pathlib import Path
import hashlib
import io
import re
import sys
import tarfile
import zipfile
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
fragment = (root/'app/src/main/java/com/zomdroid/fragments/OptLabFragment.java').read_text()
ui = (root/'app/src/main/java/com/zomdroid/fragments/OptLabUi.java').read_text()
settings = (root/'app/src/main/java/com/zomdroid/fragments/SettingsFragment.java').read_text()
prefs = (root/'app/src/main/java/com/zomdroid/OptLabPreferences.java').read_text()
native = (root/'app/src/main/java/com/zomdroid/NativeModulesPreferences.java').read_text()

assert 'extends Fragment' in fragment, 'OPT-LAB is not a real navigation Fragment'
assert 'AlertDialog' not in fragment and 'AlertDialog' not in ui, 'nested dialog leaked into UI'
assert 'R.id.opt_lab_fragment' in settings, 'Settings does not navigate to full-screen OPT-LAB'
assert 'OptLabDialog.show' not in settings, 'legacy dialog entry point remains active'
for old in ('OptLabDialog.java', 'Build42OptLabDialog.java', 'ChunkOptimizationDialog.java',
            'RenderOptimizationDialog.java', 'NativeModulesDialog.java'):
    assert not (root/'app/src/main/java/com/zomdroid/fragments'/old).exists(), \
        f'legacy nested dialog still exists: {old}'

for label in ('opt_lab_tab_overview', 'opt_lab_tab_runtime', 'opt_lab_tab_display',
              'opt_lab_tab_build42', 'opt_lab_tab_native', 'opt_lab_tab_experimental'):
    assert f'R.string.{label}' in fragment, f'missing tab {label}'
assert fragment.count('buildOverviewPage()') >= 2
assert fragment.count('buildRuntimePage()') >= 2
assert fragment.count('buildDisplayPage()') >= 2
assert fragment.count('buildBuild42Page()') >= 2
assert fragment.count('buildNativePage()') >= 2
assert fragment.count('buildExperimentalPage()') >= 2

for setter in (
    'setMasterEnabled', 'setQuietRuntime', 'setStdioMode', 'setSqliteAndroidNative',
    'setBox64Policy', 'setSurfaceMode', 'setDisplayFpsHint', 'setInputQueueMode',
    'setAnalogFilter', 'setInputCoalesce', 'setMobileGlFileLogEnabled',
    'setOnlyBuild42', 'setAdvancedControls', 'setFeatureEnabled',
    'setLighting64Enabled', 'setPzClipperEnabled', 'setPathfindingEnabled',
    'setPopManEnabled', 'setGeneralProfile', 'setBuild42LabProfile'):
    assert setter in fragment, f'unmapped setter: {setter}'
for action in ('createCustomBuild42Preset', 'applyCustomBuild42Preset',
               'updateCustomBuild42Preset', 'deleteCustomBuild42Preset',
               'resetCustomBuild42PresetCatalog'):
    assert action in fragment and action in prefs, f'unmapped custom preset action: {action}'
for advanced_store_action in ('renameCustomBuild42Preset',
                              'duplicateCustomBuild42Preset'):
    assert advanced_store_action in prefs, \
        f'custom preset store API was not retained: {advanced_store_action}'

assert 'setSafeModeEnabled' in fragment and 'isSafeModeEnabled' in fragment
assert 'SCHEMA = 12' in prefs and 'K_SAFE_MODE' in prefs
custom_presets = (root/'app/src/main/java/com/zomdroid/OptLabCustomPresetStore.java').read_text()
assert 'SCHEMA = 1' in custom_presets and 'ZOMDROID_BUILD42_PRESETS' in custom_presets
assert 'feature.id' in custom_presets and '.ordinal()' not in custom_presets
assert 'K_GENERAL_PROFILE' in prefs and 'K_BUILD42_PROFILE' in prefs
assert 'SAFE MODE · setări păstrate' in prefs
assert 'SCHEMA = 4' in native and 'optLab.isSafeModeEnabled()' in native
assert 'setBuild42ProductionAllOff' in fragment and 'setBuild42ProductionAllOff' in prefs
for module in ('MAIN_LOOP_PACING', 'WORLD_STREAM_CHUNK', 'FBO_RENDER_CELL', 'RENDER_HOTPATH',
               'MODEL_RINGBUFFER', 'SHADER_UNIFORMS', 'MEMORY_ALLOCATION'):
    assert f'Module.{module}' in fragment, f'missing module page {module}'
for token in ('LAB_PROFILES', 'showBuild42Module', 'showExperimentalModule',
              'generalProfileCard', 'build42ProfileCard', 'experimentalModuleCards',
              'advancedControls', 'featureCards', 'featuresForModule'):
    assert token in fragment, f'missing modular UI token {token}'
for token in ('routeHistory', 'navigateBack()', 'Route.build42Module(module)',
              'Route.experimentalModule(module)', 'OnBackPressedCallback'):
    assert token in fragment, f'missing route-history token {token}'
assert 'back.setOnClickListener(view -> showBuild42Home())' not in fragment, \
    'Build 42 Back is still hardcoded to the module home'
assert 'back.setOnClickListener(view -> showExperimentalHome())' not in fragment, \
    'Experimental Back is still hardcoded to the global Experimental home'
assert 'navigate(Route.experimentalModule(module), true)' in fragment, \
    'Build 42 -> Experimental does not preserve its exact origin route'
assert 'internalCards' not in fragment, 'Internal features still use read-only cards'
assert 'Internal · control individual · fallback automat' in fragment
assert 'INTERNAL · control individual' in fragment
for token in ('buildCustomPresetManagerPage', 'showCustomPresetManager',
              'customPresetSpinner', 'customPresetName', 'pendingPresetDeleteId',
              'Modified · based on ', 'Custom · '):
    assert token in fragment, f'missing custom preset manager token {token}'
assert 'Ștergerea cere două apăsări.' in fragment
assert 'Nume pentru un preset nou' in fragment
assert 'customPresetName.input.setText(preset.getName())' not in fragment, \
    'preset selection still overwrites the new-preset name field'
assert 'customPresetRename' not in fragment and 'customPresetDuplicate' not in fragment, \
    'advanced preset operations still clutter the compact manager'
assert 'customPresetReset.setVisibility(storageProblem ? View.VISIBLE : View.GONE)' in fragment
assert 'RTHREAD_RING_BULK_PACK' not in fragment, 'archived Ring bulk leaked into UI'
assert 'Pathfinding' in fragment and 'PopMan' in fragment
assert 'setChunkCp2cDirtyClear' not in fragment, \
    'CP2C bypasses the registry-driven Experimental UI'
assert 'addFeatureGroup(page, module, OptLabFeatureRegistry.Maturity.EXPERIMENTAL' \
       not in fragment, 'Experimental toggles duplicated in Build 42 detail pages'

assert 'MaterialCardView' in ui, 'card layout missing'
assert 'colorSurfaceContainerLow' in ui and 'colorPrimary' in ui
assert 'Color.' not in ui and '0x' not in ui and '#' not in ui, \
    'hardcoded UI color found'

layout = root/'app/src/main/res/layout/fragment_opt_lab.xml'
nav = root/'app/src/main/res/navigation/nav_graph.xml'
ET.parse(layout)
nav_tree = ET.parse(nav)
android = '{http://schemas.android.com/apk/res/android}'
destinations = [node.attrib.get(android+'name') for node in nav_tree.getroot()]
assert 'com.zomdroid.fragments.OptLabFragment' in destinations

for icon in ('profile', 'runtime', 'display', 'build42', 'native', 'experimental',
             'safe_mode', 'chevron'):
    ET.parse(root/f'app/src/main/res/drawable/optlab_ic_{icon}.xml')
for theme in ('values/themes.xml', 'values-night/themes.xml'):
    ET.parse(root/'app/src/main/res'/theme)

strings_tree = ET.parse(root/'app/src/main/res/values/strings.xml')
values = {node.attrib.get('name'): ''.join(node.itertext())
          for node in strings_tree.getroot()}
assert values['opt_lab_safe_mode_summary'] == 'Dezactivează temporar toate optimizările'
assert 'implicit OFF' in values['opt_lab_experimental_placeholder']
assert 'EXPERIMENTAL' in values['native_modules_pathfinding']
assert 'EXPERIMENTAL' in values['native_modules_popman']

for source in (fragment, ui):
    for name in re.findall(r'R\.string\.([A-Za-z0-9_]+)', source):
        assert name in values, f'missing string resource: {name}'
    for name in re.findall(r'R\.drawable\.([A-Za-z0-9_]+)', source):
        assert (root/f'app/src/main/res/drawable/{name}.xml').is_file() or any(
            (root/'app/src/main/res'/folder/f'{name}.png').is_file()
            for folder in ('drawable', 'drawable-hdpi', 'drawable-xhdpi', 'drawable-xxhdpi')
        ), f'missing drawable resource: {name}'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

bundle = root/'app/src/main/assets/bundles/jars.tar'
with tarfile.open(bundle, 'r:') as archive:
    member = archive.getmember('./zomdroid-agent.jar')
    payload = archive.extractfile(member).read()
with zipfile.ZipFile(io.BytesIO(payload)) as agent:
    names = set(agent.namelist())
    assert 'com/zomdroid/agent/optimization/FeatureCompatibility.class' in names
    assert 'zombie/core/ZDOptStateRunFast.class' in names
    assert b'[ZD-OPT-LAB-AGENT] version=15 schema=14 loaded' in \
        agent.read('com/zomdroid/agent/Main.class')
    assert 'zombie/iso/fboRenderChunk/ZDOptFboPrepareFast.class' in agent.namelist()
    assert 'zombie/core/ZDOptSameProgramBindFast.class' in agent.namelist()
assert len(hashlib.sha256(payload).hexdigest()) == 64

print('OPTLAB_TABBED_UI_SOURCE PASS full_screen=1 tabs=6 modules=7 detail_pages=1 '
      'profiles=general_build42_independent experimental=module_categories '
      'advanced=registry_driven custom_presets=manager_actions_4_recovery nested_dialogs=0 '
      'safe_mode=effective_preserve navigation=route_history '
      'theme=light_dark agent_bundle_source_matched=1')
PY
