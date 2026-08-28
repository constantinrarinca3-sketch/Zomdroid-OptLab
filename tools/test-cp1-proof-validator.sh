#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT
VALIDATOR="$PROJECT_DIR/tools/verify-cp1-device-proof.py"

cat > "$WORK_DIR/on.log" <<'EOF'
[ZD-OPT-PROOF] session=42-test mechanism=PATHFINDING_NATIVE_AVAILABLE state=AVAILABLE detail=reason_AUDITED_B42_ABI
[ZD-OPT-PROOF] session=42-test mechanism=PATHFINDING_NATIVE_ACTIVE state=ACTIVE detail=sha256_x
[ZD-OPT-PROOF] session=42-test mechanism=pathfinding_native_exercised state=exercised detail=native_findPath_completed_normally
EOF
cat > "$WORK_DIR/off.log" <<'EOF'
[ZD-OPT-PROOF] session=42-off mechanism=PATHFINDING_NATIVE_AVAILABLE state=AVAILABLE detail=reason_USER_DISABLED
[ZD-OPT-PROOF] session=42-off mechanism=PATHFINDING_NATIVE_ACTIVE state=OFF detail=sha256_x
EOF
cat > "$WORK_DIR/fallback.log" <<'EOF'
[ZD-OPT-PROOF] session=42-fail mechanism=PATHFINDING_NATIVE_ACTIVE state=ACTIVE detail=sha256_x
[ZD-OPT-PROOF] session=42-fail mechanism=pathfinding_native_fallback state=fallback detail=original_java_polygonalmap2
EOF

python3 "$VALIDATOR" --expect on "$WORK_DIR/on.log"
python3 "$VALIDATOR" --expect off "$WORK_DIR/off.log"
python3 "$VALIDATOR" --expect fallback "$WORK_DIR/fallback.log"
if python3 "$VALIDATOR" --expect on "$WORK_DIR/off.log" >/dev/null 2>&1; then
  echo "CP1_PROOF_VALIDATOR_UNIT FAIL accepted OFF as ON" >&2
  exit 1
fi
echo "CP1_PROOF_VALIDATOR_UNIT PASS"
