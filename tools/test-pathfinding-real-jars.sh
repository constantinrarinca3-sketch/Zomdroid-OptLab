#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 )); then
  echo "usage: $0 /path/to/projectzomboid.jar [...]" >&2
  exit 2
fi

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

tar --no-same-owner -xf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar" -C "$WORK_DIR"
AGENT="$WORK_DIR/zomdroid-agent.jar"
mkdir -p "$WORK_DIR/classes"

java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -cp "$AGENT" \
  -d "$WORK_DIR/classes" \
  "$PROJECT_DIR/tools/OptLabPathfindingBytecodeSmoke.java"

for jar in "$@"; do
  if [[ ! -f "$jar" ]]; then
    echo "missing projectzomboid.jar: $jar" >&2
    exit 1
  fi
  java -Dnet.bytebuddy.experimental=true \
    -cp "$WORK_DIR/classes:$AGENT" \
    OptLabPathfindingBytecodeSmoke "$jar"
done
