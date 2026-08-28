#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libMobileGLPZDefault.so"
EXPECTED_SHA="f8c2851d9c3cbadc40c73f09c5ec9430a53a0e815cee3e2ec1392dcb4f9fa10a"
EXPECTED_SIZE=14358432
EXPECTED_BUILD_ID="da5508480c21ecc5a13d60539cd53d90860c46dd"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

[[ -f "$LIB" ]] || { echo "MOBILEGL_DEFAULT FAIL missing payload" >&2; exit 1; }
actual_sha="$(sha256sum "$LIB" | cut -d' ' -f1)"
actual_size="$(stat -c '%s' "$LIB")"
[[ "$actual_sha" == "$EXPECTED_SHA" ]] || {
  echo "MOBILEGL_DEFAULT FAIL sha=$actual_sha" >&2; exit 1;
}
[[ "$actual_size" == "$EXPECTED_SIZE" ]] || {
  echo "MOBILEGL_DEFAULT FAIL size=$actual_size" >&2; exit 1;
}

readelf -h "$LIB" > "$WORK_DIR/header"
readelf -lW "$LIB" > "$WORK_DIR/segments"
readelf -dW "$LIB" > "$WORK_DIR/dynamic"
readelf -n "$LIB" > "$WORK_DIR/notes"
readelf --dyn-syms -W "$LIB" > "$WORK_DIR/symbols"
strings -a "$LIB" > "$WORK_DIR/strings"

grep -Eq 'Class:[[:space:]]+ELF64' "$WORK_DIR/header"
grep -Eq 'Data:[[:space:]]+2.s complement, little endian' "$WORK_DIR/header"
grep -Eq 'Machine:[[:space:]]+AArch64' "$WORK_DIR/header"
grep -Fq "Build ID: $EXPECTED_BUILD_ID" "$WORK_DIR/notes"
grep -Fq 'Library soname: [libMobileGLPZ.so]' "$WORK_DIR/dynamic"
grep -Fq 'BIND_NOW' "$WORK_DIR/dynamic"
grep -q 'GNU_RELRO' "$WORK_DIR/segments"
if awk '$1=="GNU_STACK" && $0~/RWE/ {exit 1}' "$WORK_DIR/segments"; then :; else
  echo "MOBILEGL_DEFAULT FAIL executable stack" >&2; exit 1
fi
load_count="$(awk '$1=="LOAD" {count++; if ($NF!="0x4000") bad++} END {
  if (bad) exit 1; print count+0
}' "$WORK_DIR/segments")"
[[ "$load_count" == 3 ]] || { echo "MOBILEGL_DEFAULT FAIL LOAD count" >&2; exit 1; }

awk '/\(NEEDED\)/ {gsub(/[][]/,"",$5); print $5}' "$WORK_DIR/dynamic" \
  | LC_ALL=C sort -u > "$WORK_DIR/needed.actual"
printf '%s\n' libc.so libandroid.so libdl.so liblog.so libm.so libvulkan.so \
  | LC_ALL=C sort -u > "$WORK_DIR/needed.expected"
diff -u "$WORK_DIR/needed.expected" "$WORK_DIR/needed.actual"

for symbol in \
  eglGetProcAddress glXGetProcAddress glXGetProcAddressARB \
  glGetString glGetIntegerv glShaderSource glCompileShader glLinkProgram \
  glDrawElements glDrawArrays glTexImage2D glReadPixels; do
  awk -v wanted="$symbol" '$7!="UND" && $8==wanted {found=1} END {exit !found}' \
    "$WORK_DIR/symbols"
done

for marker in \
  'V1.2-PRESENT-FASTPATH-TARGET-THINLTO' \
  'MOBILEGL_PZ_PRESENT_FASTPATH' \
  'MOBILEGL_PZ_OPT_SET' \
  'MGLPZ_OPT_CONFIG schema=6' \
  'MOBILEGL_PZ_FILES_DIR' \
  'MOBILEGL_PZ_PROOF_FILE' \
  'com.zomdroid.mglpz2'; do
  grep -Fq "$marker" "$WORK_DIR/strings"
done

grep -Fq "$EXPECTED_SHA" \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/MobileGlDefaultRendererManager.java"
grep -Fq 'private static final long DEFAULT_SIZE = 14_358_432L;' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/patch/MobileGlDefaultRendererManager.java"

echo "MOBILEGL_DEFAULT_PAYLOAD PASS sha256=$actual_sha size=$actual_size build_id=$EXPECTED_BUILD_ID load_align=0x4000"
