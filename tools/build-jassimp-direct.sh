#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RECIPE_DIR="$PROJECT_DIR/optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5"
OUTPUT="${1:-$PROJECT_DIR/build/jassimp-direct/libjassimp64.so}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-30}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
BUILD_TYPE_CMAKE="${BUILD_TYPE_CMAKE:-Release}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

need_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing command: $1" >&2; exit 1; }
}

need_command git
need_command cmake
need_command readelf
need_command nm
[[ -n "$ANDROID_NDK_HOME" ]] || { echo "ANDROID_NDK_HOME is required" >&2; exit 1; }
[[ -n "${JAVA_HOME:-}" ]] || { echo "JAVA_HOME is required" >&2; exit 1; }
[[ "$ANDROID_ABI" == "arm64-v8a" ]] || { echo "CP3 supports only arm64-v8a" >&2; exit 1; }
TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
STRIP_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[[ -f "$TOOLCHAIN_FILE" ]] || { echo "Missing NDK toolchain: $TOOLCHAIN_FILE" >&2; exit 1; }

git clone --depth 1 --branch v5.4.3 --single-branch \
  https://github.com/assimp/assimp.git "$WORK_DIR/assimp"
git -C "$WORK_DIR/assimp" apply "$RECIPE_DIR/patches/assimp/0001.patch"
git -C "$WORK_DIR/assimp" apply "$RECIPE_DIR/patches/assimp/0002.patch"

cmake -S "$WORK_DIR/assimp" -B "$WORK_DIR/build" \
  -DBUILD_SHARED_LIBS=OFF \
  -DASSIMP_BUILD_TESTS=OFF \
  -DASSIMP_INSTALL=ON \
  -DASSIMP_NO_EXPORT=ON \
  -DASSIMP_BUILD_ALL_IMPORTERS_BY_DEFAULT=OFF \
  -DASSIMP_BUILD_FBX_IMPORTER=ON \
  -DASSIMP_BUILD_GLTF_IMPORTER=ON \
  -DASSIMP_BUILD_X_IMPORTER=ON \
  -DBUILD_JASSIMP=ON \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
  -DANDROID_NDK="$ANDROID_NDK_HOME" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API_LEVEL" \
  -DCMAKE_BUILD_TYPE="$BUILD_TYPE_CMAKE"
cmake --build "$WORK_DIR/build" --parallel "$(nproc)"

BUILT="$WORK_DIR/build/libjassimp64.so"
[[ -f "$BUILT" ]] || { echo "Build did not produce $BUILT" >&2; exit 1; }
if [[ "$BUILD_TYPE_CMAKE" == "Release" && -x "$STRIP_BIN" ]]; then
  "$STRIP_BIN" --strip-unneeded "$BUILT"
fi
mkdir -p "$(dirname -- "$OUTPUT")"
install -m 0755 "$BUILT" "$OUTPUT"
"$PROJECT_DIR/tools/test-jassimp-direct.sh" "$OUTPUT"

echo "JASSIMP_DIRECT_BUILD PASS output=$OUTPUT sha256=$(sha256sum "$OUTPUT" | awk '{print $1}')"
