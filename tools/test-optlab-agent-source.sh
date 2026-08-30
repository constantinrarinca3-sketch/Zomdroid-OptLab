#!/usr/bin/env bash
set -euo pipefail
export TZ=UTC

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_SOURCE_DIR="$PROJECT_DIR/optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5/zomdroid-agent"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

mkdir -p "$WORK_DIR/bundle" "$WORK_DIR/classes" "$WORK_DIR/tests" \
  "$WORK_DIR/chunk-stub-classes"
tar --no-same-owner -xf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar" \
  -C "$WORK_DIR/bundle"

# Reject a source/bundle mismatch before tests can accidentally validate freshly compiled
# classes while the APK would still package an older agent.
unzip -tq "$WORK_DIR/bundle/zomdroid-agent.jar"
unzip -Z1 "$WORK_DIR/bundle/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/FeatureCompatibility.class'
unzip -p "$WORK_DIR/bundle/zomdroid-agent.jar" com/zomdroid/agent/Main.class \
  | grep -aFq '[ZD-OPT-LAB-AGENT] version=15 schema=14 loaded'
unzip -Z1 "$WORK_DIR/bundle/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/FboInnerLoopRuntime.class'
unzip -Z1 "$WORK_DIR/bundle/zomdroid-agent.jar" \
  | grep -qx 'zombie/iso/fboRenderChunk/ZDOptFboPrepareFast.class'
unzip -Z1 "$WORK_DIR/bundle/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/SameProgramBindRuntime.class'
unzip -Z1 "$WORK_DIR/bundle/zomdroid-agent.jar" \
  | grep -qx 'zombie/core/ZDOptSameProgramBindFast.class'
unzip -Z1 "$WORK_DIR/bundle/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/RenderOptimizationRuntime.class'
unzip -Z1 "$WORK_DIR/bundle/zomdroid-agent.jar" \
  | grep -qx 'com/zomdroid/agent/optimization/HotPathOptimizationRuntime.class'
unzip -Z1 "$WORK_DIR/bundle/zomdroid-agent.jar" \
  | grep -qx 'zombie/core/ZDOptStateRunFast.class'

mapfile -d '' CHUNK_STUBS < <(find "$PROJECT_DIR/tools/chunk-optimization-stubs" \
  -type f -name '*.java' -print0 | LC_ALL=C sort -z)
java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -d "$WORK_DIR/chunk-stub-classes" \
  "${CHUNK_STUBS[@]}"

java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -cp "$WORK_DIR/bundle/zomdroid-agent.jar:$WORK_DIR/chunk-stub-classes" \
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

mapfile -d '' STUBS < <(find "$PROJECT_DIR/tools/optlab-stubs" -type f -name '*.java' \
  -print0 | LC_ALL=C sort -z)
java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 17 \
  -cp "$WORK_DIR/classes:$WORK_DIR/chunk-stub-classes:$WORK_DIR/bundle/zomdroid-agent.jar" \
  -d "$WORK_DIR/tests" \
  "${STUBS[@]}" \
  "$PROJECT_DIR/tools/FeatureCompatibilityUnit.java" \
  "$PROJECT_DIR/tools/Cp6134BuildDirectParityUnit.java" \
  "$PROJECT_DIR/tools/HotPathOptimizationRuntimeUnit.java" \
  "$PROJECT_DIR/tools/OptLabRuntimeUnit.java" \
  "$PROJECT_DIR/tools/OptLabPremainPathfindingOnlyUnit.java" \
  "$PROJECT_DIR/tools/OptLabPremainPopManOnlyUnit.java"

java -cp "$WORK_DIR/tests:$WORK_DIR/classes:$WORK_DIR/chunk-stub-classes" FeatureCompatibilityUnit
java -cp "$WORK_DIR/tests:$WORK_DIR/classes:$WORK_DIR/chunk-stub-classes" \
  HotPathOptimizationRuntimeUnit
java -cp "$WORK_DIR/tests:$WORK_DIR/classes:$WORK_DIR/chunk-stub-classes" \
  zombie.core.Cp6134BuildDirectParityUnit
java -cp "$WORK_DIR/tests:$WORK_DIR/classes:$WORK_DIR/chunk-stub-classes" OptLabRuntimeUnit
java -cp "$WORK_DIR/tests:$WORK_DIR/classes:$WORK_DIR/bundle/zomdroid-agent.jar" \
  OptLabPremainPathfindingOnlyUnit
java -cp "$WORK_DIR/tests:$WORK_DIR/classes:$WORK_DIR/bundle/zomdroid-agent.jar" \
  OptLabPremainPopManOnlyUnit
