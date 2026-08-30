#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LIBRARY="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libPZPathFindB4220.so"
EXPECTED_SHA='a6719506323898dd79277f08869e9c803886717d92308e531dc34720a581b2dc'
EXPECTED_SIZE='11995392'

test -f "$LIBRARY"
test "$(wc -c < "$LIBRARY" | tr -d ' ')" = "$EXPECTED_SIZE"
test "$(sha256sum "$LIBRARY" | awk '{print $1}')" = "$EXPECTED_SHA"
readelf -h "$LIBRARY" | grep -q 'Machine:.*AArch64'
readelf -d "$LIBRARY" | grep -q 'Library soname: \[libPZPathFind64.so\]'

EXPECTED_EXPORTS=(
  Java_zombie_pathfind_nativeCode_PathfindNative_initWorld
  Java_zombie_pathfind_nativeCode_PathfindNative_destroyWorld
  Java_zombie_pathfind_nativeCode_PathfindNative_freeMemoryAtExit
  Java_zombie_pathfind_nativeCode_PathfindNative_update
  Java_zombie_pathfind_nativeCode_PathfindNative_updateChunk
  Java_zombie_pathfind_nativeCode_PathfindNative_removeChunk
  Java_zombie_pathfind_nativeCode_PathfindNative_updateSquare
  Java_zombie_pathfind_nativeCode_PathfindNative_addVehicle
  Java_zombie_pathfind_nativeCode_PathfindNative_removeVehicle
  Java_zombie_pathfind_nativeCode_PathfindNative_teleportVehicle
  Java_zombie_pathfind_nativeCode_PathfindNative_findPath
  Java_zombie_pathfind_nativeCode_PathfindNativeRenderer_renderNative
  Java_zombie_pathfind_nativeCode_PathfindNativeRenderer_setDebugOption
)

EXPORTS="$(readelf -Ws "$LIBRARY" | awk '{print $8}')"
for symbol in "${EXPECTED_EXPORTS[@]}"; do
  grep -qx "$symbol" <<< "$EXPORTS"
done

echo "PATHFINDING_NATIVE_PAYLOAD PASS sha256=$EXPECTED_SHA exports=${#EXPECTED_EXPORTS[@]}"
