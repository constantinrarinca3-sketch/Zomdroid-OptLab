#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_PAYLOAD="$PROJECT_DIR/optlab_deps/jassimp-direct-0dbe092850d5cf528dbdfac01603d9d1bb799d04/payload/libjassimp64.so"
PAYLOAD="${1:-$DEFAULT_PAYLOAD}"
BUNDLE="$PROJECT_DIR/app/src/main/assets/bundles/libs.tar.xz"
EXPECTED_SHA="a73942ea3a4cdb25cd989661306151f35368af314e5e6d5b2fd20688f6609ac4"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

[[ -f "$PAYLOAD" ]] || { echo "Missing JAssimp payload: $PAYLOAD" >&2; exit 1; }
actual_sha="$(sha256sum "$PAYLOAD" | awk '{print $1}')"
if [[ "$actual_sha" != "$EXPECTED_SHA" && "${ALLOW_REBUILT_JASSIMP:-0}" != "1" ]]; then
  echo "Refusing unknown JAssimp payload sha256=$actual_sha" >&2
  echo "Set ALLOW_REBUILT_JASSIMP=1 only for an audited clean rebuild." >&2
  exit 1
fi

readelf -h "$PAYLOAD" | grep -Eq 'Machine:[[:space:]]+AArch64'
readelf -dW "$PAYLOAD" | grep -Fq 'Library soname: [libjassimp64.so]'

mkdir -p "$WORK_DIR/root"
xz -dc "$BUNDLE" | tar --no-same-owner -xf - -C "$WORK_DIR/root"
TARGET="$WORK_DIR/root/android-arm64-v8a/libjassimp64.so"
[[ -f "$TARGET" ]] || { echo "libs.tar.xz has no libjassimp64.so" >&2; exit 1; }
target_mode="$(stat -c '%a' "$TARGET")"
install -m "$target_mode" "$PAYLOAD" "$TARGET"

tar --sort=name --format=gnu --mtime='UTC 2026-08-28 00:00:00' \
  --owner=0 --group=0 --numeric-owner -cf "$WORK_DIR/libs.tar" -C "$WORK_DIR/root" .
xz --threads=1 -9e --check=crc64 --keep "$WORK_DIR/libs.tar"
install -m 0644 "$WORK_DIR/libs.tar.xz" "$BUNDLE"

if [[ "$actual_sha" == "$EXPECTED_SHA" ]]; then
  "$PROJECT_DIR/tools/test-jassimp-direct.sh"
else
  echo "Installed audited rebuild candidate sha256=$actual_sha; run candidate ABI verification." >&2
  "$PROJECT_DIR/tools/test-jassimp-direct.sh" "$PAYLOAD"
fi

echo "JASSIMP_DIRECT_INSTALLED bundle=$(sha256sum "$BUNDLE" | awk '{print $1}') payload=$actual_sha"
