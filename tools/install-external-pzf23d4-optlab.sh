#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# R2 is the canonical OPT-LAB package. APP remains overridable for an intentional
# legacy/R1 operation, but the script must never silently write to mglpz1.
APP="${APP:-com.zomdroid.mglpz2}"
APP_FILES="/data/user/0/$APP/files"
LIB_DIR="$APP_FILES/dependencies/libs/android-arm64-v8a"
LIB="$LIB_DIR/libMobileGLPZ.so"
STAGED="$LIB.optlab-staged"
EXPECTED_SHA='f5b280fac78f2189daeac41d7d6b456747e1e1970754bcf807135ae33af82e8d'
STAMP="$(date '+%Y%m%d-%H%M%S')"
SOURCE="${1:-}"
export RISH_APPLICATION_ID='com.termux'

fail() {
    printf 'EROARE: %s\n' "$*" >&2
    exit 1
}

remote_sha() {
    rish -c "run-as $APP /system/bin/toybox sha256sum '$1'" 2>/dev/null \
        | tr -d '\r' \
        | awk 'length($1)==64 && $1 !~ /[^0-9a-f]/ {print $1}' \
        | tail -n 1 || true
}

test -n "$SOURCE" || fail \
    'Utilizare: bash install-external-pzf23d4-optlab.sh /cale/libMobileGLPZ-PZF23D4.so'
command -v rish >/dev/null 2>&1 || fail 'rish/Shizuku lipsește.'
test -f "$SOURCE" || fail "Fișierul extern nu există: $SOURCE"
test "$(sha256sum "$SOURCE" | awk '{print $1}')" = "$EXPECTED_SHA" || fail \
    'SHA-256 nu corespunde ELF-ului PZF23D4 validat.'

# APK-ul este debuggable; run-as trebuie să funcționeze înainte de orice scriere.
rish -c "run-as $APP /system/bin/id" >/dev/null 2>&1 || fail \
    'run-as nu poate accesa aplicația. Instalează și pornește o dată APK-ul OPT-LAB.'
rish -c "am force-stop $APP" >/dev/null
rish -c "run-as $APP /system/bin/toybox mkdir -p '$LIB_DIR'" >/dev/null

ACTIVE_SHA="$(remote_sha "$LIB")"
if test "$ACTIVE_SHA" = "$EXPECTED_SHA"; then
    printf 'PZF23D4 este deja activ: %s\n' "$EXPECTED_SHA"
    exit 0
fi

if [[ "$ACTIVE_SHA" =~ ^[0-9a-f]{64}$ ]]; then
    BACKUP="$LIB.before-optlab-$STAMP"
    rish -c "run-as $APP /system/bin/sh -c '
        /system/bin/toybox cp -p \"$LIB\" \"$BACKUP\"
        /system/bin/toybox echo \"$ACTIVE_SHA\" > \"$BACKUP.sha256\"
    '" >/dev/null
    test "$(remote_sha "$BACKUP")" = "$ACTIVE_SHA" || fail \
        'Backupul rendererului existent nu a trecut verificarea.'
    printf 'Backup creat: %s (SHA-256 %s)\n' "$BACKUP" "$ACTIVE_SHA"
else
    printf 'Nu există renderer anterior; instalare bootstrap fără backup.\n'
fi

rish -c "run-as $APP /system/bin/sh -c '/system/bin/toybox cat > \"$STAGED\"'" \
    < "$SOURCE"
test "$(remote_sha "$STAGED")" = "$EXPECTED_SHA" || fail \
    'Transferul staged nu a trecut verificarea.'
rish -c "run-as $APP /system/bin/sh -c '
    /system/bin/toybox chmod 755 \"$STAGED\"
    /system/bin/toybox mv -f \"$STAGED\" \"$LIB\"
'" >/dev/null
test "$(remote_sha "$LIB")" = "$EXPECTED_SHA" || fail \
    'Rendererul activ nu are SHA-256 așteptat după instalare.'

printf 'PZF23D4 instalat extern pentru OPT-LAB: %s\n' "$EXPECTED_SHA"
printf 'Pornește aplicația și verifică rendererSha256 în linia [ZD-OPT-LAB].\n'
