#!/usr/bin/env bash
set -euo pipefail
export TZ=UTC

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_4220="${1:-${PZ_4220_JAR:-}}"
JAR_42203="${2:-${PZ_42203_JAR:-}}"
if [[ -z "$JAR_4220" || -z "$JAR_42203" ]]; then
  echo "usage: $0 <projectzomboid-42.20.jar> <projectzomboid-42.20.3.jar>" >&2
  exit 2
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT
mkdir -p "$WORK_DIR/bundle" "$WORK_DIR/classes"
tar --no-same-owner -xf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar" \
  -C "$WORK_DIR/bundle"

java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -cp "$WORK_DIR/bundle/zomdroid-agent.jar" \
  -d "$WORK_DIR/classes" \
  "$PROJECT_DIR/tools/Cp62ExactJarBytecodeUnit.java"

java -Dnet.bytebuddy.experimental=true \
  -cp "$WORK_DIR/classes:$WORK_DIR/bundle/zomdroid-agent.jar" \
  Cp62ExactJarBytecodeUnit "$JAR_4220" "$JAR_42203"
