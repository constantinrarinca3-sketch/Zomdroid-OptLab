#!/usr/bin/env bash
set -euo pipefail
export TZ=UTC

if [[ "$#" -ne 2 ]]; then
  echo "usage: $0 /path/to/projectzomboid-42.20.jar /path/to/projectzomboid-42.20.3.jar" >&2
  exit 2
fi

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT
mkdir -p "$WORK_DIR/bundle" "$WORK_DIR/tests"
tar --no-same-owner -xf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar" \
  -C "$WORK_DIR/bundle"

java -m jdk.compiler/com.sun.tools.javac.Main --release 17 \
  -cp "$WORK_DIR/bundle/zomdroid-agent.jar" \
  -d "$WORK_DIR/tests" "$PROJECT_DIR/tools/HotPathOptimizationRealJarSmoke.java"

for game_jar in "$@"; do
  proof="$WORK_DIR/$(basename "$game_jar").proof.log"
  game_sha="$(sha256sum "$game_jar" | cut -d' ' -f1)"
  java -Dnet.bytebuddy.experimental=true \
    -cp "$WORK_DIR/tests:$WORK_DIR/bundle/zomdroid-agent.jar:$game_jar" \
    HotPathOptimizationRealJarSmoke "$game_jar" "$proof" "$game_sha"

  for mechanism in \
    rthread_shader_lookup rthread_mvp rthread_staterun_texture \
    rthread_texture_bind rthread_game_profiler_idle \
    rthread_render_style_probe rthread_build_loop rthread_extended_probes; do
    grep -q "mechanism=$mechanism state=active" "$proof"
  done
  if grep -Eq 'state=(unsupported|blocked_install)' "$proof"; then
    echo "unexpected fallback in $game_jar" >&2
    grep -E 'state=(unsupported|blocked_install)' "$proof" >&2
    exit 1
  fi
done

echo "CP6_6_HOTPATH_BOTH_REAL_JARS PASS"
