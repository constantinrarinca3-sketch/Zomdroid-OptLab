# Remediere ShadowBridge: moduri golite la lansare

## Simptom confirmat

După import, modurile există în `Zomboid/mods`, dar la lansarea jocului
directoarele rămân fără conținut. Logul jocului raportează apoi
`NoSuchFileException` pentru directorul de moduri și `loadMods(0 mods)`.

## Cauză

`LowercasePathAliases.applyToMod()` reconstruia linkul ShadowBridge prin
`FileUtils.deleteDirectory(modLink)`. Implementarea veche a
`deleteDirectory()` utiliza `File.listFiles()`, care urmărește un symlink către
un director. În consecință, erau șterse recursiv fișierele modului real înainte
de eliminarea linkului.

## Remediere

- `FileUtils.deleteDirectory()` detectează acum un symlink înainte de orice
  enumerare și șterge numai linkul.
- `LowercasePathAliases.applyToMod()` separă explicit cazul symlinkului de o
  eventuală copie reală veche.
- `LowercasePathAliases.repair()` recreează `Zomboid/mods` dacă lipsește.
- `FileUtilsSymlinkTest` verifică faptul că eliminarea linkului ShadowBridge
  păstrează fișierul Lua din modul real.

Modurile al căror conținut a fost deja șters trebuie reimportate după instalarea
APK-ului recompilat.
