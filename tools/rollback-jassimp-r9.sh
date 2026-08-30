#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ROLLBACK="$PROJECT_DIR/optlab_deps/jassimp-direct-0dbe092850d5cf528dbdfac01603d9d1bb799d04/rollback/libjassimp64-r9-unpatched.so"
BUNDLE="$PROJECT_DIR/app/src/main/assets/bundles/libs.tar.xz"
EXPECTED_SHA="095da5c4acb15cc43270c7f98bde1cf4f83e26abbc9c23bd3352fb97d7dbb849"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

if [[ "${CONFIRM_JASSIMP_R9_ROLLBACK:-0}" != "1" ]]; then
  echo "Rollback is intentionally guarded. Set CONFIRM_JASSIMP_R9_ROLLBACK=1." >&2
  exit 1
fi
[[ "$(sha256sum "$ROLLBACK" | awk '{print $1}')" == "$EXPECTED_SHA" ]]
readelf -h "$ROLLBACK" | grep -Eq 'Machine:[[:space:]]+AArch64'
readelf -dW "$ROLLBACK" | grep -Fq 'Library soname: [libjassimp64.so]'

mkdir -p "$WORK_DIR/root"
xz -dc "$BUNDLE" | tar --no-same-owner -xf - -C "$WORK_DIR/root"
TARGET="$WORK_DIR/root/android-arm64-v8a/libjassimp64.so"
[[ -f "$TARGET" ]] || { echo "libs.tar.xz has no libjassimp64.so" >&2; exit 1; }
target_mode="$(stat -c '%a' "$TARGET")"
install -m "$target_mode" "$ROLLBACK" "$TARGET"
tar --sort=name --format=gnu --mtime='UTC 2026-08-28 00:00:00' \
  --owner=0 --group=0 --numeric-owner -cf "$WORK_DIR/libs.tar" -C "$WORK_DIR/root" .
xz --threads=1 -9e --check=crc64 --keep "$WORK_DIR/libs.tar"
install -m 0644 "$WORK_DIR/libs.tar.xz" "$BUNDLE"

mkdir -p "$WORK_DIR/check"
xz -dc "$BUNDLE" | tar --no-same-owner -xf - -C "$WORK_DIR/check"
actual="$(sha256sum "$WORK_DIR/check/android-arm64-v8a/libjassimp64.so" | awk '{print $1}')"
[[ "$actual" == "$EXPECTED_SHA" ]] || { echo "Rollback verification failed" >&2; exit 1; }
echo "JASSIMP_R9_ROLLBACK INSTALLED sha256=$actual bundle=$(sha256sum "$BUNDLE" | awk '{print $1}')"
