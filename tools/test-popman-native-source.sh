#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PAYLOAD="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libPZPopManB4220.so"
BRIDGE="$PROJECT_DIR/app/src/main/cpp/popman_savecell_bridge.c"
MANAGER="$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/PopManNativeManager.java"
EXPECTED_SHA='eec5ac6f8fb60964cbd9b49c1525ac521f52191484439651b48278f5b8b684af'
EXPECTED_SIZE='9539608'

test -f "$PAYLOAD"
test "$(wc -c < "$PAYLOAD" | tr -d ' ')" = "$EXPECTED_SIZE"
test "$(sha256sum "$PAYLOAD" | awk '{print $1}')" = "$EXPECTED_SHA"
readelf -h "$PAYLOAD" | grep -q 'Machine:.*AArch64'
readelf -n "$PAYLOAD" | grep -q '5d14d77db7656224cc78114ec553cb9d6c363323'

EXPORTS="$(readelf -Ws "$PAYLOAD" | awk '{print $8}')"
EXPECTED_EXPORTS=(
  Java_zombie_popman_ZombiePopulationManager_n_1init
  Java_zombie_popman_ZombiePopulationManager_n_1config
  Java_zombie_popman_ZombiePopulationManager_n_1configFloat
  Java_zombie_popman_ZombiePopulationManager_n_1configInt
  Java_zombie_popman_ZombiePopulationManager_n_1setSpawnOrigins
  Java_zombie_popman_ZombiePopulationManager_n_1setOutfitNames
  Java_zombie_popman_ZombiePopulationManager_n_1updateMain
  Java_zombie_popman_ZombiePopulationManager_n_1hasDataForThread
  Java_zombie_popman_ZombiePopulationManager_n_1readyToPause
  Java_zombie_popman_ZombiePopulationManager_n_1updateThread
  Java_zombie_popman_ZombiePopulationManager_n_1shouldWait
  Java_zombie_popman_ZombiePopulationManager_n_1beginSaveRealZombies
  Java_zombie_popman_ZombiePopulationManager_n_1saveRealZombies
  Java_zombie_popman_ZombiePopulationManager_n_1save
  Java_zombie_popman_ZombiePopulationManager_n_1stop
  Java_zombie_popman_ZombiePopulationManager_n_1addZombie
  Java_zombie_popman_ZombiePopulationManager_n_1aggroTarget
  Java_zombie_popman_ZombiePopulationManager_n_1loadChunk
  Java_zombie_popman_ZombiePopulationManager_n_1loadedAreas
  Java_zombie_popman_ZombiePopulationManager_n_1realZombieCount
  Java_zombie_popman_ZombiePopulationManager_n_1spawnHorde
  Java_zombie_popman_ZombiePopulationManager_n_1worldSound
  Java_zombie_popman_ZombiePopulationManager_n_1getAddZombieCount
  Java_zombie_popman_ZombiePopulationManager_n_1getAddZombieData
  Java_zombie_popman_ZombiePopulationManager_n_1hasRadarData
  Java_zombie_popman_ZombiePopulationManager_n_1requestRadarData
  Java_zombie_popman_ZombiePopulationManager_n_1getRadarZombieData
)
for symbol in "${EXPECTED_EXPORTS[@]}"; do
  grep -qx "$symbol" <<< "$EXPORTS"
done
if grep -qx 'Java_zombie_popman_ZombiePopulationManager_n_1saveCell' <<< "$EXPORTS"; then
  echo 'POPMAN_NATIVE_SOURCE FAIL payload unexpectedly exports n_saveCell' >&2
  exit 1
fi

grep -q 'BRIDGE_SEMANTICS_VERIFIED = true' "$MANAGER"
grep -q 'MANAGER_MAIN_INSTANCE_OFFSET.*0x261b60' "$BRIDGE"
grep -q 'GET_CELL_FROM_WORLD_POS_OFFSET.*0x07968c' "$BRIDGE"
grep -q 'ARRAY_LIST_ZOMBIE_ADD_OFFSET.*0x0687ec' "$BRIDGE"
grep -q 'OBJECT_POOL_ZOMBIE_RELEASE_OFFSET.*0x07d87c' "$BRIDGE"
grep -q 'recycle_pending_zombies' "$BRIDGE"
grep -q 'Java_zombie_popman_ZombiePopulationManager_n_1saveCell' "$BRIDGE"

cc -std=c11 -Wall -Wextra -Werror \
  -I "$PROJECT_DIR/tools/c-stubs" \
  -fsyntax-only "$BRIDGE"

if (( $# > 0 )); then
  declare -A EXPECTED_CLASSES=(
    [126fbf46d4b0ee77c6b85a4781e270fa71bcb35d405596bd130f1c276302e8dc]=1
    [d78514c622b513e5c43b5f6ab2f523f8a6bd64ae4f6efdde16d590351624072e]=1
  )
  for jar in "$@"; do
    test -f "$jar"
    class_sha="$(unzip -p "$jar" zombie/popman/ZombiePopulationManager.class | sha256sum \
      | awk '{print $1}')"
    test "${EXPECTED_CLASSES[$class_sha]:-0}" = 1
    unset 'EXPECTED_CLASSES[$class_sha]'
  done
  test "${#EXPECTED_CLASSES[@]}" = 0
fi

echo "POPMAN_NATIVE_SOURCE PASS sha256=$EXPECTED_SHA exports=${#EXPECTED_EXPORTS[@]} bridge_syntax=PASS"
