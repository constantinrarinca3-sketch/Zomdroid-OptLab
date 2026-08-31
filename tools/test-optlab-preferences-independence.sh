#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

mapfile -d '' STUBS < <(find "$PROJECT_DIR/tools/preferences-stubs" \
  -type f -name '*.java' -print0 | LC_ALL=C sort -z)

java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -d "$WORK_DIR/classes" \
  "${STUBS[@]}" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/OptLabPreferences.java" \
  "$PROJECT_DIR/tools/OptLabPreferencesIndependenceUnit.java"

java -cp "$WORK_DIR/classes" OptLabPreferencesIndependenceUnit
