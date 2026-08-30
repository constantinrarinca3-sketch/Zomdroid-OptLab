# R15-00 — baseline R14 verificat și rollback persistent

Data: 2026-08-30 UTC

## Identitate

- Sursă de intrare: `ZomDroid-R14-CP6.6-MODULAR-SOURCE-2026-08-29.tar(1).zst`
- SHA-256 sursă de intrare: `edfbc8a312f169f9e6c45be4ae4b068d672f08a790d4106e394abdd80fedcadf`
- Project Zomboid 42.20: `e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8`
- Project Zomboid 42.20.3: `bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227`

Acest checkpoint nu schimbă logica R14. Fișierul de față și handoff-ul viu sunt
singurele completări, pentru ca fiecare arhivă R15 să fie autonomă.

## Baseline host

PASS:

- agent source/runtime;
- registry integrity (46 feature-uri, 27 proprietăți unice);
- preferințe și Safe Mode;
- UI modular: 6 taburi, 6 module, pagini detaliate, light/dark;
- CP6.2 wiring și transformarea exactă 42.20/42.20.3;
- CP6.6 hotpath exact 42.20/42.20.3;
- chunk optimizations exact 42.20/42.20.3;
- pathfinding exact 42.20/42.20.3 și payload ARM64;
- native modules, PopMan source/ABI, JAssimp direct și MobileGL payload.

Observație baseline: `tools/test-chunk-optimization-real-jars.sh` nu avea bitul
executabil în arhiva primită. Rulat explicit prin `bash`, testul trece pe ambele
JAR-uri. Repararea permisiunii este rezervată următorului checkpoint.

## Limite

- Android/Gradle compile: nerulat în acest mediu.
- Device test al APK-ului integrat: nerulat.
- Dovezile device din arhiva CP1–CP6 aparțin implementărilor PoC/javaagent;
  portarea R15 va necesita o validare nouă pe dispozitiv.

## Rollback

Rollback byte-identic: arhiva R14 și SHA-256 de mai sus.

