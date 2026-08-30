#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
OUT="../MGLPZ-Performance-CP6.13.10-SAME-PROGRAM-BIND-FASTPATH.jar"
BASE="baseline/MGLPZ-Performance-CP6.13.9-SHADER-DEPTH-REDUNDANCY-CENSUS.jar"
rm -rf classes compile-stub-classes .stubs.list .src.list overlay-stage
mkdir -p classes compile-stub-classes
find compile-stubs -name '*.java' -print > .stubs.list
javac --release 8 -d compile-stub-classes @.stubs.list
find src -name '*.java' -print > .src.list
javac --release 8 -cp compile-stub-classes -d classes @.src.list
cp "$BASE" "$OUT"
mkdir -p overlay-stage/mglpz/chunkagent overlay-stage/zombie/core
cp classes/mglpz/chunkagent/ClassPatcher.class overlay-stage/mglpz/chunkagent/
cp classes/mglpz/chunkagent/ChunkTransformer.class overlay-stage/mglpz/chunkagent/
cp classes/zombie/core/MGLPZStateRunRenderFast.class overlay-stage/zombie/core/
cp classes/zombie/core/MGLPZSameShaderBindFast.class overlay-stage/zombie/core/
find overlay-stage -exec touch -t 202608301300.00 {} +
jar uf "$OUT" -C overlay-stage .
echo "BUILT=$OUT"
