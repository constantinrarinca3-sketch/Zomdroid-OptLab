# ZomDroid R15 — handoff viu pentru continuare

Actualizat: 2026-08-30 UTC — R15-07 SETTINGS ARMING FIX

## R15-07 — corecție critică după device feedback

- `R15-07-01` repară persistarea/armarea tuturor setărilor, elimină proprietățile JVM
  OPT-LAB vechi și impune un singur snapshot 0/1 înainte de JNI.
- Callbackurile programatice ale profilelor nu mai pot reaplica Recommended după un toggle OFF.
- Back revine la ruta exactă de origine, inclusiv Build 42 module → Experimental → modulul inițial.
- Managerul preset este redus la create/apply/update/delete și are recuperare fail-closed pentru
  catalog corupt fără modificarea setărilor active.
- Export logs include `opt-proof.log`.
- Identitatea de build finală este versionCode `14756`, R15-07.
- Host QA și JAR-urile exacte 42.20/42.20.3: PASS. DEVICE PASS: NU.
- Handoff final: `CHECKPOINTS/R15-07-FINAL-HANDOFF-RO.md`.
- Rollback implementare: commit local `5f472cceeeaa1a976443f62b1f8b8ba7c8df0feb`.

## Gata

- R14 autoritativ identificat și extras.
- SHA-256 pentru sursă și cele două JAR-uri PZ înregistrate.
- Baseline host complet: PASS, cu limitările documentate în R15-00.
- Rollback-ul R14 rămâne byte-identic și independent.
- Profilul General controlează numai Runtime/Display; Build 42 are profil propriu.
- Experimental este organizat pe Native și pe module Build 42, fără toggle-uri
  duplicate în paginile de producție.
- Lighting, Pathfinding și PopMan sunt izolate în Experimental și implicit OFF;
  PZClipper rămâne Stable.
- CP6.4.1 StateRun cedează metodei vanilla când `style == null`.
- Extracția ZIP/TAR respinge path traversal înainte de orice scriere.
- Sursele/evidence CP6.13.4 și CP6.13.10 sunt incluse în `PORTING_REFERENCE/`.
- CP6.13.4 `buildDrawBuffer` rulează prin fastpath direct scalar, fără RingPack
  bulk, alocări per draw, skip sau defer.
- Fallback-ul R15-02 se decide înainte de efecte secundare; cazurile de rollover,
  lipsă de capacitate și granița de creștere StateRun rămân vanilla.
- La R15-02 agentul integrat era `version=13 schema=12`; schema de preferințe
  rămânea 11.
- R15-02 a trecut testele host, paritatea randomizată și validarea exactă pe
  42.20/42.20.3. APK/device rămân netestate.
- CP6.13.4 FBO inner-loop este portat separat, fără skip/defer/budget; reutilizează
  numai referința `squareFlags` și lumina deja citită pentru același player.
- FBO inner-loop este Experimental, implicit OFF în toate profilurile și a trecut
  paritatea host plus transformarea exactă pe 42.20 și 42.20.3.
- CP6.13.10 same-program bind este portat ca mecanism separat și folosește
  `ShaderHelper.currentlyBound` drept stare autoritativă, fără shadow state paralel.
- Same-program bind sare apelul numai pentru un program pozitiv identic, când
  verificarea debug `boundShader` există și este OFF. Programul 0/negativ,
  schimbarea programului, debug ON, forma incompatibilă și erorile rămân vanilla.
- Same-program bind este Experimental, implicit OFF în toate profilurile și a
  trecut testul semantic plus transformarea exactă pe 42.20 și 42.20.3.
- Agentul integrat este `version=15 schema=14`; la R15-05 schema de preferințe
  era `11`, iar seria R15-06 o ridică la `12` pentru preseturi.
- R15-05 a trecut runnerul final unic: integritate bundle, registry-agent 1:1,
  agent/UI/native/JAssimp/MobileGL și toate transformările exacte pe ambele JAR-uri.
- Gate-urile native-only au regresii separate: Pathfinding-only și PopMan-only
  instalează agentul unificat chiar dacă toate funcțiile Java OPT-LAB sunt OFF.
- Auditul final nu a justificat un refactor runtime suplimentar. R15-05 nu adaugă
  o optimizare nouă și păstrează byte-identic agentul validat în R15-04.
- R15-06-01 expune fiecare optimizare Build 42 `INTERNAL` ca toggle individual
  persistent. Calea generică repară dependențele și marchează profilul `Custom`;
  Safe Mode continuă să păstreze starea.
- R15-06-01 nu schimbă agentul, bundle-ul, profilele implicite, maturitatea sau
  fallback-urile mecanismelor.
- R15-06-02 adaugă un catalog versionat pentru preseturi custom Build 42
  multiple, cu snapshot complet prin ID stabil, checksum, dependency repair și
  fail-closed fără suprascriere pe schema necunoscută.
- Motorul R15-06-02 implementează create/apply/update/rename/duplicate/delete;
  managerul vizual este atașat separat în R15-06-03.
- Schema preferințelor este acum `12`, iar catalogul are schema proprie `1`.
- R15-06-03 adaugă un singur card pe pagina Build 42 și mută toate cele șase
  acțiuni într-o subpagină separată, fără tab nou și fără nested dialog.
- UI-ul afișează distinct `Custom · <nume>`, `Modified · based on <nume>`,
  preset selectat dar neaplicat, Safe Mode și catalog fail-closed.
- Ștergerea necesită două apăsări. Toate controalele folosesc tema existentă,
  fără culori hardcodate și fără diferență de layout între light/dark.
- Regresia `world black` NU este declarată rezolvată; toggle-urile Internal și
  presetul persistent permit izolarea mecanismului pe dispozitiv.

## Seria R15-06 autorizată

| Checkpoint | Scop | Stare |
| --- | --- | --- |
| R15-06-01 | Toggle-uri pentru optimizările Internal | **DONE SOURCE/HOST** |
| R15-06-02 | Motor versionat pentru preseturi custom multiple | **DONE SOURCE/HOST** |
| R15-06-03 | Manager UI: create/apply/update/rename/duplicate/delete | **DONE SOURCE/HOST** |
| R15-06-04 | Localizare integrală RO/EN | **AMÂNATĂ explicit de utilizator; neinclusă** |

După `R15-06-03`, utilizatorul a autorizat închiderea sursei fără pasul de
localizare. Interfața rămâne în forma curentă pentru compilare și device test.

## Plan autorizat

1. Refactorizare controlată a codului existent și corectarea problemelor de
   paritate/metadata descoperite, fără a slăbi fallback-ul.
2. Categorii OPT-LAB clare, fără listă unică aglomerată.
3. Port direct CP6.13.4 `buildDrawBuffer` fastpath.
4. Port direct CP6.13.4 FBO inner-loop, strict fără skip/defer.
5. CP6.13.10 same-program bind separat, Experimental și implicit OFF.
6. Host/exact-jar QA, arhivă finală, diff, SHA-256 și plan CP6.14+.

Toate cele șase etape sunt **DONE la nivel SOURCE/HOST**. APK compile și device
test rămân explicit nerealizate.

## Matrice autoritativă de portare

| Prioritate | Optimizare | Decizie |
| --- | --- | --- |
| 1 | CP6.13.4 `buildDrawBuffer` direct fastpath | **DONE în R15-02; DEVICE TEST NO** |
| 2 | CP6.13.4 FBO inner-loop reuse | **DONE în R15-03; DEVICE TEST NO** |
| 3 | CP6.13.10 same-program bind | **DONE în R15-04; DEVICE TEST NO** |
| 4 | CP6.7 IsoZombie cast specialization | Doar experimental; beneficiul trebuie demonstrat |
| — | CP6.7 RingBuffer fastpaths | Nu separat; se suprapun cu CP6.13.4 |

CP6.13.4 este baseline-ul târziu ales: proof-of-concept-ul din arhiva master a
redus maximele observate aproximativ `318 -> 149 ms` pentru `buildDrawBuffer`
și `215 -> 89 ms` pentru `RingBuffer.render`. Direct packing are paritate pe
10.000 de secvențe randomizate / 243.790 de comenzi. Implementarea integrată
R15-02 are separat paritate host pe 10.000 de secvențe / 243.415 comenzi și
validare bytecode exactă pe ambele JAR-uri. Nici rezultatele PoC, nici testele
host R15-02 nu reprezintă DEVICE PASS pentru APK.

R14 păstrează CP6.1/6.2 chunkDepth, CP6.3 shader-ID/MVP, Texture.bind,
GameProfiler/Render Style idle și empty `modelDrawCounts.clear`. CP6.4.1
StateRun rămâne în producție după corectarea cazului `style == null` în R15-01:
Advice-ul execută metoda PZ neatinsă pentru acel caz.

Nu se portează RingPack bulk, CP6.10 prestage, CP6.13.2 FBO defer/skip,
CP6.13.5 coalesce/state-elide/hotOps, pachetele CP6.13.6-7 în bloc,
CP6.13.8/9/11/12/13 diagnostic, profilerul CP1-CP5, pathfinding wake
suppression, scheduler notify suppression, `chunkLightingDone` cache sau
`CHUNK_VEHICLE_INDEX` în forma curentă.

Sursele și evidence-ul necesare portării sunt păstrate în
`PORTING_REFERENCE/CP6.13.4/` și `PORTING_REFERENCE/CP6.13.10/` începând cu
checkpointul R15-01. Ele sunt referință, nu cod lansabil implicit.

## Reguli pentru următorul agent

- Fiecare checkpoint trebuie să conțină acest handoff actualizat și un fișier
  propriu în `CHECKPOINTS/`.
- Fiecare checkpoint se livrează ca arhivă completă de sursă, nu doar patch.
- Nu se declară DEVICE PASS fără rularea APK-ului integrat pe dispozitiv.
- 42.20 și 42.20.3 se validează separat; B42 necunoscut rămâne OFF/PROBE.
- Nicio optimizare nu poate sări sau amâna definitiv pregătirea unui chunk FBO dirty.

## Identitatea curentă

- `zomdroid-agent.jar`:
  `22fedc09273fd7e70887e64451b9e8b53305432962dca932b24afc7c447926b3`
- `app/src/main/assets/bundles/jars.tar`:
  `6c4e67b13f6926292bf1fbe271833d6365cfeb0f89f27c62bdd00ae2abde66a5`
- preferences schema: `12`; custom preset schema: `1`
- rollback persistent imediat R15-06-02:
  `9363643e38f495cd2829073f7aeb5d70a99e1ecebf450735b9c52003ea7f4a25`
- checkpoint curent: `CHECKPOINTS/R15-06-03-PRESET-MANAGER-UI-RO.md`
- handoff final de compilare:
  `CHECKPOINTS/R15-06-FINAL-COMPILE-HANDOFF-RO.md`
- checklist validare: `CHECKPOINTS/R15-06-03-USER-VALIDATION-CHECKLIST-RO.txt`
- patch cumulativ de cod R14 → R15:
  `72d1a34d45fc851ccd4ad2dae6b807a43a45bd162e41ab8b1a51a6985661c942`
- detalii QA: `CHECKPOINTS/R15-05-FINAL-QA-RO.md`
- device checklist: `CHECKPOINTS/R15-05-DEVICE-TEST-CHECKLIST-RO.txt`

## Următorul pas — compilare și device test

Compilează sursa conform `CHECKPOINTS/R15-06-FINAL-COMPILE-HANDOFF-RO.md`, apoi
rulează `CHECKPOINTS/R15-06-03-USER-VALIDATION-CHECKLIST-RO.txt` pe APK/device.
Localizarea RO/EN (`R15-06-04`) rămâne amânată până la o cerere explicită.
Nu declara DEVICE PASS și nu adăuga optimizări noi înaintea verdictului real.
