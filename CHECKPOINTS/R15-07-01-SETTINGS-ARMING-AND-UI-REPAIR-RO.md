# R15-07-01 — repararea armării setărilor și a navigării OPT-LAB

Data: 2026-08-30 UTC

Baseline autoritativ: arhiva `ZomDroid-R15-06-FINAL-COMPILE-SOURCE-2026-08-30.tar.zst`.
Commit local de rollback baseline: `948c9f30a61c1af1b824396194424915b6dbe815`.

## Probleme reproduse în sursă

1. Un callback întârziat al spinnerului de profil putea reaplica un profil complet după
   modificarea unui toggle individual. Efectul vizibil era că un `OFF` din Stream/FBO/Chunk
   putea reveni la valoarea profilului.
2. Argumentele salvate în instanță și câmpul JVM liber puteau conține proprietăți
   `-Dzomdroid.optlab.*` vechi. Lansarea adăuga apoi snapshotul curent fără să impună o singură
   valoare pentru fiecare cheie.
3. Navigarea Back era legată de pagini fixe, nu de ruta reală de origine.
4. Managerul de preseturi completa automat câmpul pentru un preset nou cu numele selecției.
   Crearea putea eșua astfel ca nume duplicat, iar cele șase acțiuni erau greu de diferențiat.
5. Catalogul de preseturi corupt era fail-closed, dar nu avea o recuperare simplă în UI.

## Reparații implementate

- `OptLabLaunchContract` elimină toate valorile OPT-LAB/native gestionate din argumentele vechi,
  scrie fiecare proprietate exact o dată și verifică snapshotul final imediat înainte de JNI.
  Sunt eliminate și formele JVM prescurtate `-Dcheie`, nu doar `-Dcheie=valoare`.
- Registrul emite toate proprietățile de feature, inclusiv `0`, iar launcherul verifică valoarea
  fiecărei proprietăți plus starea requested/active pentru Pathfinding și PopMan.
- Fiecare scriere SharedPreferences critică folosește `commit()` sincron și read-back; o
  nepotrivire oprește lansarea/setarea cu eroare explicită în loc să pretindă succes.
- Profilele General, Build 42 și politica Box64 se aplică numai după o interacțiune reală a
  utilizatorului; callbackurile programatice nu mai pot rescrie toggle-uri.
- Back folosește istoric de rute. Exemplu verificat structural:
  `Build 42 → World Stream & Chunk → Experimental → Back → World Stream & Chunk → Back → Build 42`.
- Managerul de preseturi vizibil are patru operații clare: creare nouă, aplicare, actualizare și
  ștergere. API-urile rename/duplicate rămân în motor pentru compatibilitate, dar nu aglomerează UI.
- Câmpul de nume rămâne exclusiv pentru un preset nou și nu mai este suprascris la selectare.
- Dacă lista este coruptă apare o acțiune de reparare în doi pași. Ea șterge numai catalogul și
  ID-ul activ; nu modifică niciun toggle curent.
- `opt-proof.log` este inclus în Export logs pentru verificare fără ADB.

## Acoperire

- toate cele 48 de intrări ale registrului;
- toate feature-urile configurabile Build 42 și Experimental: ON → restart logic → OFF → restart;
- toate controalele General/Runtime/Display în ambele direcții;
- Lighting, PZClipper, Pathfinding și PopMan în ambele direcții;
- profilele independente, Safe Mode, dependențele și dependenții;
- snapshotul JVM fără proprietăți duplicate;
- agentul integrat `version=15 / schema=14`;
- JAR-urile exacte 42.20 și 42.20.3.

## Limită de verdict

Host QA este PASS. APK compile și testul pe dispozitiv nu fac parte din acest checkpoint.
Nu declara DEVICE PASS înainte de un APK instalat și de compararea `opt-proof.log` pentru ON/OFF.

