# ZomDroid R15-08 — handoff final

Data: 2026-08-31

## Verdict

Sursa R15-08 este checkpointul de recuperare a încrederii pentru OPT-LAB. Host QA este PASS, inclusiv verificările structurale și transformările pe `projectzomboid.jar` 42.20 și 42.20.3. Compilarea APK și testarea pe dispozitiv nu au fost executate în acest mediu; nu se declară DEVICE PASS.

## Ce s-a schimbat

1. Managerul de preseturi custom salvate a fost eliminat complet, împreună cu stocarea și migrarea lui defectă. Profilul standard `Custom` din selectoarele OPT-LAB a fost păstrat intenționat.
2. F2 `FBO frame-budget governor` și C1 `Stream–FBO coordinator` au fost eliminate complet din UI, registru, profiluri, proprietăți JVM, agent și runtime. FBO păstrează numai deduplicarea dirty și inner-loop reuse; nu sare și nu amână chunk-uri.
3. Profilul `Safe` pentru Build 42 este acum ALL OFF real.
4. Jocul rulează într-un proces Android separat (`:game`). La ieșirea din joc, procesul JVM este închis explicit. Butonul `Aplică și repornește ZomDroid` folosește procesul helper `:restart`, închide launcherul și îl redeschide, astfel încât următoarea lansare a jocului primește proprietățile armate într-un JVM nou.
5. Navigarea OPT-LAB continuă să folosească istoricul rutelor: Back revine exact la pagina anterioară, nu la categoria Experimental globală.
6. Agentul inclus a fost reconstruit: version 16 / schema 15.

## Checkpointuri și rollback

- Baseline R15-07: `bc2a8b8e0a5a05571c49756b25cabe7da4a075e2`
- R15-08-01, eliminare preseturi salvate: `d5536ec6b77bb985f513c3521133dbb73a20fe28`
- R15-08-02, eliminare F2/C1: `6e0851cf4c9c07556a053f1cab0ba7707e720f8d`
- R15-08-03, JVM separat și restart determinist: `9b28f2225930cf64b90d78b6c9b2954b810bc295`

Patchurile independente sunt în `CHECKPOINTS/0001-R15-08-*.patch`. Pentru rollback complet se poate reveni la commitul baseline; pentru rollback selectiv se poate aplica invers patchul checkpointului corespunzător.

## Validare executată

- Preferințe/registru/profiluri/toggle round-trip: PASS
- Migrare preseturi defecte și deschidere UI: PASS pe host
- Contract OFF și eliminarea proprietăților JVM stale: PASS
- Izolare procese și restart helper: PASS pe sursă/host
- Agent source/runtime/premain: PASS
- Bundle agent reconstruit și marker 16/15: PASS
- CP6.13.4 direct build și FBO inner-loop parity: PASS
- Transformări reale 42.20 și 42.20.3: PASS
- Pathfinding bytecode transform pe ambele JAR-uri: PASS
- APK compile: NOT RUN (SDK/Gradle indisponibil local)
- Device test: NO

Rezultatul complet este în `CHECKPOINTS/R15-08-FINAL-HOST-TEST-RESULTS.txt`.

## Validare obligatorie pe dispozitiv

1. Compilează APK-ul prin GitHub Actions și instalează-l.
2. Deschide OPT-LAB: profilul standard `Custom` trebuie să existe, dar managerul de preseturi salvate nu trebuie să mai existe.
3. Verifică faptul că F2 și C1 nu mai apar nicăieri.
4. Aplică profilul Build 42 `Safe`: toate mecanismele Build 42 trebuie să fie OFF.
5. Schimbă individual un toggle, apasă `Aplică și repornește ZomDroid`, apoi lansează jocul și verifică linia `[ZD-OPT-LAB]`/`[ZD-OPT-PROOF]` pentru valoarea efectivă.
6. Repetă ON → restart → joc și OFF → restart → joc pentru Stream/Chunk; fiecare lansare trebuie să aibă sesiune/JVM nou.
7. Testează încărcarea chunk-urilor și lumea neagră. Orice regresie rămasă trebuie atribuită unui mecanism încă prezent folosind dovada efectivă din log, nu starea vizuală a switchului.

## Continuare

Nu adăuga alte optimizări până la DEVICE PASS pentru armarea ON/OFF. Păstrează mecanismele independente și nu reintroduce F2/C1 ori managerul de preseturi salvate fără o reproiectare și teste instrumentate.
