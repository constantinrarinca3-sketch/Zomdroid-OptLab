#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf -- "$BUILD_DIR"' EXIT

java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -d "$BUILD_DIR" \
  "$PROJECT_DIR/tools/preferences-stubs/androidx/annotation/NonNull.java" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/ArchivePathGuard.java" \
  "$PROJECT_DIR/tools/ArchivePathGuardUnit.java"
java -cp "$BUILD_DIR" com.zomdroid.ArchivePathGuardUnit
