# ZomDroid R6 QA + MobileGL PZCompat V1.2 Present Fastpath ThinLTO

## Rezultat

Varianta `sideBySideDebug` păstrează pachetul R2 `com.zomdroid.mglpz2` și cheia de
test R2, deci se poate instala ca update peste R2. Versiunea nouă este:

- `versionCode`: `14747`
- `versionName`: `1.4.7v4-optlab-r6-cp1-qa-mgl12-side`
- etichetă: `ZomDroid OPT LAB R6 QA`

## MobileGL inclus

- sursă: `MobileGL-PZCompat-V1.2-PRESENT-FASTPATH-THINLTO-MGLPZ2-RISH-INJECT-ROLLBACK-2026-08-28.zip`
- fișier în proiect: `app/src/main/jniLibs/arm64-v8a/libMobileGLPZDefault.so`
- SHA-256: `f8c2851d9c3cbadc40c73f09c5ec9430a53a0e815cee3e2ec1392dcb4f9fa10a`
- dimensiune: `14,358,432` octeți
- arhitectură: ELF64 AArch64
- SONAME: `libMobileGLPZ.so`
- Build ID: `da5508480c21ecc5a13d60539cd53d90860c46dd`
- aliniere LOAD: `0x4000` (16 KiB)
- Android API: 26
- NDK: r27d (`27.3.13750724`)
- build: Release + ThinLTO, PZCompat, PZF23D4, BUG002 și BUG003

La prima pornire, rendererul este copiat atomic ca `libMobileGLPZ.so`. Un renderer
custom ARM64 valid este păstrat. Atât defaultul 022 anterior (`8dc064f0…`), cât și
vechiul PZF23D4 (`f5b280fa…`) sunt recunoscute după hash și actualizate automat la
V1.2. Un fișier necunoscut sau invalid este salvat distinct înainte de instalare.

## Corecția blocajului `Creating display`

R2 păstra selecția MobileGL, dar pierduse partea esențială din ruta EGL care
funcționa în P1. Loaderul putea continua cu EGL-ul sistemului dacă încărcarea
explicită eșua și calcula prefixul dintr-un index nevalid pentru ruta explicită.

Corecția din `egl_context.c`:

- urmărește numele bibliotecii încărcate prin `loadedName`;
- oprește lansarea cu eroare clară dacă biblioteca EGL cerută nu se încarcă;
- oprește lansarea dacă biblioteca client GLES cerută nu se încarcă;
- nu mai permite fallback silențios la EGL/GLES de sistem pentru MobileGL.

În `zomdroid_window.c`, MobileGL deține explicit ambele rute:

- EGL: `libMobileGLPZ.so`
- GLES/GL: `libMobileGLPZ.so`

Aceasta restaurează modelul de ownership din P1 și evită contextul mixt
MobileGL + system EGL.

## Setări inițiale MobileGL V1.2

- backend: `DirectGLES`
- GLES cerut: `3.2`
- relaxed semantics: activ
- present fastpath: `MOBILEGL_PZ_PRESENT_FASTPATH=1`
- selector recomandat implicit: `MOBILEGL_PZ_OPT_SET=002,003`
- proof, selector și cache-uri: căi absolute în sandboxul pachetului curent

Câmpul de mediu configurat de utilizator se aplică ulterior și poate înlocui
selectorul implicit. Căile explicite `MOBILEGL_PZ_FILES_DIR`, `...OPT_FILE`,
`...PROOF_FILE` și directoarele cache elimină dependența runtime de fallback-ul
hardcodat `com.zomdroid.mglpz2`, inclusiv pentru flavorul `replaceR1`.

## Verificări efectuate

- SHA-256 din ZIP și `SHA256SUMS.txt`: PASS;
- ELF64/AArch64, SONAME, Build ID, 3 segmente LOAD de 16 KiB: PASS;
- RELRO, BIND_NOW, stack neexecutabil și dependențe Android exacte: PASS;
- toate cele 8.607 exporturi funcționale ale defaultului anterior sunt păstrate;
- 3.152 exporturi funcționale suplimentare, niciun export vechi lipsă;
- instalare, post-copy hash, detectare present, custom-preserve și invalid-recovery: PASS host;
- gate-ul payloadului este rulat automat în toate workflow-urile;
- buildul Android R6: NEEXECUTAT aici, deoarece Gradle 8.13 nu este disponibil local.

## Test minim pe telefon

1. Instalează APK-ul peste R2 (nu dezinstala R2).
2. Pornește aplicația și selectează rendererul MobileGL.
3. Oprește forțat aplicația, redeschide-o și pornește jocul.
4. După `Creating display`, logul trebuie să continue până la informațiile
   plăcii video și încărcarea jocului.

Verificările statice și host confirmă integrarea sursei. Crearea contextului și
present fastpath pe driverul real pot fi confirmate definitiv doar pe dispozitiv.
