#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

mkdir -p "$WORK_DIR/bundle" "$WORK_DIR/unit" "$WORK_DIR/smoke"
tar --no-same-owner -xf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar" \
  -C "$WORK_DIR/bundle"
AGENT_JAR="$WORK_DIR/bundle/zomdroid-agent.jar"

mapfile -t UNIT_STUBS < <(find "$PROJECT_DIR/tools/optlab-stubs" -type f -name '*.java' \
  -print | LC_ALL=C sort)
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 \
  -cp "$AGENT_JAR" -d "$WORK_DIR/unit" \
  "${UNIT_STUBS[@]}" "$PROJECT_DIR/tools/OptLabRuntimeUnit.java"
java -cp "$WORK_DIR/unit:$AGENT_JAR" OptLabRuntimeUnit

mapfile -t MODPATH_STUBS < <(find "$PROJECT_DIR/tools/modpath-stubs" -type f \
  -name '*.java' -print | LC_ALL=C sort)
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 \
  -d "$WORK_DIR/smoke" "${MODPATH_STUBS[@]}" \
  "$PROJECT_DIR/tools/ModPathAgentSmoke.java"

SMOKE_ROOT="$WORK_DIR/Instanta Dinamica/Zomboid/mods"
java -javaagent:"$AGENT_JAR" \
  -Dnet.bytebuddy.experimental=true \
  -Dzomdroid.modpath.fix=1 \
  -Dzomdroid.modpath.root="$SMOKE_ROOT" \
  -Dzomdroid.optlab.jar.gate=1 \
  -Dzomdroid.optlab.jar.sha256=e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8 \
  -cp "$WORK_DIR/smoke" ModPathAgentSmoke
