#!/usr/bin/env bash
set -euo pipefail
export TZ=UTC

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_SOURCE_DIR="$PROJECT_DIR/optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5/zomdroid-agent"
BUNDLE_PATH="$PROJECT_DIR/app/src/main/assets/bundles/jars.tar"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

mkdir -p "$WORK_DIR/bundle" "$WORK_DIR/classes" "$WORK_DIR/chunk-stub-classes"
tar --no-same-owner -xf "$BUNDLE_PATH" -C "$WORK_DIR/bundle"
cp "$WORK_DIR/bundle/zomdroid-agent.jar" "$WORK_DIR/zomdroid-agent.jar"

mapfile -d '' CHUNK_STUBS < <(find "$PROJECT_DIR/tools/chunk-optimization-stubs" \
  -type f -name '*.java' -print0 | LC_ALL=C sort -z)
java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -d "$WORK_DIR/chunk-stub-classes" \
  "${CHUNK_STUBS[@]}"

java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -cp "$WORK_DIR/zomdroid-agent.jar:$WORK_DIR/chunk-stub-classes" \
  -d "$WORK_DIR/classes" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/Main.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/ChunkOptimizationRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/FeatureCompatibility.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/FboInnerLoopRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/PacingRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/PathfindingRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/PopManRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/ProofRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/RenderOptimizationRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/SameProgramBindRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/StreamCoreRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/FboRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/com/zomdroid/agent/optimization/HotPathOptimizationRuntime.java" \
  "$AGENT_SOURCE_DIR/src/main/java/zombie/core/ZDOptBuildFast.java" \
  "$AGENT_SOURCE_DIR/src/main/java/zombie/core/ZDOptSameProgramBindFast.java" \
  "$AGENT_SOURCE_DIR/src/main/java/zombie/core/ZDOptStateRunFast.java" \
  "$AGENT_SOURCE_DIR/src/main/java/zombie/core/opengl/ZDOptMvpFast.java" \
  "$AGENT_SOURCE_DIR/src/main/java/zombie/core/textures/ZDOptTextureBindFast.java" \
  "$AGENT_SOURCE_DIR/src/main/java/zombie/iso/fboRenderChunk/ZDOptFboPrepareFast.java"

# Keep overlay entry timestamps stable so rebuilding the same sources reproduces the jar bytes.
find "$WORK_DIR/classes/com/zomdroid/agent" -type f -name '*.class' \
  -exec touch -d '@1748649600' {} +
find "$WORK_DIR/classes/zombie" -type f -name 'ZDOpt*.class' \
  -exec touch -d '@1748649600' {} +

(
  cd "$WORK_DIR/classes"
  zip -q -d "$WORK_DIR/zomdroid-agent.jar" \
    'com/zomdroid/agent/Main*.class' \
    'com/zomdroid/agent/optimization/*.class' \
    'zombie/core/ZDOpt*.class' \
    'zombie/core/opengl/ZDOpt*.class' \
    'zombie/core/textures/ZDOpt*.class' \
    'zombie/iso/fboRenderChunk/ZDOpt*.class'
  find com/zomdroid/agent zombie/core zombie/iso/fboRenderChunk -type f \
    \( -name '*.class' -a \( -path 'com/zomdroid/agent/*' -o -name 'ZDOpt*.class' \) \) \
    -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 zip -q "$WORK_DIR/zomdroid-agent.jar"
)

cp "$WORK_DIR/zomdroid-agent.jar" "$WORK_DIR/bundle/zomdroid-agent.jar"
tar --sort=name --mtime='@1748649600' --owner=0 --group=0 --numeric-owner \
  -cf "$WORK_DIR/jars.tar" -C "$WORK_DIR/bundle" .

unzip -tq "$WORK_DIR/zomdroid-agent.jar"
unzip -p "$WORK_DIR/zomdroid-agent.jar" META-INF/MANIFEST.MF \
  | grep -q '^Premain-Class: com.zomdroid.agent.Main'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/FeatureCompatibility.class'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/ChunkOptimizationRuntime.class'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/RenderOptimizationRuntime.class'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/HotPathOptimizationRuntime.class'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/FboInnerLoopRuntime.class'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/SameProgramBindRuntime.class'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'zombie/core/ZDOptSameProgramBindFast.class'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'zombie/iso/fboRenderChunk/ZDOptFboPrepareFast.class'
unzip -Z1 "$WORK_DIR/zomdroid-agent.jar" \
  | grep -qx 'zombie/core/opengl/ZDOptMvpFast.class'
unzip -p "$WORK_DIR/zomdroid-agent.jar" com/zomdroid/agent/Main.class \
  | grep -aFq '[ZD-OPT-LAB-AGENT] version=15 schema=14 loaded'
tar --no-same-owner -tf "$WORK_DIR/jars.tar" | grep -qx './zomdroid-agent.jar'

# Publish only after the candidate jar and archive have both passed validation. A failed rebuild
# therefore leaves the last known-good APK bundle byte-for-byte intact.
mv "$WORK_DIR/jars.tar" "$BUNDLE_PATH"
sha256sum "$WORK_DIR/zomdroid-agent.jar" "$BUNDLE_PATH"
