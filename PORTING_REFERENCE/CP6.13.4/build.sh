#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf classes compile-stub-classes .stubs.list .src.list
mkdir -p classes compile-stub-classes
find compile-stubs -name '*.java' -print > .stubs.list
javac --release 8 -d compile-stub-classes @.stubs.list
find src -name '*.java' -print > .src.list
javac --release 8 -cp compile-stub-classes -d classes @.src.list
rm -rf jar-stage
mkdir -p jar-stage
# Stage only runtime agent classes/helpers. A single -C avoids jar(1) path-state ambiguity.
mkdir -p jar-stage/mglpz jar-stage/zombie
cp -R classes/mglpz/chunkagent jar-stage/mglpz/
cp -R classes/zombie/* jar-stage/zombie/
jar cfm ../MGLPZ-Performance-CP6.13.4-HOTPATH-CPU-ALLOC.jar MANIFEST.MF -C jar-stage .
