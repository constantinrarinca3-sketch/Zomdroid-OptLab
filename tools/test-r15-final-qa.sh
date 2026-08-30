#!/usr/bin/env bash
set -euo pipefail
export TZ=UTC

if [[ "$#" -ne 2 ]]; then
  echo "usage: $0 /path/to/projectzomboid-42.20.jar /path/to/projectzomboid-42.20.3.jar" >&2
  exit 2
fi

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_4220="$1"
JAR_42203="$2"
EXPECTED_4220="e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8"
EXPECTED_42203="bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

fail() {
  echo "R15_FINAL_QA FAIL $*" >&2
  exit 1
}

assert_sha() {
  local path="$1"
  local expected="$2"
  [[ -f "$path" ]] || fail "missing=$path"
  local actual
  actual="$(sha256sum "$path" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] \
    || fail "sha file=$path actual=$actual expected=$expected"
}

run() {
  echo "[R15-05] RUN $*"
  "$@"
}

assert_sha "$JAR_4220" "$EXPECTED_4220"
assert_sha "$JAR_42203" "$EXPECTED_42203"

run bash -n "$PROJECT_DIR"/tools/*.sh
run xz -t "$PROJECT_DIR/app/src/main/assets/bundles/jre21.tar.xz"
run xz -t "$PROJECT_DIR/app/src/main/assets/bundles/jre25.tar.xz"
run xz -t "$PROJECT_DIR/app/src/main/assets/bundles/libs.tar.xz"
run tar -tf "$PROJECT_DIR/app/src/main/assets/bundles/jars.tar"

rg -o '"zomdroid\.optlab\.[^"]+"' \
  "$PROJECT_DIR/app/src/main/java/com/zomdroid/OptLabFeatureRegistry.java" \
  | sed 's/^[^:]*://' | LC_ALL=C sort -u > "$WORK_DIR/registry-properties"
rg -o '"zomdroid\.optlab\.[^"]+"' \
  "$PROJECT_DIR/optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5/zomdroid-agent/src/main/java/com/zomdroid/agent/Main.java" \
  | sed 's/^[^:]*://' | LC_ALL=C sort -u > "$WORK_DIR/agent-properties"
diff -u "$WORK_DIR/registry-properties" "$WORK_DIR/agent-properties" \
  || fail "registry/agent property coverage"
echo "R15_PROPERTY_COVERAGE PASS registry_agent=1_to_1"

run bash "$PROJECT_DIR/tools/build-optlab-agent.sh"
run bash "$PROJECT_DIR/tools/test-optlab-agent-source.sh"
run bash "$PROJECT_DIR/tools/test-optlab-registry-integrity.sh"
run bash "$PROJECT_DIR/tools/test-optlab-tabbed-ui-source.sh"
run bash "$PROJECT_DIR/tools/test-cp62-apk-wiring.sh"
run bash "$PROJECT_DIR/tools/test-mobilegl-default-payload.sh"
run bash "$PROJECT_DIR/tools/test-archive-path-guard.sh"
run bash "$PROJECT_DIR/tools/test-native-modules-source.sh"
run bash "$PROJECT_DIR/tools/test-pathfinding-native-payload.sh"
run bash "$PROJECT_DIR/tools/test-popman-native-source.sh"
run bash "$PROJECT_DIR/tools/test-jassimp-direct.sh"
run bash "$PROJECT_DIR/tools/test-cp1-proof-validator.sh"

run bash "$PROJECT_DIR/tools/test-cp6134-build-direct.sh" "$JAR_4220" "$JAR_42203"
run bash "$PROJECT_DIR/tools/test-cp6134-fbo-inner-loop.sh" "$JAR_4220" "$JAR_42203"
run bash "$PROJECT_DIR/tools/test-cp61310-same-program-bind.sh" "$JAR_4220" "$JAR_42203"
run bash "$PROJECT_DIR/tools/test-cp62-exact-jars.sh" "$JAR_4220" "$JAR_42203"
run bash "$PROJECT_DIR/tools/test-cp66-hotpath-real-jars.sh" "$JAR_4220" "$JAR_42203"
run bash "$PROJECT_DIR/tools/test-chunk-optimization-real-jars.sh" "$JAR_4220" "$JAR_42203"
run bash "$PROJECT_DIR/tools/test-pathfinding-real-jars.sh" "$JAR_4220" "$JAR_42203"

echo "R15_FINAL_QA PASS source_host=1 exact_42_20=1 exact_42_20_3=1 apk_compile=NOT_RUN device_test=NO"
