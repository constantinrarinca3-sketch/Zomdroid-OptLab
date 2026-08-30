# ZomDroid R13 — checkpoint persistent UI-01

Data UTC: 2026-08-29  
Ramură: `r13-optlab-tabbed-ui`  
Rollback integral: `6a73d966791b19f1520aeabc236e4f6edcadffa8`

## Stare implementată

- OPT-LAB este o destinație Android `Fragment` full-screen în graful de navigație.
- Sunt șase taburi reale în același ecran: Overview, Runtime, Display, Build 42,
  Native și Experimental.
- Overview folosește carduri de navigație și sumar live, conform direcției vizuale
  aprobate.
- Culorile, suprafețele, contururile și textul sunt derivate exclusiv din tema
  Material a aplicației; rezultatul moștenește tema light și tema dark.
- Dialogurile OPT-LAB imbricate vechi au fost eliminate.
- Setările generale, Build 42, Native și Experimental rămân module independente.
- Pathfinding, PopMan și CP2C sunt prezentate separat ca experimentale și rămân
  implicit OFF.
- Safe Mode este efectiv și reversibil: ocolește temporar toate optimizările fără
  să șteargă valorile salvate; dezactivarea lui restaurează selecțiile anterioare.

## Verificări host efectuate

- `OPTLAB_TABBED_UI_COMPILE PASS javac_release=17 tabs=6`
- `OPTLAB_PREFERENCES_INDEPENDENCE_UNIT PASS schema=8 safe_mode=1`
- `NATIVE_MODULES_PREFERENCES_UNIT PASS schema=3 safe_mode=1`
- `OPTLAB_TABBED_UI_SOURCE PASS ... cp62_unchanged=1`
- `CP6_2_EXACT_JAR_BYTECODE PASS versions=42.20,42.20.3`
- CP6.2 wiring/bundle, agent runtime, Native, PopMan, Pathfinding, Jassimp și
  MobileGL: PASS.
- `git diff --check`: PASS.

## Limită de mediu

Buildul Gradle Android complet nu a fost executat: distribuția Gradle 8.13 nu
există în cache, iar accesul la `services.gradle.org` este blocat în acest mediu.
Acesta rămâne `NOT RUN`, nu PASS.

## Integritate și limită de scop

- `app/src/main/assets/bundles/jars.tar` a rămas exact:
  `bbbc0768409a3960386a4dd7ce8863435ccc839d460da1250c965702bb78400c`
- agentul împachetat a rămas exact:
  `045461369c1442ba7f422a71b927a1956457ee0779bf9cac6e22b70330cff6d8`
- Pachetul CP6.6 nu a fost deschis, citit sau portat în acest checkpoint.

Următorul pas autorizabil este portarea CP6.6, dar numai după oprirea și raportarea
etapei UI către utilizator.
