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
mkdir -p "$WORK_DIR/bundle" "$WORK_DIR/stubs" "$WORK_DIR/tests"
tar --no-same-owner -xf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar" \
  -C "$WORK_DIR/bundle"

mapfile -d '' STUBS < <(find "$PROJECT_DIR/tools/chunk-optimization-stubs" \
  -type f -name '*.java' -print0 | LC_ALL=C sort -z)
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 \
  -d "$WORK_DIR/stubs" "${STUBS[@]}"

java -m jdk.compiler/com.sun.tools.javac.Main --release 17 \
  -cp "$WORK_DIR/bundle/zomdroid-agent.jar:$WORK_DIR/stubs" \
  -d "$WORK_DIR/tests" \
  "$PROJECT_DIR/tools/Cp6134FboInnerLoopParityUnit.java" \
  "$PROJECT_DIR/tools/Cp6134FboInnerLoopExactJarUnit.java"

java -cp "$WORK_DIR/tests:$WORK_DIR/stubs:$WORK_DIR/bundle/zomdroid-agent.jar" \
  zombie.iso.fboRenderChunk.Cp6134FboInnerLoopParityUnit
java -Dnet.bytebuddy.experimental=true \
  -cp "$WORK_DIR/tests:$WORK_DIR/bundle/zomdroid-agent.jar:$1" \
  Cp6134FboInnerLoopExactJarUnit "$1" 42.20
java -Dnet.bytebuddy.experimental=true \
  -cp "$WORK_DIR/tests:$WORK_DIR/bundle/zomdroid-agent.jar:$2" \
  Cp6134FboInnerLoopExactJarUnit "$2" 42.20.3
echo "CP6134_FBO_INNER_LOOP_EXACT_JARS PASS versions=42.20,42.20.3"
