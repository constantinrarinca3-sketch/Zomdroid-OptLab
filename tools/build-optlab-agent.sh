#!/usr/bin/env bash
set -euo pipefail
export TZ=UTC

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_SOURCE_DIR="$PROJECT_DIR/optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5/zomdroid-agent"
BUNDLE_PATH="$PROJECT_DIR/app/src/main/assets/bundles/jars.tar"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

mkdir -p "$WORK_DIR/bundle" "$WORK_DIR/classes"
tar --no-same-owner -xf "$BUNDLE_PATH" -C "$WORK_DIR/bundle"
cp "$WORK_DIR/bundle/zomdroid-agent.jar" "$WORK_DIR/zomdroid-agent.jar"

java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -cp "$WORK_DIR/zomdroid-agent.jar" \
  -d "$WORK_DIR/classes" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/Main.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/PacingRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/ProofRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/ModPathRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/StreamCoreRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/FboRuntime.java"

# Keep overlay entry timestamps stable so rebuilding the same sources reproduces the jar bytes.
find "$WORK_DIR/classes/com/zomdroid/agent" -type f -name '*.class' \
  -exec touch -d '@1748649600' {} +

(
  cd "$WORK_DIR/classes"
  zip -q -d "$WORK_DIR/zomdroid-agent.jar" \
    'com/zomdroid/agent/Main*.class' \
    'com/zomdroid/agent/optimization/*.class'
  find com/zomdroid/agent -type f -name '*.class' -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 zip -q "$WORK_DIR/zomdroid-agent.jar"
)

cp "$WORK_DIR/zomdroid-agent.jar" "$WORK_DIR/bundle/zomdroid-agent.jar"
tar --sort=name --mtime='@1748649600' --owner=0 --group=0 --numeric-owner \
  -cf "$WORK_DIR/jars.tar" -C "$WORK_DIR/bundle" .
mv "$WORK_DIR/jars.tar" "$BUNDLE_PATH"

unzip -tq "$WORK_DIR/zomdroid-agent.jar"
unzip -p "$WORK_DIR/zomdroid-agent.jar" META-INF/MANIFEST.MF \
  | grep -q '^Premain-Class: com.zomdroid.agent.Main'
sha256sum "$WORK_DIR/zomdroid-agent.jar" "$BUNDLE_PATH"
