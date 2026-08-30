# R15-07 — handoff final pentru compilare și device validation

Data: 2026-08-30 UTC

## Verdict

- Repararea sursei: **DONE**.
- Host QA complet: **PASS**.
- Validare exactă `projectzomboid.jar` 42.20 și 42.20.3: **PASS**.
- Compilare Gradle în mediul curent: **NEEXECUTATĂ**, deoarece Gradle 8.13 nu era în cache,
  iar accesul la `services.gradle.org` a fost blocat înainte de configurarea proiectului.
- DEVICE PASS: **NU**; trebuie stabilit numai după APK instalat.

## Ce s-a reparat

1. Toate setările vizibile folosesc scriere sincronă cu read-back.
2. Profilele nu mai sunt aplicate de callbackuri programatice/întârziate.
3. La fiecare launch sunt eliminate toate proprietățile vechi `zomdroid.optlab.*`,
   `zomdroid.native.pathfinding.*` și `zomdroid.native.popman.*` din instanță/JVM field.
4. Snapshotul curent emite `0` și `1` explicit, fără duplicate, și este verificat imediat
   înainte de `startGame()`.
5. Navigarea Back folosește istoricul exact al rutelor.
6. Preseturile multiple rămân suportate, dar UI-ul are doar create/apply/update/delete;
   rename/duplicate rămân în API pentru compatibilitate viitoare.
7. Un catalog preset corupt poate fi resetat în doi pași fără a schimba toggle-urile curente.
8. `opt-proof.log` este inclus în arhiva Export logs.

## Identitate APK

- versionCode: `14756`
- side-by-side applicationId: `com.zomdroid.mglpz2`
- side-by-side versionName: `1.4.7v5-optlab-r15-07-settings-fix-side`
- replace-R1 applicationId: `com.zomdroid.mglpz1`
- replace-R1 versionName: `1.4.7v5-optlab-r15-07-settings-fix-replace`

Folosește în mod normal flavorul **sideBySide**. Flavorul replaceR1 este numai pentru instalarea
deliberată peste identitatea R1.

## GitHub Actions și locația APK

Workflow-urile reconstruiesc agentul înainte de Gradle și încarcă acum din calea reală cu flavor:

`app/build/outputs/apk/**/debug/*.apk`

respectiv release. `if-no-files-found: error` împiedică un workflow verde fără APK livrat.
În workflow-ul „Zomdroid (Android Studio Build)”, câmpul branch acceptă acum orice nume de branch.

Build local recomandat:

`./gradlew assembleSideBySideDebug --stacktrace`

APK așteptat:

`app/build/outputs/apk/sideBySide/debug/ZomDroid-1.4.7v5-optlab-r15-07-settings-fix-side-sideBySide-debug.apk`

## Device validation obligatorie

Urmează `CHECKPOINTS/R15-07-DEVICE-VALIDATION-CHECKLIST-RO.txt`. Verificarea principală este
ON → launch → Export logs, apoi OFF → închidere completă → launch → Export logs. În al doilea
`opt-proof.log`, mecanismul trebuie să fie `state=off detail=not_requested`, nu ACTIVE/EXERCISED.

## Rollback

- baseline R15-06: `948c9f30a61c1af1b824396194424915b6dbe815`
- implementare R15-07-01: `5f472cceeeaa1a976443f62b1f8b8ba7c8df0feb`
- checkpoint documentat R15-07-01: `d5839ebcc75da4ddfd8bee3b9645db882449272d`
- patch autonom: `CHECKPOINTS/0001-R15-07-01-repair-settings-arming-navigation-and-pres.patch`

