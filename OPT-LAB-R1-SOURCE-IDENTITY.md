# ZomDroid OPT-LAB R1 — identitatea sursei

Status: build static finalizat; validarea pe telefon trebuie făcută separat.

## Identitate

- Pachet Android: `com.zomdroid.mglpz1`
- Versiune: `1.4.7v4-optlab-r1` (`versionCode 14740`)
- Sursă ZomDroid de bază: `c21989411bed8209229fc0207c3dc71a054a5b11`
- Box64: `3ad9fb24a46be373a7456dce27d3066b868a943a`
- GLFW de bază: `2d15d96eabbfd4cb821b9fafb54a162c8e64e13d`, cu modificările OPT-LAB incluse în arhivă
- Patch-ul de integrare P1: `patches/0003-P1-integration.patch`

## Ce conține OPT-LAB

Un singur APK oferă master rollback, profilurile `BASELINE`, `RUNTIME_SAFE`,
`ALL_TEST_ON` și `CUSTOM`, plus mecanisme independente:

1. O1 — quiet runtime;
2. O2 — main-loop pacing cu park/spin limitat și gate de identitate;
3. O3 — logger nativ buffered/bounded;
4. O4 — politica Box64;
5. O5 — ownership Surface prin generation/ACK limitat;
6. O6 — refresh real și frame-rate hint Android;
7. O7 — coadă de input mutex-safe, cu coalescing și filtru analog opționale.

Este inclus și traseul SQLite Android-native, tot ca toggle. Ideile STREAM-CORE
care în materialele de cercetare sunt doar `DESIGN / NOT IMPLEMENTED` nu au fost
pretinse și nu au fost activate în acest build.

Pacing se armează numai pentru:

- `projectzomboid.jar` SHA-256:
  `e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8`
- `zombie/MainThread.class` SHA-256:
  `c7ee1d1d3026185ad49cd80edbf9ddb6f59c0cd7faf2ced4ebbe50f2dada9c0f`
- `zombie/GameWindow.class` SHA-256:
  `34c9927f595ecd524e5ed5524ede1b4789c9be462ea1a04a0f5d1b57dd1d5c95`

La orice abatere, agentul lasă pacing-ul oprit și scrie motivul în log.

## Renderer extern

APK-ul nu conține PZF23D4 și nu conține niciun fișier `libMobileGLPZ.so`.
Ruta `MOBILEGL_PZCOMPAT` încarcă dinamic numai numele stabil
`libMobileGLPZ.so`, din:

`/data/user/0/com.zomdroid.mglpz1/files/dependencies/libs/android-arm64-v8a/libMobileGLPZ.so`

Referința externă stabilă folosită numai pentru audit:

- arhivă PZF23D4 SHA-256:
  `043acdb670f436ebfa70998d7a44af989676212b7f7c459f5ecfe706debcc053`
- ELF validat SHA-256:
  `f5b280fac78f2189daeac41d7d6b456747e1e1970754bcf807135ae33af82e8d`
- Build ID:
  `5a70674ea4cbd78c7b9cba1e048b60c5770e576b`

La pornire, APK-ul calculează și scrie în `[ZD-OPT-LAB]` calea, dimensiunea și
SHA-256 ale rendererului găsit efectiv pe dispozitiv.

## Toolchain

- Gradle 8.13
- Android Gradle Plugin 8.13.2
- compileSdk / targetSdk 35; minSdk 30
- NDK 27.3.13750724 (r27d), Android Clang 18.0.4
- CMake 3.22.1
- host JDK 17; codul aplicației are target Java 11
- ABI livrat: numai `arm64-v8a`

`local.properties` nu este inclus. După extragere, setează `sdk.dir` către un
SDK care conține platforma/build-tools 35, NDK-ul și CMake-ul de mai sus.

Reconstruire:

```bash
./tools/build-optlab-agent.sh
./gradlew clean :app:assembleDebug
```

## Verificări efectuate

- clean `:app:assembleDebug`: PASS;
- `git diff --check` în proiect și GLFW: PASS;
- APK Signature Scheme v2: PASS, certificat Android Debug;
- `zipalign -c -P 16 -v 4`: PASS;
- toate ELF-urile APK au `PT_LOAD p_align = 0x4000`;
- numai ABI `arm64-v8a`;
- agentul Java are manifest valid și clasele `Main` / `PacingRuntime`;
- `libsqlitejdbc.so` există în bundle;
- PZF23D4/MobileGL nu apare ca payload și nu este dependență ELF `NEEDED`.

Android Lint mai raportează 9 erori moștenite în fișiere nemodificate
(`keyboard_styles.xml`, `NewGameInstanceFragment.java` și layout-urile vechi cu
`android:tint`). Problemele introduse de OPT-LAB identificate de Lint au fost
corectate înaintea build-ului final.

