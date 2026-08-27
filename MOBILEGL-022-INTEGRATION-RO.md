# ZomDroid R2 + MobileGL OPT-LAB V3 022

## Rezultat

Varianta `sideBySideDebug` păstrează pachetul R2 `com.zomdroid.mglpz2` și cheia de
test R2, deci se poate instala ca update peste R2. Versiunea nouă este:

- `versionCode`: `14743`
- `versionName`: `1.4.7v4-optlab-r2-mobilegl022-fix-side`
- etichetă: `ZomDroid OPT LAB MGL022`

## MobileGL inclus

- sursă: `MobileGL-PZ-OPT-LAB-V3-022-TEXTURE-FRONTEND-PACK-DEVICE-2026-08-25`
- fișier în proiect: `app/src/main/jniLibs/arm64-v8a/libMobileGLPZDefault.so`
- SHA-256: `8dc064f0386d01fccab8b94681921fdf9c304ed8995e87e07f1906119728310f`
- dimensiune: `14,876,840` octeți
- arhitectură: ELF64 AArch64
- SONAME: `libMobileGLPZ.so`
- Build ID: `7eff62cc7ca8ab6fcf82a0f017966b6a6d5ee6e5`
- aliniere LOAD: `0x4000` (16 KiB)

La prima pornire, rendererul 022 este copiat ca `libMobileGLPZ.so`. Un renderer
custom ARM64 valid este păstrat. Vechiul default PZF23D4 al R2 este recunoscut
după hash și actualizat automat la 022; un fișier necunoscut sau invalid este
salvat înainte de instalarea defaultului.

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

## Setări inițiale MobileGL 022

- backend: `DirectGLES`
- GLES cerut: `3.2`
- relaxed semantics: activ
- selector control implicit: `019,020,021A,021B,021D`

Câmpul de mediu configurat de utilizator se aplică ulterior și poate înlocui
selectorul implicit. Candidatele 022A-022E rămân pentru validare separată pe
telefon, nu sunt activate toate din prima lansare.

## Verificări efectuate

- build Gradle `:app:assembleSideBySideDebug`: reușit;
- semnătură APK v2: validă;
- certificatul este identic cu R2;
- pachetul și `versionCode` permit update peste R2;
- hashul MobileGL extras din APK este identic cu binarul 022 sursă;
- `libglfw.so` din APK conține ruta `MGLPZ_GATE` și mesajele fail-closed EGL;
- codul DEX conține selectorul control și stările de instalare/upgrade MGL022.

## Test minim pe telefon

1. Instalează APK-ul peste R2 (nu dezinstala R2).
2. Pornește aplicația și selectează rendererul MobileGL.
3. Oprește forțat aplicația, redeschide-o și pornește jocul.
4. După `Creating display`, logul trebuie să continue până la informațiile
   plăcii video și încărcarea jocului.

Buildul și verificările statice confirmă integrarea, însă crearea contextului pe
driverul real poate fi confirmată definitiv doar prin acest test pe dispozitiv.
