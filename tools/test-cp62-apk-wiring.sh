#!/usr/bin/env bash
set -euo pipefail
export TZ=UTC

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$PROJECT_DIR" <<'PY'
from pathlib import Path
import sys

root=Path(sys.argv[1])
launcher=(root/'app/src/main/java/com/zomdroid/GameLauncher.java').read_text()
prefs=(root/'app/src/main/java/com/zomdroid/OptLabPreferences.java').read_text()
registry=(root/'app/src/main/java/com/zomdroid/OptLabFeatureRegistry.java').read_text()
agent=(root/'optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5/zomdroid-agent/src/main/java/com/zomdroid/agent/Main.java').read_text()
runtime=(root/'optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5/zomdroid-agent/src/main/java/com/zomdroid/agent/optimization/RenderOptimizationRuntime.java').read_text()

props={
    'zomdroid.optlab.render.chunk.depth.upload':'isRenderChunkDepthUpload',
    'zomdroid.optlab.render.chunk.depth.lookup':'isRenderChunkDepthLookup',
    'zomdroid.optlab.render.ring.empty.clear':'isRenderRingEmptyClear',
}
for prop,getter in props.items():
    assert prop in registry and getter in prefs, f'registry/preferences missing {prop}'
    assert prop in agent, f'agent missing {prop}'
assert 'addAgentFeatureProperties(jvmArgs)' in launcher, \
    'launcher does not delegate feature properties to registry'
assert 'OptLabLaunchContract.clearManagedProperties(jvmArgs)' in launcher, \
    'launcher does not remove stale OPT-LAB JVM properties'
assert 'OptLabLaunchContract.requireUniqueManagedProperties(jvmArgs)' in launcher, \
    'launcher does not reject duplicate OPT-LAB JVM properties'
assert 'compatibility.requireAgentFeatureProperties(jvmArgs)' in launcher, \
    'launcher does not verify the complete feature snapshot before JNI'
for feature in ('RTHREAD_CHUNK_DEPTH_UPLOAD','RTHREAD_CHUNK_DEPTH_LOOKUP',
                'RTHREAD_RING_RENDER_CLEAR'):
    assert feature in registry and feature in agent, f'registry/agent missing {feature}'
assert 'public static final int SCHEMA = 13' in prefs, 'preference schema mismatch'
assert 'isSafeModeEnabled()' in prefs and 'K_SAFE_MODE' in prefs, \
    'Safe Mode effective gate missing'
assert 'isAnyRenderOptimizationEnabled()' in prefs, 'agent launch gate ignores Render'
assert 'if (!value) editor.putBoolean(K_RENDER_CHUNK_DEPTH_LOOKUP, false)' in prefs, \
    'upload/lookup dependency not atomic'
assert 'ring_bulk_pack=0_experimental_not_integrated' in runtime, \
    'bulk-pack diagnostic status missing'
assert 'rthreadProfileFast' not in agent and 'rthreadProfileFast' not in runtime, \
    'rejected CP6.0 profiler fastpath reintroduced'
assert 'rthreadRingPackFast' not in agent and 'MGLPZRingFast' not in agent, \
    'unproven Ring bulk pack accidentally integrated'
assert 'version=16 schema=15 loaded' in agent, 'agent marker mismatch'

for workflow in (root/'.github/workflows').glob('*.yml'):
    if workflow.name == 'jassimp-direct.yml':
        continue
    text=workflow.read_text()
    assert 'build-optlab-agent.sh' in text, f'{workflow.name} skips agent rebuild'
    assert 'test-optlab-agent-source.sh' in text, f'{workflow.name} skips agent test'
    assert 'app/build/outputs/apk/**/' in text, \
        f'{workflow.name} uploads from the wrong flavor output directory'
    assert 'if-no-files-found: error' in text, \
        f'{workflow.name} can succeed without delivering an APK'
print('CP6_2_APK_WIRING_SOURCE PASS properties=3 features=3 workflows=3')
PY

WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT
tar --no-same-owner -xf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar" \
  -C "$WORK_DIR"
unzip -tq "$WORK_DIR/zomdroid-agent.jar"
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/RenderOptimizationRuntime.class'
unzip -p "$WORK_DIR/zomdroid-agent.jar" com/zomdroid/agent/Main.class \
  | grep -aFq '[ZD-OPT-LAB-AGENT] version=16 schema=15 loaded'
if tar -tf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar" | grep -q 'MGLPZ-Performance'; then
  echo 'external CP6.2 proof agent leaked into APK bundle' >&2
  exit 1
fi
echo 'CP6_2_APK_BUNDLE PASS unified_agent=1 external_agent=0 marker=16/15 registry_owned=1'
