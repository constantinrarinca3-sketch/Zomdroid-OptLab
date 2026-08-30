#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

mapfile -d '' STUBS < <(find "$PROJECT_DIR/tools/native-module-stubs" -type f -name '*.java' \
  -print0 | LC_ALL=C sort -z)

java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -d "$WORK_DIR/classes" \
  "${STUBS[@]}" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/ElfSymbols.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/OptLabFeatureRegistry.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/DistinctFileBackup.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/LightingArm64AbManager.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/MobileGlDefaultRendererManager.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/PathfindingWorkaround.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/PathfindingNativeManager.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/PopManNativeManager.java" \
  "$PROJECT_DIR/tools/NativeModulesSourceUnit.java" \
  "$PROJECT_DIR/tools/BackupPreservationUnit.java" \
  "$PROJECT_DIR/tools/OptLabRegistryUnit.java"

UNIT_ARGS=("$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a")
if (( $# > 0 )); then UNIT_ARGS+=("$1"); fi
java -cp "$WORK_DIR/classes" NativeModulesSourceUnit "${UNIT_ARGS[@]}"
java -cp "$WORK_DIR/classes" com.zomdroid.patch.BackupPreservationUnit
java -cp "$WORK_DIR/classes" OptLabRegistryUnit

# Pathfinding or PopMan can be the only enabled agent mechanism. Either must still opt
# Byte Buddy into Java 25 class-file support before PZ classes are transformed.
grep -q 'boolean agentWorkRequested = optLab.isAnyAgentOptimizationEnabled()' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/GameLauncher.java"
grep -q '|| pathfindingNative.active || popManNative.active;' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/GameLauncher.java"
grep -q '8dc064f0386d01fccab8b94681921fdf9c304ed8995e87e07f1906119728310f' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/MobileGlDefaultRendererManager.java"
grep -q 'MOBILEGL_PZ_PRESENT_FASTPATH", "1"' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/GameLauncher.java"
grep -q 'MOBILEGL_PZ_OPT_SET", "002,003"' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/GameLauncher.java"

echo "NATIVE_MODULES_LAUNCHER_GATE PASS"
