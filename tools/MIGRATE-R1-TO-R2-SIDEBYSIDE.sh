#!/usr/bin/env bash
set -euo pipefail

OLD_PACKAGE=${OLD_PACKAGE:-com.zomdroid.mglpz1}
NEW_PACKAGE=${NEW_PACKAGE:-com.zomdroid.mglpz2}

if [[ ! "$OLD_PACKAGE" =~ ^[A-Za-z0-9._]+$ ]] ||
   [[ ! "$NEW_PACKAGE" =~ ^[A-Za-z0-9._]+$ ]]; then
    printf 'EROARE: package Android invalid.\n' >&2
    exit 1
fi

if [[ ${1:-} != "--yes" ]]; then
    printf '%s\n' \
        'Acest script copiază files/dependencies și files/instances din R1 în R2.' \
        'R1 rămâne instalat și nu este șters. Preferințele aplicației nu sunt copiate.' \
        'Pornește mai întâi R2 o dată, închide-l, apoi rulează din nou cu --yes.' \
        "Sursă: $OLD_PACKAGE" \
        "Destinație: $NEW_PACKAGE"
    exit 2
fi

if ! command -v rish >/dev/null 2>&1; then
    printf 'EROARE: rish nu este în PATH. Pornește Shizuku/Rish și încearcă din nou.\n' >&2
    exit 1
fi

export RISH_APPLICATION_ID=${RISH_APPLICATION_ID:-com.termux}

rish -c "run-as '$OLD_PACKAGE' id >/dev/null"
rish -c "run-as '$NEW_PACKAGE' id >/dev/null"
rish -c "am force-stop '$OLD_PACKAGE'; am force-stop '$NEW_PACKAGE'"
rish -c "run-as '$OLD_PACKAGE' /system/bin/toybox test -d files/dependencies"
rish -c "run-as '$OLD_PACKAGE' /system/bin/toybox test -d files/instances"
rish -c "run-as '$NEW_PACKAGE' /system/bin/toybox mkdir -p files"

rish -c "run-as '$OLD_PACKAGE' /system/bin/toybox tar -C files -cf - dependencies instances | run-as '$NEW_PACKAGE' /system/bin/toybox tar -C files -xf -"

printf '%s\n' 'Migrare terminată. Renderere MobileGL găsite în R2:'
rish -c "run-as '$NEW_PACKAGE' find files -type f \( -iname '*mobilegl*.so' -o -name 'libMobileGLPZ.so' \) -print"
printf '%s\n' 'OK. Deschide R2 și configurează din nou setările OPT-LAB.'
