#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf -- "$BUILD_DIR"' EXIT

mapfile -t STUBS < <(find "$PROJECT_DIR/tools/ui-stubs" -type f -name '*.java' | sort)

java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -d "$BUILD_DIR" \
    "${STUBS[@]}" \
    "$PROJECT_DIR/app/src/main/java/com/zomdroid/fragments/OptLabUi.java" \
    "$PROJECT_DIR/app/src/main/java/com/zomdroid/fragments/OptLabFragment.java"

test -f "$BUILD_DIR/com/zomdroid/fragments/OptLabFragment.class"
test -f "$BUILD_DIR/com/zomdroid/fragments/OptLabUi.class"
echo "OPTLAB_TABBED_UI_COMPILE PASS javac_release=17 tabs=6"
