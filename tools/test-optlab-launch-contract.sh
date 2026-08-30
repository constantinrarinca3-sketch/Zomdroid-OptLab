#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

if java -m jdk.compiler/com.sun.tools.javac.Main -version >/dev/null 2>&1; then
  java -m jdk.compiler/com.sun.tools.javac.Main --release 11 -d "$WORK_DIR" \
    "$PROJECT_DIR/tools/ui-stubs/androidx/annotation/NonNull.java" \
    "$PROJECT_DIR/app/src/main/java/com/zomdroid/OptLabLaunchContract.java" \
    "$PROJECT_DIR/tools/OptLabLaunchContractUnit.java"
  java -cp "$WORK_DIR" com.zomdroid.OptLabLaunchContractUnit
else
  python3 - "$PROJECT_DIR" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
contract = (root/'app/src/main/java/com/zomdroid/OptLabLaunchContract.java').read_text()
launcher = (root/'app/src/main/java/com/zomdroid/GameLauncher.java').read_text()
registry = (root/'app/src/main/java/com/zomdroid/OptLabFeatureRegistry.java').read_text()
for prefix in ('zomdroid.optlab.', 'zomdroid.native.pathfinding.',
               'zomdroid.native.popman.'):
    assert f'"{prefix}"' in contract
assert 'OptLabLaunchContract.clearManagedProperties(jvmArgs);' in launcher
assert 'OptLabLaunchContract.requireUniqueManagedProperties(jvmArgs);' in launcher
assert 'compatibility.requireAgentFeatureProperties(jvmArgs);' in launcher
assert 'OptLabLaunchContract.putProperty(jvmArgs, feature.agentProperty' in registry

# Runtime-equivalent transition: every managed stale ON is removed, unrelated args survive,
# and putting an OFF twice still leaves one authoritative value.
managed = ('zomdroid.optlab.', 'zomdroid.native.pathfinding.',
           'zomdroid.native.popman.')
args = ['-Xmx2G', '-Dzomdroid.optlab.stream.wake=1',
        '-Dzomdroid.optlab.chunk.grid.load=1',
        '-Dzomdroid.optlab.fbo.frame.budget=1', '-Dunrelated.keep=1']
def key(arg):
    return arg[2:].split('=', 1)[0] if arg.startswith('-D') and '=' in arg else None
args = [a for a in args if not (key(a) and key(a).startswith(managed))]
def put(k, v):
    global args
    args = [a for a in args if key(a) != k]
    args.append(f'-D{k}={v}')
for k in ('zomdroid.optlab.stream.wake', 'zomdroid.optlab.chunk.grid.load',
          'zomdroid.optlab.fbo.frame.budget'):
    put(k, '0')
put('zomdroid.optlab.stream.wake', '0')
keys = [key(a) for a in args if key(a) and key(a).startswith(managed)]
assert len(keys) == len(set(keys))
assert all(f'-D{k}=0' in args for k in (
    'zomdroid.optlab.stream.wake', 'zomdroid.optlab.chunk.grid.load',
    'zomdroid.optlab.fbo.frame.budget'))
print('OPTLAB_LAUNCH_CONTRACT_SOURCE_FALLBACK PASS stale_on=removed off=authoritative')
PY
fi
