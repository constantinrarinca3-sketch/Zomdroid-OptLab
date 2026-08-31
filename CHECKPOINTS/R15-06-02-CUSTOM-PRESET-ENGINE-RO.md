# R15-06-02 — motor versionat pentru preseturi custom Build 42

Data: 2026-08-30 UTC

Status: **DONE (SOURCE/HOST)**

APK COMPILE: **NOT RUN**

DEVICE TEST: **NO**

## Rezultat

Sursa conține acum un motor independent și testabil pentru mai multe preseturi
Build 42 denumite de utilizator. Checkpointul nu adaugă încă suprafața vizuală;
managerul UI este izolat în `R15-06-03`.

## Contractul presetului

- snapshot complet pentru toate funcțiile vizibile cu
  `Category.BUILD42`, inclusiv Stable, Internal și Experimental Build 42;
- identificare exclusiv prin `Feature.id` stabil, niciodată prin ordinalul enum;
- General, Native și categoria independentă `Category.EXPERIMENTAL` nu sunt
  capturate și nu sunt rescrise;
- funcțiile adăugate într-un registry viitor, dar absente dintr-un preset vechi,
  sunt aplicate conservator ca OFF;
- dependențele sunt închise/reparate înainte de aplicare;
- create și update salvează starea configurată brută, nu starea temporar mascată
  de Safe Mode;
- aplicarea este un singur commit `SharedPreferences` pentru starea Build 42,
  identitatea activă și profilul `Custom`.

## Persistență și siguranță

- schema principală de preferințe devine `12`;
- catalogul are schema proprie `OptLabCustomPresetStore.SCHEMA = 1`;
- formatul are marker de schemă, număr de înregistrări și checksum CRC32;
- numele sunt codate UTF-8 și sunt unice case-insensitive;
- lungime nume: 1–48 code points;
- limită: 32 de preseturi;
- un catalog corupt sau cu versiune necunoscută este fail-closed și read-only;
  datele necunoscute nu sunt suprascrise automat;
- built-in Safe/Recommended/Aggressive golește numai identitatea custom activă,
  nu șterge preseturi salvate;
- `ALL OFF` păstrează presetul de bază și starea devine `Modified`;
- diagnosticul machine-readable păstrează schema, numărul, ID-ul activ și
  indicatorul modified, fără a serializa numele liber în linia de lansare.

## Operații implementate în motor

- create;
- apply;
- update din configurația curentă;
- rename fără schimbarea ID-ului;
- duplicate cu ID nou, fără schimbarea presetului activ;
- delete, inclusiv curățarea identității dacă este șters presetul activ.

## Fișiere și metode

- `app/src/main/java/com/zomdroid/OptLabCustomPresetStore.java`
  - catalog, codec, checksum, normalizare, snapshot, operațiile CRUD/apply.
- `app/src/main/java/com/zomdroid/OptLabPreferences.java`
  - integrare și API public pentru managerul UI;
  - `setBuild42LabProfile(...)` separă corect profilele built-in de custom;
  - `machineReadable()` include proveniența presetului.
- `tools/OptLabCustomPresetStoreUnit.java`
  - lifecycle complet, izolare, forward safety, dependency repair și fail-closed.
- `tools/test-optlab-custom-presets.sh`
  - compilare Java 11 și execuție host izolată.
- runner-ele existente au fost actualizate pentru schema 12 și noul test.

## Validare

- custom preset lifecycle complet: **PASS**;
- snapshot prin ID stabil: **PASS**;
- scope Build42-only: **PASS**;
- dependency repair: **PASS**;
- unknown/corrupt catalog fail-closed: **PASS**;
- compilare izolată Java 11: **PASS**;
- UI/source/preferences/native/registry: **PASS**;
- final R15 exact 42.20 + 42.20.3: **PASS**;
- Gradle APK: **NOT RUN** — wrapperul nu era disponibil local și mediul nu a
  putut descărca distribuția;
- device: **NO**.

## Rollback

Checkpointul anterior R15-06-01:

- commit: `fb3c3d4fb6e4803ddc7a3a52e11213f57f4eda87`;
- arhivă:
  `ZomDroid-R15-06-01-INTERNAL-TOGGLES-FULL-SOURCE-2026-08-30.tar.zst`;
- SHA-256:
  `f982a9979448d70c972521eb2d302863ae478df4f9a75a64e9681eb1f072ba61`.

## Următorul checkpoint autorizat

`R15-06-03`: manager UI compact în tabul Build 42 pentru create/apply/update/
rename/duplicate/delete. După arhivarea lui R15-06-03 lucrul se oprește pentru
validarea utilizatorului; localizarea RO/EN nu începe înaintea acelei validări.
