#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

mapfile -d '' STUBS < <(find "$PROJECT_DIR/tools/preferences-stubs" \
  "$PROJECT_DIR/tools/registry-stubs" -type f -name '*.java' \
  ! -path '*/preferences-stubs/com/zomdroid/OptLabFeatureRegistry.java' \
  -print0 | LC_ALL=C sort -z)

java -m jdk.compiler/com.sun.tools.javac.Main --release 11 \
  -d "$WORK_DIR/classes" \
  "${STUBS[@]}" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/OptLabCustomPresetStore.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/OptLabPreferences.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/NativeModulesPreferences.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/OptLabFeatureRegistry.java" \
  "$PROJECT_DIR/tools/OptLabCustomPresetStoreUnit.java"

java -cp "$WORK_DIR/classes" com.zomdroid.OptLabCustomPresetStoreUnit
