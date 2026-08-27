# ZomDroid OPT-LAB R2 — build și test

## Build recomandat

Varianta principală este `sideBySide`, package `com.zomdroid.mglpz2`. Se instalează lângă R1 și
lângă aplicația MobilePZ/ZomDroid originală.

```bash
./gradlew :app:assembleSideBySideDebug
```

Cheia inclusă este exclusiv pentru builduri locale de test; vezi
`signing/README-R2-TEST-KEY.md`.

## Ce repară R2

R1 nu mai instala traseul nativ ARM64 Lighting/PZClipper prezent în AB3 și dezactiva biblioteca
Lighting ARM64 a instanței. R2 restaurează înainte de fiecare pornire:

- shimul Lighting AB1, compilat din sursa exactă AB1;
- engine-ul ARM64 legacy Lighting exact, verificat prin mărime, SHA-256 și ELF AArch64;
- PZClipper ARM64 exact AB3, verificat identic;
- rendererul stabil PZF23D4 ca fallback implicit pentru o instalare nouă; un renderer ARM64
  injectat de utilizator este păstrat și nu este suprascris;
- copiere atomică și păstrarea unei biblioteci străine înainte de înlocuire;
- oprire clară a lansării MobileGL dacă traseul nativ nu a putut fi instalat.

Master `OFF` din OPT-LAB este acum rollback real și persistent: toate optimizările experimentale
revin la baseline, inclusiv logul MobileGL. Reparația ARM64 este infrastructură obligatorie pentru
renderer, nu o optimizare experimentală, deci rămâne activă și cu master `OFF`.

## Test minim pe dispozitiv

1. Instalează APK-ul R2 și pornește aplicația o dată.
2. Dacă vrei să păstrezi jocul și rendererul din R1, rulează
   `bash tools/MIGRATE-R1-TO-R2-SIDEBYSIDE.sh --yes` din Termux cu Shizuku/Rish activ.
3. Selectează `MOBILEGL_PZCOMPAT`, pune OPT-LAB pe `BASELINE / ALL OFF`, apoi pornește jocul.
4. În log trebuie să apară `ZD-ARM64-NATIVE`, `ZD-LIGHT-ARM64`,
   `ZD-PZCLIPPER-ARM64`, `ZD-LIGHT-SHIM` și `MGLPZ_GATE`.

Validarea statică nu înlocuiește testul de lansare pe dispozitiv. Nu promova R2 ca stabil până
când meniul, încărcarea lumii și ieșirea normală au trecut pe dispozitivul țintă.
