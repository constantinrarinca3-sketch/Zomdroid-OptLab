# R15-06-03 — manager UI pentru preseturi custom Build 42

Data: 2026-08-30 UTC

Status: **DONE (SOURCE/HOST) — PAUZĂ PENTRU VALIDAREA UTILIZATORULUI**

APK COMPILE: **NOT RUN**

DEVICE TEST: **NO**

## Rezultat vizual/structural

Pagina principală Build 42 primește un singur card nou:

`Preseturi custom Build 42`

Toate controalele de administrare sunt într-o subpagină separată, nu în lista
modulelor și nu într-un dialog suprapus. Structura rămâne compatibilă cu tema
aplicației, inclusiv tema întunecată: aceleași `MaterialCardView`, culori derivate
din temă, tipografie și butoane ca restul OPT-LAB.

Subpagina conține:

1. card de stare;
2. selector pentru presetul salvat;
3. câmp unic de nume;
4. `Creează din configurația curentă`;
5. `Aplică` / `Actualizează` pe același rând;
6. `Redenumește` / `Duplică` pe același rând;
7. `Șterge`, cu confirmare prin a doua apăsare.

Nu a fost adăugat un tab nou și nu a fost reintrodus niciun nested dialog.

## Stări afișate explicit

- `Custom · <nume>` — presetul activ este identic cu configurația curentă;
- `Modified · based on <nume>` — un control a fost schimbat după aplicare;
- `Selectat: <nume> · nu este aplicat` — selectorul indică alt preset;
- `Safe Mode · controale blocate` — valorile sunt păstrate;
- catalog corupt/necunoscut — controalele de scriere sunt blocate și datele sunt
  păstrate pentru recuperare, fără overwrite automat.

Profilul built-in rămâne separat. Alegerea Safe/Recommended/Aggressive nu șterge
catalogul. `ALL OFF` nu șterge presetul de bază și produce starea `Modified`.

## Problema „world black”

Checkpointul nu declară cauza sau remedierea regresiei grafice fără test device.
El elimină blocajul de diagnostic: toate optimizările Internal pot fi oprite
individual, iar combinația stabilă poate fi salvată permanent ca preset numit.

## Fișiere și metode modificate

- `app/src/main/java/com/zomdroid/fragments/OptLabFragment.java`
  - câmpurile/lifecycle-ul managerului;
  - `buildBuild42Page()`;
  - `buildCustomPresetManagerPage()`;
  - listenerul selectorului;
  - `syncBuild42(...)`, `syncCustomPresetManager(...)` și stările rezumate;
  - create/apply/update/rename/duplicate/delete;
  - navigarea home/module/preset manager.
- `app/src/main/java/com/zomdroid/fragments/OptLabUi.java`
  - `TextFieldCard` theme-aware;
  - `actionRow(...)` pentru gruparea compactă a acțiunilor.
- `tools/ui-stubs/...`
  - suprafețele minime EditText, adapter dinamic și API-ul presetului.
- `tools/test-optlab-tabbed-ui-compile.sh`
  - compilează UI-ul cu Java 11, egal cu source compatibility al aplicației.
- `tools/test-optlab-tabbed-ui-source.sh`
  - verifică subpagina separată, toate cele șase operații, statusurile și absența
    nested dialogs.

## Validare host

- UI compile `--release 11`: **PASS**;
- UI source/theme/light-dark contract: **PASS**;
- custom preset engine/lifecycle: **PASS**;
- preferences/native/registry: **PASS**;
- agent și bundle determinist: **PASS**, byte-identice cu R15-05;
- CP6.13.4/CP6.13.10 și toate transformările exacte pe 42.20/42.20.3:
  **PASS**;
- APK compile: **NOT RUN** — Gradle 8.13 nu era disponibil local, iar descărcarea
  distribuției a fost blocată de rețeaua mediului;
- device: **NO**.

## Rollback

Checkpointul persistent anterior R15-06-02:

- commit: `8a472b9387840959c85ea8ddaa2cceca66a0e3be`;
- arhivă:
  `ZomDroid-R15-06-02-CUSTOM-PRESET-ENGINE-FULL-SOURCE-2026-08-30.tar.zst`;
- SHA-256:
  `9363643e38f495cd2829073f7aeb5d70a99e1ecebf450735b9c52003ea7f4a25`.

## Stop obligatoriu

Nu începe `R15-06-04` (localizare RO/EN), nu muta textele noi în resources și
nu adăuga optimizări până când utilizatorul nu validează checkpointul 03.
Checklistul de validare este în
`CHECKPOINTS/R15-06-03-USER-VALIDATION-CHECKLIST-RO.txt`.
