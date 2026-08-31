# ZomDroid R12 — CP6.2 direct integration — host QA și handoff CP6.3+

Data: 2026-08-29 UTC  
Ramură: `r12-cp6.2-direct-integration`  
Checkpoint funcțional final: `c9d1ecd`  
Baseline R11 / rollback curat: `925bf720334079bb3ad462ff27173e99ec9497a6`

## Verdict

Subsetul demonstrat CP6.2 a fost portat direct în agentul unic ZomDroid și cablat în APK. Nu a fost introdus un al doilea javaagent. CP4.1 rămâne complet, CP6.0 `rthreadProfileFast` nu a fost reintrodus, iar RingBuffer bulk packing nu este integrat și nu este activ implicit.

Host QA este PASS pentru sursa agentului, semantică, cablarea APK/UI și transformarea bytecode exactă pe Project Zomboid 42.20 și 42.20.3. Android Gradle și testul real pe dispozitiv nu au fost executate și nu sunt declarate PASS.

## Ce este integrat

1. **CP6.1 ChunkDepth upload-elision**
   - elimină apelurile redundante `glUniform1f`;
   - compară valoarea prin `Float.floatToRawIntBits` și identitatea obiectului `Uniform`;
   - nu comite cache-ul înainte ca apelul GL să reușească.

2. **CP6.2 ChunkDepth lookup-elision**
   - reutilizează `getUniform("chunkDepth")` pe aceeași identitate de program și aceeași epocă de compilare;
   - invalidează cache-ul numai după terminarea cu succes a `DefaultShader.onCompileSuccess`;
   - lookup OFF păstrează frecvența vanilla și permite separat upload-elision.

3. **CP6.2 RingBuffer empty-clear-elision**
   - în `RingBuffer.render()` este rescris numai apelul final exact `Model.modelDrawCounts.clear()`;
   - dacă mapa este deja goală, apelul este omis;
   - dacă nu este goală, `clear()` se execută exact o dată;
   - restul metodei originale Project Zomboid nu este duplicat.

## Corecție de siguranță găsită în QA

Invalidarea după compilare este necesară și când este activ numai CP6.1 upload-elision. Fără ea, o implementare PZ care reutilizează obiectul `Uniform`, dar schimbă locația după recompile, ar putea produce un skip incorect. Din acest motiv hook-ul de compilare este cerut pentru ambele mecanisme ChunkDepth.

## Integrarea APK și UI

- Preferințe schema 7, trei mecanisme Build 42 independente și implicit OFF.
- `GameLauncher` transmite exact cele trei proprietăți:
  - `zomdroid.optlab.render.chunk.depth.upload`
  - `zomdroid.optlab.render.chunk.depth.lookup`
  - `zomdroid.optlab.render.ring.empty.clear`
- Activarea lookup activează atomic upload-ul necesar; oprirea upload oprește lookup.
- Master switch-ul general nu șterge și nu dezactivează setările Build 42/Render/Native.
- UI separat: `Build 42 -> Render Hotpaths`, cu tema light/dark moștenită și fără culori hardcodate.
- Presetul demonstrat activează numai cele trei mecanisme validate.
- UI declară explicit că RingBuffer bulk packing nu este inclus din lipsa unui câștig CPU real demonstrat pe dispozitiv.

## Compatibilitate

| Țintă | Statut | Bază |
|---|---|---|
| Project Zomboid 42.20 | VERIFIED | hash-uri exacte + transformare offline pe JAR-ul real |
| Project Zomboid 42.20.3 | VERIFIED | hash-uri exacte + transformare offline pe JAR-ul real |
| Alte versiuni Build 42 | PROBE | verificare structurală per mecanism; COMPATIBLE numai dacă proba trece |
| Țintă incompatibilă | UNSUPPORTED per feature | fallback la bytecode-ul original; celelalte mecanisme pot continua |

Nu există o promisiune oarbă de compatibilitate pentru toate versiunile viitoare Build 42. Politica este hash exact pentru versiunile cunoscute și probă structurală, fail-open, pentru celelalte.

Hash-urile claselor critice identice în 42.20 și 42.20.3:

- `DefaultShader.class`: `2a4231d4059687d285a6d460eff78643f18a7d596186ba3f6fa64b52e0e04273`
- `ShaderProgram.class`: `062a78360dab7245283099312d88d09c216da5ae5c0b4deb5edd3f4a08afb621`
- `ShaderProgram$Uniform.class`: `f5f17a0bfe4510a7b9964410facaadc083ff14c0316796e8ec277061aa8595c9`
- `SpriteRenderer$RingBuffer.class`: `c50a46274331398ba2168a33f9b87d2d164301cc239c238830f6bd8588dfc8c3`
- `Model.class`: `3dd5e7b618cecf3c27e216a386e07937b1be694a7fc083b1bc2f7c993cbee3d9`

## Rezultate host

- 4096 apeluri ChunkDepth identice: 1 lookup, 1 upload.
- Schimbare de valoare: 0 lookup-uri suplimentare, exact 1 upload nou.
- Recompile reușit: exact 1 lookup nou și upload forțat.
- Lookup OFF: lookup vanilla la fiecare apel, upload redundant eliminat.
- Lookup OFF + recompile: lookup/upload forțat în siguranță.
- Eroare GL simulată: cache necomis, apel original, mecanism fail-open.
- `modelDrawCounts` gol: `clear()` omis; ne-gol: exact un `clear()`.
- Pentru fiecare JAR real: două hook-uri `DefaultShader`, o singură rescriere tail în `RingBuffer.render`, versiunea classfile păstrată.

Lista completă a testelor și starea lor este în `CP6.2-FINAL-HOST-TEST-RESULTS.txt`.

## Bundle livrat

- Agent marker: `version=10 / schema=9`
- `zomdroid-agent.jar`: `045461369c1442ba7f422a71b927a1956457ee0779bf9cac6e22b70330cff6d8`
- `app/src/main/assets/bundles/jars.tar`: `bbbc0768409a3960386a4dd7ce8863435ccc839d460da1250c965702bb78400c`

Bundle-ul agentului este reconstruit automat de `tools/build-optlab-agent.sh`; patch-ul cumulativ exclude binarul `jars.tar`, care se reproduce prin acest script.

`CP6.2-FINAL-SOURCE-MANIFEST.sha256` acoperă toate fișierele livrate din arborele sursă, cu excepția propriului fișier și a metadatelor `.git`.

## Limitări declarate

- **Android Gradle compile: NOT RUN.** Distribuția Gradle 8.13 nu exista în cache, iar mediul a blocat descărcarea de la `services.gradle.org` cu `Network is unreachable`.
- **DEVICE PASS: NOT RUN / NOT CLAIMED.** Este necesar un APK construit într-un mediu Gradle funcțional și un test real pe dispozitiv.
- RingBuffer bulk packing rămâne cercetare; host parity PASS din handoff nu dovedește reducerea timpului CPU.

## Proveniența pachetului CP6.2

- Pachetul primit: `69d81ac09ba031928c5c090ce469c648c6e3245588856dfe785df0dc562c2bd1`
- PZ 42.20 primit separat: `e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8`
- PZ 42.20.3 primit separat: `bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227`

Neconcordanță păstrată în diagnostic, nu ascunsă:

- `APK-INTEGRATION-HANDOFF.md` real: `1979b4a77b9ebbadf87a153628058c69762bbc37acb504332be4330891d68fe0`
- hash așteptat de manifest: `f2476f6ed39493f712b219c31dd68b7004896f1e2d453e430481cab41738818f`
- `SHA256SUMS.txt`: `595189f6b2154801d2c4ac5e19f19847714ed0595eeae13f7bdcb18029ab6635`
- `FACTS-AND-EVIDENCE.txt`: `82a1aada2d47bd21f2d48967d6214d11597313ce2c81f89fba7c1d2a3f7537f9`
- rezultat device din pachet: `0ae951196154fc274a4c4f4717126974acb4c421bc437de9d95f671686c97179`
- manifestul pachetului menționează două fișiere care nu au fost livrate în ZIP: `reference/projectzomboid-42.20.3.jar` și `baseline/MGLPZ-Performance-CP6.1-V2-HITRATE.jar`.

JAR-urile Project Zomboid furnizate separat au fost folosite numai pentru validare și nu sunt redistribuite în arhiva sursei.

## Rollback

- Rollback total la R11: `git switch --detach 925bf720334079bb3ad462ff27173e99ec9497a6`
- Rollback înainte de agent/runtime CP6.2: `9969eb5`
- Agent/runtime fără cablarea APK/UI finală: `b5d2364`
- Integrarea funcțională și QA host: `c9d1ecd`
- În APK, rollback imediat fără rebuild: presetul `ALL OFF` din `Render Hotpaths`; fallback-ul per feature rămâne activ la incompatibilitate.

## Handoff CP6.3+

Ordinea recomandată de cercetare:

1. `RingBuffer.render / drawElements`
2. `ShaderHelper.setModelViewProjection`
3. `ShaderPrograms.getProgramByID`
4. `VertexBufferObject.setModelViewProjection`
5. `buildDrawBuffer`

Reguli de continuare:

- păstrează cele trei rute validate în acest checkpoint drept baseline separat;
- măsoară timp CPU real pe dispozitiv, nu doar hit count sau host parity;
- RingBuffer bulk packing poate exista numai experimental/toggleable până la câștig măsurabil;
- fiecare mecanism nou trebuie să aibă toggle independent, probă structurală, fallback fail-open și rollback;
- păstrează compatibilitatea Build 42 per feature și refuză transformarea la formă ambiguă;
- rulează host QA și transformări pe JAR-uri exacte înainte de device test;
- nu declara DEVICE PASS fără rulare reală și log/proveniență păstrate.
