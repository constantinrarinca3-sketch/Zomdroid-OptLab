# R15-08-02 — eliminare F2/C1 și profil Safe real

Data: 2026-08-31

## Verdict

`FBO_FRAME_BUDGET` (F2) și `STREAM_FBO_COORDINATOR` (C1) au fost eliminate complet
din configurația lansabilă. Nu mai există în registrul APK, UI, profiluri, proprietățile
agentului, deciziile de compatibilitate, transformerele Byte Buddy sau runtime-ul agentului.

Motivul eliminării este semantic, nu cosmetic: F2 intercepta rezultatul
`FBORenderLevels.isDirty()` și putea întoarce `false` pentru un FBO dirty deja cache-uit.
C1 micșora suplimentar bugetul în ferestrele considerate urgente. Această amânare a muncii
dirty contrazice regula CP6.13.4 „niciun chunk dirty nu este sărit sau amânat” și corespunde
familiei CP6.13.2 respinse. În plus, activarea C1 arma automat F2, dar dezactivarea C1 nu
dezarma prerechizita F2; astfel combinația putea părea aleatorie din UI.

## Schimbări

- eliminate enum-urile și proprietățile F2/C1 din `OptLabFeatureRegistry`;
- migrarea R15-08 șterge cheile persistente vechi `fbo_frame_budget` și
  `stream_fbo_coordinator`;
- contractul de lansare curăță în continuare orice argument JVM vechi cu prefixul
  `zomdroid.optlab.*`, dar nu mai emite proprietățile eliminate nici cu valoarea `0`;
- eliminat transformerul pentru `FBORenderChunkManager` și visitorul `allowDirty`;
- `FboRuntime` conține acum exclusiv deduplicarea semantic sigură F1;
- eliminată starea de urgență din `StreamCoreRuntime`, folosită numai de C1;
- profilul Build 42 `Safe` este acum garantat ALL OFF pentru fiecare feature Build 42;
- agent reconstruit ca `version=16 schema=15` și publicat în `jars.tar`.

Referințele rămase la vechea proprietate F2 sunt intenționate exclusiv în testul de
migrare al contractului de lansare: acesta dovedește că un argument stale `=1` este șters
și nu reapare.

## Identitate bundle

- `zomdroid-agent.jar`: `94732776a7e82e8ee97837aa5c36d3b1c629347a64b1976253aaddf198e271cf`
- `app/src/main/assets/bundles/jars.tar`:
  `2d8562b983fcd8e38f765d90571930496340bf7d221871a570736d26709a8805`

## Rollback

Punctul anterior este commitul checkpointului R15-08-01. Revenirea trebuie făcută prin
revert al commitului R15-08-02; nu se copiază un `jars.tar` vechi peste sursa agentului.

## Limită de validare

Host validation este PASS. Acest checkpoint nu declară DEVICE PASS; comportamentul final
trebuie recompilat și verificat pe dispozitiv.
