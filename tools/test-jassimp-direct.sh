#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CP3_DIR="$PROJECT_DIR/optlab_deps/jassimp-direct-0dbe092850d5cf528dbdfac01603d9d1bb799d04"
RECIPE_DIR="$PROJECT_DIR/optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5"
PAYLOAD="$CP3_DIR/payload/libjassimp64.so"
ROLLBACK="$CP3_DIR/rollback/libjassimp64-r9-unpatched.so"
REFERENCE_AGENT="$CP3_DIR/reference/ZomDroidAssimp53Compat-0.1.0.jar"
PATCH="$RECIPE_DIR/patches/assimp/0002.patch"
BUNDLE="$PROJECT_DIR/app/src/main/assets/bundles/libs.tar.xz"
JARS_BUNDLE="$PROJECT_DIR/app/src/main/assets/bundles/jars.tar"
CANDIDATE="${1:-}"
EXPECTED_DIRECT_SHA="a73942ea3a4cdb25cd989661306151f35368af314e5e6d5b2fd20688f6609ac4"
EXPECTED_ROLLBACK_SHA="095da5c4acb15cc43270c7f98bde1cf4f83e26abbc9c23bd3352fb97d7dbb849"
EXPECTED_AGENT_SHA="af9f00fce0f264e75be6ac5642c63e10b2978aa618fb12ccb463885ae59a954a"
EXPECTED_PATCH_SHA="6079b4f5c9482f09a8dca4eb8d0a00f5fe1bf6e759b4a6cd2f037d011f10b1aa"
EXPECTED_BUILD_ID="ecc1dfeb0746c29f5983526659d139da433459e3"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

fail() {
  echo "JASSIMP_DIRECT FAIL $*" >&2
  exit 1
}

assert_sha() {
  local path="$1"
  local expected="$2"
  [[ -f "$path" ]] || fail "missing=$path"
  local actual
  actual="$(sha256sum "$path" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || fail "sha file=$path actual=$actual expected=$expected"
}

check_elf() {
  local path="$1"
  local label="$2"
  readelf -h "$path" > "$WORK_DIR/$label.header"
  readelf -dW "$path" > "$WORK_DIR/$label.dynamic"
  readelf --dyn-syms -W "$path" > "$WORK_DIR/$label.symbols"
  grep -Eq 'Class:[[:space:]]+ELF64' "$WORK_DIR/$label.header" \
    || fail "$label is not ELF64"
  grep -Eq 'Machine:[[:space:]]+AArch64' "$WORK_DIR/$label.header" \
    || fail "$label is not AArch64"
  grep -Fq 'Library soname: [libjassimp64.so]' "$WORK_DIR/$label.dynamic" \
    || fail "$label SONAME"
  for symbol in \
    Java_jassimp_Jassimp_aiImportFile \
    Java_jassimp_Jassimp_getErrorString \
    Java_jassimp_Jassimp_getQKeysize \
    Java_jassimp_Jassimp_getV3Dsize \
    Java_jassimp_Jassimp_getVKeysize \
    Java_jassimp_Jassimp_getdoublesize \
    Java_jassimp_Jassimp_getfloatsize \
    Java_jassimp_Jassimp_getintsize \
    Java_jassimp_Jassimp_getlongsize \
    Java_jassimp_Jassimp_getuintsize; do
    awk -v wanted="$symbol" '$7 != "UND" && $8 == wanted {found=1} END {exit !found}' \
      "$WORK_DIR/$label.symbols" || fail "$label missing export=$symbol"
  done
}

assert_sha "$PAYLOAD" "$EXPECTED_DIRECT_SHA"
assert_sha "$ROLLBACK" "$EXPECTED_ROLLBACK_SHA"
assert_sha "$REFERENCE_AGENT" "$EXPECTED_AGENT_SHA"
assert_sha "$PATCH" "$EXPECTED_PATCH_SHA"
[[ "$EXPECTED_DIRECT_SHA" != "$EXPECTED_ROLLBACK_SHA" ]] || fail "direct equals rollback"

grep -Fq 'ASSIMP_TAG="v5.4.3"' "$RECIPE_DIR/build-jassimp.sh" \
  || fail "Assimp tag is not pinned"
grep -Fq 'git apply "$PATCH_DIR"/*.patch' "$RECIPE_DIR/build-jassimp.sh" \
  || fail "build recipe does not apply all patches"
grep -Fq 'bone->mOffsetMatrix = cluster->Transform();' "$PATCH" \
  || fail "pre-5.4 offset assignment missing"
grep -Fq 'PZ compatibility revert of assimp commit 384db868' "$PATCH" \
  || fail "compatibility provenance missing"

check_elf "$PAYLOAD" direct
check_elf "$ROLLBACK" rollback
readelf -n "$PAYLOAD" > "$WORK_DIR/direct.notes"
grep -Fq "Build ID: $EXPECTED_BUILD_ID" "$WORK_DIR/direct.notes" \
  || fail "direct build id"

nm -D --defined-only "$PAYLOAD" | awk '{print $3}' | LC_ALL=C sort -u \
  > "$WORK_DIR/direct.exports"
nm -D --defined-only "$ROLLBACK" | awk '{print $3}' | LC_ALL=C sort -u \
  > "$WORK_DIR/rollback.exports"
diff -u "$WORK_DIR/rollback.exports" "$WORK_DIR/direct.exports" \
  || fail "direct/rollback export ABI differs"
[[ "$(wc -l < "$WORK_DIR/direct.exports" | tr -d ' ')" == "3053" ]] \
  || fail "unexpected export count"

mkdir -p "$WORK_DIR/bundle"
xz -dc "$BUNDLE" | tar --no-same-owner -xf - -C "$WORK_DIR/bundle"
BUNDLED_LIB="$WORK_DIR/bundle/android-arm64-v8a/libjassimp64.so"
assert_sha "$BUNDLED_LIB" "$EXPECTED_DIRECT_SHA"
check_elf "$BUNDLED_LIB" bundled
cmp -s "$PAYLOAD" "$BUNDLED_LIB" || fail "bundle is not byte-for-byte direct payload"

if tar -tf "$JARS_BUNDLE" | grep -Eqi 'assimp53compat|ZomDroidAssimp53Compat'; then
  fail "reference Java agent leaked into jars.tar"
fi
unzip -p "$REFERENCE_AGENT" META-INF/MANIFEST.MF > "$WORK_DIR/reference.manifest"
grep -Fq 'Premain-Class: com.zomdroid.assimp53compat.Main' "$WORK_DIR/reference.manifest" \
  || fail "reference agent manifest"
java -m jdk.jdeps/com.sun.tools.javap.Main -classpath "$REFERENCE_AGENT" -c -p \
  'com.zomdroid.assimp53compat.BinaryFbxBindData$Reader' > "$WORK_DIR/reference.reader"
java -m jdk.jdeps/com.sun.tools.javap.Main -classpath "$REFERENCE_AGENT" -c -p \
  com.zomdroid.assimp53compat.CompatRuntime > "$WORK_DIR/reference.runtime"
grep -Fq '// String Transform' "$WORK_DIR/reference.reader" \
  || fail "reference agent does not recover Cluster Transform"
grep -Fq '// String m_offsetMatrix' "$WORK_DIR/reference.runtime" \
  || fail "reference agent does not patch bone offset matrices"
grep -Fq '// String MAKE_LEFT_HANDED' "$WORK_DIR/reference.runtime" \
  || fail "reference agent handedness gate missing"
if grep -RIEq 'assimp53compat|ZomDroidAssimp53Compat' \
  "$PROJECT_DIR/app/src/main/java" "$PROJECT_DIR/app/src/main/cpp"; then
  fail "reference Java agent wired into runtime source"
fi

grep -Fq "$EXPECTED_DIRECT_SHA" "$PROJECT_DIR/app/src/main/java/com/zomdroid/GameLauncher.java" \
  || fail "runtime identity SHA missing"
grep -Fq 'C.deps.LIBS_ANDROID_ARM64_v8a' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/game/PresetManager.java" \
  || fail "bundled ARM64 library path missing"
grep -Fq 'getJavaLibraryPath() + ":."' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/game/GameInstance.java" \
  || fail "bundled library path is not before game working directory"

if [[ -n "$CANDIDATE" ]]; then
  [[ -f "$CANDIDATE" ]] || fail "candidate missing=$CANDIDATE"
  check_elf "$CANDIDATE" candidate
  nm -D --defined-only "$CANDIDATE" | awk '{print $3}' | LC_ALL=C sort -u \
    > "$WORK_DIR/candidate.exports"
  diff -u "$WORK_DIR/direct.exports" "$WORK_DIR/candidate.exports" \
    || fail "candidate export ABI differs"
fi

echo "JASSIMP_DIRECT PASS sha256=$EXPECTED_DIRECT_SHA rollback=$EXPECTED_ROLLBACK_SHA exports=3053 agent=reference-only"
