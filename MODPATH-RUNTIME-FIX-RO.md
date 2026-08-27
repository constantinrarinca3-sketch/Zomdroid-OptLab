# ZomDroid R3 v5 — corecție runtime pentru căile modurilor

Bază: arhiva originală indicată de utilizator, verificată cu SHA-256
`93d7d32df8e87705448fceb423d7f414e0cf5b93a887a1d83f6cc0eb8e8c3475`.

## Problema rezolvată

La încărcarea unor scripturi de mod, jocul poate transforma o cale absolută validă într-o cale
dublată și lowercased, de forma:

```text
<mods>/data/user/0/<package>/files/instances/<instanță>/zomboid/mods/<mod>/<fișier>
```

Corecția nu presupune un nume fix pentru instanță. `GameLauncher` transmite agentului rădăcina
instanței selectate, calculată din `gameInstance.getHomePath()/Zomboid/mods`. Numele instanței
poate fi oricare.

## Cum funcționează

- agentul interceptează `ScriptManager.LoadFile` și `IndieFileLoader.getStreamReader`;
- repară doar căi absolute aflate sub rădăcina `Zomboid/mods` a instanței curente;
- recunoaște structural dublarea Android și caută fiecare segment fără diferență de casing;
- întoarce calea canonică, cu numele reale de pe disc, numai dacă ținta este un fișier existent;
- lasă neatinse căile relative, resursele vanilla, traversările `..`, intrările `.fmtrashed*`,
  țintele inexistente și potrivirile ambigue;
- orice eroare este fail-open: jocul primește calea originală;
- emite o singură dovadă `MOD_PATH_RESOLVER ... APPLIED` pe sesiune.

Installerul nu mai creează automat arborele shadow `mods/data/...` și nu mai dublează modul cu
nume lowercased. Fiecare mod este instalat o singură dată, păstrând casing-ul original.

## Compatibilitate și limitări

Corecția este armată numai pentru `projectzomboid.jar` cu SHA-256:

```text
e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8
```

JAR-ul jocului nu este inclus și nu este modificat. Problemele independente din moduri și cazul
Horse/ploaie nu fac parte din această corecție.

## Rebuild și verificare

Agentul precompilat din `app/src/main/assets/bundles/jars.tar` este deja actualizat. După alte
modificări ale sursei agentului, reconstruiește-l și rulează testele:

```bash
./tools/build-optlab-agent.sh
./tools/run-optlab-runtime-tests.sh
./gradlew :app:assembleSideBySideDebug
```

Testele host includ calea observată pentru AutoTsarTrailers, calea 73Winnebago și o instanță cu
un nume arbitrar. Testul final trebuie făcut pe Android după eliminarea manuală a vechiului
director `$MODS/data`, pentru a demonstra că arborele shadow nu mai este necesar.
