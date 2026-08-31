# R15-01 — checkpoint complet pentru continuare

Data: 2026-08-30 UTC

## Implementat

- Profilul General configurează exclusiv Runtime/Display.
- Build 42 are profil separat Safe/Recommended/Aggressive/Custom.
- Experimental este navigator pe categorii: Native ARM64 și modulele Build 42
  care conțin experimente. Toggle-urile nu mai sunt duplicate în producție.
- CP2C este Experimental / World Stream & Chunk și trece prin registry-ul generic.
- Lighting, Pathfinding și PopMan sunt Experimental și implicit OFF; PZClipper
  rămâne Stable și implicit ON.
- CP6.4.1 StateRun revine la metoda PZ pentru `style == null`.
- Agent version 12 / schema 11 este reconstruit automat; scriptul verifică jarul
  și arhiva candidat înainte de publicarea atomică în `jars.tar`.
- Extracția ZIP/TAR are protecție canonicală contra path traversal.
- Sursele și evidence-ul CP6.13.4 / CP6.13.10 sunt incluse integral în
  `PORTING_REFERENCE/`, astfel încât următorul agent nu depinde de chat.

## Fișiere/metode principale

- `app/src/main/java/com/zomdroid/OptLabPreferences.java`
- `app/src/main/java/com/zomdroid/OptLabFeatureRegistry.java`
- `app/src/main/java/com/zomdroid/NativeModulesPreferences.java`
- `app/src/main/java/com/zomdroid/fragments/OptLabFragment.java`
- `app/src/main/java/com/zomdroid/ArchivePathGuard.java`
- `FileUtils.extractArchiveEntry`
- `Main.StateRunTextureAdvice.enter`
- `ProofRuntime`, `build-optlab-agent.sh` și testele/stuburile aferente

## Host QA — PASS

- UI source + javac 17: 6 taburi, 7 module, categorii experimentale;
- independența General / Build42 / Native / Experimental și Safe Mode;
- registry/profile/dependency integrity;
- agent source/runtime, inclusiv fallback StateRun null-style;
- Chunk, CP6.2 și CP6.6 exact-bytecode pe 42.20 și 42.20.3;
- Pathfinding transform pe ambele JAR-uri;
- Native, PopMan, JAssimp și MobileGL source/payload checks;
- ArchivePathGuard regression și `git diff --check`.

JAR-uri:

- 42.20: `e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8`
- 42.20.3: `bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227`

Bundle-uri:

- `jars.tar`: `5e0fefcbf5fb2f8c7fe4a13c2ef931c6dbbd003e8ddd2eff1fcb60115607549c`
- `zomdroid-agent.jar`: `8812d9e1d73ae4bf82909e2988bab5e7abf64fbe0b517cb6c96d2ec1475e3a4e`

## Limitări sincere

- Android SDK nu este disponibil: nu se declară Gradle/APK compile.
- APK-ul nu a fost rulat pe dispozitiv: nu se declară DEVICE PASS.
- CP6.13.4 și CP6.13.10 sunt doar referințe în acest checkpoint, încă neportate.

## Rollback și start pentru următorul agent

- Rollback persistent R15-00 SHA256:
  `53a2b48087b5bcc856dbfa61e7a200681faabcdf41911fff3a47444e61ae6bc8`.
- Citește `R15-CONTINUATION-HANDOFF-RO.md` integral.
- Următorul checkpoint este R15-02: CP6.13.4 direct `buildDrawBuffer`.
- Apoi R15-03: CP6.13.4 FBO inner-loop, strict fără skip/defer.
- CP6.13.10 same-program bind rămâne separat, Experimental și implicit OFF.
