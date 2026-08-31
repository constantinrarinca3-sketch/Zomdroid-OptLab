# R15-08-03 — proces de joc izolat și restart explicit

Data: 2026-08-31

## Rezultat

Fiecare sesiune de joc rulează acum în procesul Android privat `:game`. HotSpot și agentul
OPT-LAB nu mai locuiesc în procesul launcherului, deci fiecare apăsare de Play creează un
proces și un JVM complet noi. La revenirea normală din `GameLauncher.startGame()`,
`GameActivity` se închide și omoară numai procesul `:game`; launcherul rămas dedesubt revine.

În Overview există butonul `Aplică și repornește ZomDroid`. Butonul pornește activitatea
transparentă `ProcessRestartActivity` în procesul separat `:restart`. Helperul înlocuiește
procesul launcherului și relansează activitatea MAIN. Nu depinde de un alarm Android inexact.

## Clarificare UI

Managerul de preseturi custom salvate rămâne eliminat. Opțiunea standard `Custom` din
selectoarele General/Build 42 rămâne intenționat: ea arată că toggle-urile au fost modificate
manual și nu mai corespund exact profilurilor Safe/Recommended/Aggressive.

## Inițializare multi-proces

`ZomdroidApplication` inițializează `AppStorage`, `GameInstanceManager` și
`LauncherPreferences` înainte de primul `Activity.onCreate()` în procesele main și `:game`.
Procesul `:game` nu pornește încă un cititor logcat și nu rotește logul procesului principal.
Procesul `:restart` rămâne minimal și nu inițializează subsistemele aplicației.

## Curățare suplimentară F2/C1

Au fost eliminate și cele trei proprietăți de tuning rămase în `GameLauncher`:

- `zomdroid.optlab.fbo.budget`;
- `zomdroid.optlab.fbo.urgent.budget`;
- `zomdroid.optlab.fbo.max.defer.frames`.

## Fișiere principale

- `app/src/main/AndroidManifest.xml`;
- `app/src/main/java/com/zomdroid/AppProcessRestarter.java`;
- `app/src/main/java/com/zomdroid/ProcessRestartActivity.java`;
- `app/src/main/java/com/zomdroid/ZomdroidApplication.java`;
- `app/src/main/java/com/zomdroid/GameActivity.java`;
- `app/src/main/java/com/zomdroid/GameLauncher.java`;
- `app/src/main/java/com/zomdroid/fragments/OptLabFragment.java`;
- `app/src/main/res/values/strings.xml`;
- `tools/test-optlab-process-isolation.sh`;
- workflow-urile GitHub Actions.

## Limită de validare

Host/source validation este PASS. Compilarea Gradle locală nu a fost executată: mediul nu
conține Android SDK sau distribuția Gradle cache-uită, iar descărcarea externă este blocată.
GitHub Actions trebuie să compileze APK-ul. DEVICE PASS nu este declarat până la instalarea
și testarea acelui APK.

## Rollback

Rollback-ul acestui checkpoint este commitul persistent R15-08-02
`682a0825451653704970b0d843423d7c7e6fec55`.
