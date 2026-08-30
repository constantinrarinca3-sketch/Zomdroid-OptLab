#!/usr/bin/env bash
set -euo pipefail

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
  -d "$WORK_DIR/tests" "$PROJECT_DIR/tools/ChunkOptimizationRealJarSmoke.java"

for game_jar in "$@"; do
  proof="$WORK_DIR/$(basename "$game_jar").proof.log"
  game_sha="$(sha256sum "$game_jar" | cut -d' ' -f1)"
  java -Dnet.bytebuddy.experimental=true \
    -cp "$WORK_DIR/tests:$WORK_DIR/bundle/zomdroid-agent.jar:$game_jar" \
    ChunkOptimizationRealJarSmoke "$game_jar" "$proof" "$game_sha"

  grep -q 'mechanism=chunk_foraging state=active' "$proof"
  grep -q 'mechanism=chunk_neighbour_worker state=active' "$proof"
  grep -q 'mechanism=chunk_neighbour_main state=active' "$proof"
  grep -q 'mechanism=chunk_grid_load state=active' "$proof"
  grep -q 'mechanism=chunk_vehicle_index state=active' "$proof"
  grep -q 'mechanism=chunk_randomized_buildings state=active' "$proof"
  grep -q 'mechanism=chunk_lua_mapobjects state=active' "$proof"
  grep -q 'mechanism=chunk_worldgen_biome state=active' "$proof"
  grep -q 'mechanism=chunk_cp2c_dirty_clear state=active' "$proof"
  if grep -Eq 'state=(unsupported|blocked_install)' "$proof"; then
    echo "unexpected fallback in $game_jar" >&2
    grep -E 'state=(unsupported|blocked_install)' "$proof" >&2
    exit 1
  fi
done

echo "CHUNK_OPTIMIZATION_BOTH_REAL_JARS PASS"
