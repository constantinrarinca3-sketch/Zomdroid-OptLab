# R15-06 — sursă finală pentru compilare și device test

Data: 2026-08-30 UTC

## Verdict

- Codul și testele host R15-06-03 sunt PASS.
- Localizarea RO/EN a fost amânată explicit de utilizator și nu face parte din
  această livrare.
- Agentul și `jars.tar` sunt neschimbate față de baseline-ul validat.
- APK compile: **NOT RUN** în mediul de predare.
- Device test: **NO**. Regresia raportată `world black` nu este declarată
  rezolvată până la izolarea mecanismului pe dispozitiv.

## Compilare recomandată

Cerințe: JDK 17, Android SDK 35, NDK `27.3.13750724`, CMake `3.22.1` și acces la
dependențele Gradle declarate de proiect.

Din rădăcina sursei:

```bash
./gradlew clean :app:assembleSideBySideDebug --stacktrace
```

APK-ul rezultat este în `app/build/outputs/apk/sideBySide/debug/` și folosește
identitatea side-by-side `com.zomdroid.mglpz2` plus cheia de test inclusă.

Pentru înlocuirea deliberată a R1, numai după dezinstalarea aplicației vechi:

```bash
./gradlew :app:assembleReplaceR1Debug --stacktrace
```

`preBuild` rulează automat `tools/build-optlab-agent.sh` și
`tools/test-jassimp-direct.sh`; o compilare Gradle directă nu poate împacheta
intenționat agentul vechi fără ca aceste verificări să ruleze.

## Verificare înainte de dispozitiv

Cu JAR-urile exacte 42.20 și 42.20.3 disponibile:

```bash
bash tools/test-r15-final-qa.sh /path/projectzomboid-42.20.jar /path/projectzomboid-42.20.3.jar
```

Verdictul așteptat este:

```text
R15_FINAL_QA PASS source_host=1 exact_42_20=1 exact_42_20_3=1 apk_compile=NOT_RUN device_test=NO
```

## Device test obligatoriu

Urmează `CHECKPOINTS/R15-06-03-USER-VALIDATION-CHECKLIST-RO.txt`. Testează mai
întâi Safe Mode / ALL OFF, apoi activează mecanismele Internal pe rând. Salvează
un preset numai după ce combinația nu produce lumea neagră. Pathfinding,
PopMan, FBO inner-loop și same-program bind rămân experimentale/OFF implicit.

## Rollback și continuare

- rollback imediat înainte de UI: arhiva R15-06-02 cu SHA-256
  `9363643e38f495cd2829073f7aeb5d70a99e1ecebf450735b9c52003ea7f4a25`;
- checkpoint UI: `CHECKPOINTS/R15-06-03-PRESET-MANAGER-UI-RO.md`;
- patchurile de implementare sunt în `CHECKPOINTS/`;
- localizarea se reia numai la cererea explicită a utilizatorului;
- nu se declară DEVICE PASS fără rularea APK-ului rezultat pe dispozitiv.
