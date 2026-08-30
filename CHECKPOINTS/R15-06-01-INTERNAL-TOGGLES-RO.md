# R15-06-01 — controale individuale pentru optimizările Internal

Data: 2026-08-30 UTC

Status: **DONE (SOURCE/HOST)**

APK COMPILE: **NOT RUN**

DEVICE TEST: **NO**

## Scop

Checkpointul rezolvă situația în care optimizările Build 42 cu maturitate
`INTERNAL` erau active prin profiluri, dar în pagina modulului apăreau doar ca
informație fără control individual.

## Implementare

- cardurile read-only pentru funcțiile `INTERNAL` au fost înlocuite cu
  `ToggleCard`-uri reale;
- toate toggle-urile interne folosesc calea existentă și unică
  `OptLabPreferences.setFeatureEnabled(...)`;
- starea rămâne persistentă în `SharedPreferences` prin cheia stabilă a
  funcției;
- activarea închide automat dependențele, iar dezactivarea închide automat
  dependenții, exact ca pentru celelalte funcții Build 42;
- orice override individual mută profilul Build 42 în `Custom`;
- Safe Mode dezactivează temporar controalele, fără să șteargă valorile;
- maturitatea, profilele implicite, fallback-urile și proprietățile agentului
  nu au fost schimbate.

Agentul și bundle-ul sunt byte-identice cu R15-05. Acest checkpoint schimbă
numai UI-ul și testele de contract pentru preferințe/registry.

## Fișiere și metode modificate

- `app/src/main/java/com/zomdroid/fragments/OptLabFragment.java`
  - câmpurile de carduri Build 42;
  - `onDestroyView()`;
  - `buildBuild42Page()`;
  - `buildModulePage(...)`;
  - `syncBuild42(...)`;
  - `maturityLabel(...)`.
- `tools/OptLabRegistryIntegrityUnit.java`
  - `verifyProfiles()` verifică override-ul unei funcții interne și tranziția
    la profilul `Custom`.
- `tools/test-optlab-tabbed-ui-source.sh`
  - contractul UI cere toggle-uri interne și respinge vechile carduri read-only.

## Validare host

- compilare Java izolată a UI-ului: **PASS**;
- independența preferințelor și profilurilor: **PASS**;
- integritatea registry-ului: **PASS**;
- runner final R15 pe JAR-urile exacte 42.20 și 42.20.3: **PASS**;
- agent determinist și bundle source-matched: **PASS**;
- APK/device: **NU AU FOST RULATE**.

Rezultatele concise sunt în
`CHECKPOINTS/R15-06-01-HOST-TEST-RESULTS.txt`.

## Rollback

Rollback-ul imediat este commitul local al baseline-ului autoritativ R15-05:

`fdb0a742a946c64a98c74c6dbb6f3568165cace9`

Arhiva de intrare rămâne:

`ZomDroid-R15-05-FINAL-QA-FULL-SOURCE-2026-08-30.tar.zst`

SHA-256:

`93c94cde67a71046f93465afe22dcfb9d278acd45a51204ee4892c5ec05e3630`

## Următorul checkpoint autorizat

`R15-06-02`: motor versionat și testabil pentru mai multe preseturi custom
Build 42, persistate prin ID-uri stabile de funcție. Nu se începe încă
localizarea RO/EN.
