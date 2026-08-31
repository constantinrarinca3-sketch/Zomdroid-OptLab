# ZomDroid R13 — OPT-LAB full-screen tabbed UI handoff

Data: 2026-08-29  
Etapă: UI finalizat pe host; portarea CP6.6 nu a început.

## Verdict

Interfața cerută a fost implementată ca ecran Android full-screen, nu ca dialog.
Are șase taburi reale — Overview, Runtime, Display, Build 42, Native și
Experimental — cu overview pe carduri, sumar live și navigare directă între
module. Folosește tema Material existentă, astfel încât păstrează atât aspectul
light, cât și tema neagră a aplicației fără o paletă paralelă.

## Organizare și comportament

- Runtime/Display, Build 42 și Native sunt independente.
- Masterul general controlează numai Runtime/Display; OFF nu dezactivează și nu
  rescrie Build 42 ori Native.
- Profilul general nu include preseturi Build 42. STREAM ALL, FBO ALL și FULL
  CANDIDATE există numai în tabul Build 42.
- Lighting și PZClipper sunt în Native.
- Pathfinding, PopMan și CP2C sunt în Experimental, marcate explicit și implicit
  OFF. Nu sunt prezentate ca validate 100%.
- Safe Mode ocolește temporar toate optimizările generale, Build 42 și native,
  fără să șteargă configurația. La OFF sunt restaurate valorile salvate.
- Build 42 ALL OFF nu modifică CP2C, deoarece CP2C rămâne experimental și separat.

## Compatibilitate și regresii

Bundle-ul agent CP6.2 nu a fost schimbat. Testele bytecode exacte trec pentru
Build 42.20 și 42.20.3. Au trecut și verificările host pentru wiring CP6.2,
agent/runtime, module native, PopMan, Pathfinding, Jassimp și MobileGL.

Compilarea izolată Java 17 a noului fragment și a componentelor Material trece.
Buildul Android Gradle complet nu poate fi rulat în acest mediu deoarece Gradle
8.13 nu este în cache, iar descărcarea este blocată. Prin urmare, Gradle/APK și
testul vizual pe dispozitiv sunt `NOT RUN`, nu PASS.

## Rollback și checkpointuri

- Rollback R12 neatins: `6a73d966791b19f1520aeabc236e4f6edcadffa8`
- Baseline UI persistent: `1209200b72d21f0279135e465396d80c2cb64e61`
- Implementare UI: `0597e7263e355acacfbb612a3901255c589570de`
- Ramură: `r13-optlab-tabbed-ui`

Pentru rollback complet:

```bash
git switch --detach 6a73d966791b19f1520aeabc236e4f6edcadffa8
```

Pentru validare host UI:

```bash
tools/test-optlab-tabbed-ui-source.sh
tools/test-cp62-exact-jars.sh /path/to/42.20.jar /path/to/42.20.3.jar
```

## Continuarea CP6.6

Pachetul `MGLPZ-Performance-CP6.6-MEGAHOTPATH-DEVICE-VALIDATED-APK-HANDOFF-PACK.zip`
a fost primit și identificat prin SHA256
`6b9b9b334f02a14b7a1dac707f15d95efec2e33784923aada934cec5ae89d9d9`,
dar nu a fost deschis, citit sau portat în etapa UI. Înainte de portare trebuie
creat un nou rollback/checkpoint din sursa R13 UI și apoi citit integral handofful
CP6.6. Nu se declară niciun rezultat DEVICE nou fără rulare reală pe dispozitiv.
