# R15-05 — Final QA și sursă finală

Data: 2026-08-30 UTC

Status: **DONE (SOURCE/HOST)**

APK COMPILE: **NOT RUN**

DEVICE TEST: **NO**

## Verdict

R15 este finalizat la nivel de sursă și validare host. Agentul unificat,
registry-ul, UI-ul modular și fastpath-urile integrate trec împreună pe
Project Zomboid 42.20 și 42.20.3.

Auditul final nu a găsit o problemă concretă care să justifice un refactor
runtime suplimentar. R15-05 nu schimbă fastpath-urile validate în R15-02/03/04
și nu introduce o optimizare nouă. Polish-ul adăugat este exclusiv de siguranță:

- test separat pentru gate-ul `PopMan-only`;
- testul existent `Pathfinding-only` rămâne activ;
- verificare automată 1:1 a proprietăților OPT-LAB din registry și agent;
- runner unic și reproductibil `tools/test-r15-final-qa.sh`.

## Ce conține R15

| Checkpoint | Rezultat | Device |
| --- | --- | --- |
| R15-01 | UI modular, registry/profiluri, StateRun null fallback, archive guard | NO |
| R15-02 | CP6.13.4 `buildDrawBuffer` direct scalar | NO |
| R15-03 | CP6.13.4 FBO inner-loop reuse, fără skip/defer | NO |
| R15-04 | CP6.13.10 same-program bind autoritativ | NO |
| R15-05 | QA final, property coverage, native-only gates, patch și manifest | NO |

## Rezultate finale

- scripturi shell: **PASS**
- JRE 21, JRE 25 și libs XZ: **PASS integrity**
- agent build determinist și bundle source-matched: **PASS**
- agent marker: `version=15 schema=14`
- proof schema: `14`
- preferences schema: `11`
- registry: **48 funcții**, 12 Render vizibile, 5 arhivate
- ID / preference key / agent property duplicate: **0**
- registry ↔ agent property coverage: **1:1 PASS**
- UI: **PASS**, 6 taburi, 7 module, pagini detaliate, light/dark
- Pathfinding-only premain gate: **PASS**, un transformer
- PopMan-only premain gate: **PASS**, un transformer
- CP6.13.4 build direct parity: **PASS**, 10.000 secvențe / 243.415 comenzi
- CP6.13.4 FBO parity: **PASS**, 2.000 secvențe / 220.416 pătrate,
  40.412 citiri redundante eliminate, skip=0, defer=0
- CP6.13.10 semantic guard: **PASS**, program 0/debug/error rămân vanilla
- CP6.2 exact 42.20 / 42.20.3: **PASS**
- CP6.6 exact 42.20 / 42.20.3: **PASS**
- CP6.13.4 build direct exact 42.20 / 42.20.3: **PASS**
- CP6.13.4 FBO exact 42.20 / 42.20.3: **PASS**
- CP6.13.10 same-program exact 42.20 / 42.20.3: **PASS**
- Chunk exact 42.20 / 42.20.3: **PASS**
- Pathfinding exact 42.20 / 42.20.3: **PASS**
- native payloads, PopMan source/ABI, JAssimp direct, MobileGL și archive guard:
  **PASS**

Comanda autoritativă pentru repetarea QA:

```bash
bash tools/test-r15-final-qa.sh /path/projectzomboid-42.20.jar \
  /path/projectzomboid-42.20.3.jar
```

## Identitate

- Project Zomboid 42.20:
  `e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8`
- Project Zomboid 42.20.3:
  `bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227`
- `zomdroid-agent.jar`:
  `22fedc09273fd7e70887e64451b9e8b53305432962dca932b24afc7c447926b3`
- `app/src/main/assets/bundles/jars.tar`:
  `6c4e67b13f6926292bf1fbe271833d6365cfeb0f89f27c62bdd00ae2abde66a5`
- `jre21.tar.xz`:
  `2861c337a21f4145c21e1ffd00a3fd334efba10786d112772cb2bff2ed850a14`
- `jre25.tar.xz`:
  `c1240538db496c8dfbc95cd5c6bbd9e649e15702128ed169c690d036f8618ca0`
- `libs.tar.xz`:
  `b5c55bde92492f4585d76d5201398e7e7441d061445201e9c5b0edc6bcd8b295`
- patch cumulativ de cod R14 → R15:
  `72d1a34d45fc851ccd4ad2dae6b807a43a45bd162e41ab8b1a51a6985661c942`

## Limitări sincere

- Android SDK/Gradle APK compile nu a fost rulat în acest mediu.
- APK-ul integrat nu a fost testat pe dispozitiv.
- PASS host/exact-JAR dovedește compatibilitatea transformării, nu fluiditatea,
  temperatura, stabilitatea long-run sau absența stutterului pe dispozitiv.
- FBO inner-loop și same-program bind rămân Experimental și OFF în toate
  profilurile până la validare device separată.

## Continuare

Sursa autoritativă pentru CP6.14+ este arhiva completă R15-05. Înainte de o
optimizare nouă se execută checklist-ul R15-05 pe dispozitiv și se păstrează
arhiva R15-04 ca rollback imediat.
