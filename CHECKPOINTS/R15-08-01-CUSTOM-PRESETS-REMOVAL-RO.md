# R15-08-01 — eliminarea custom presets și recuperarea OPT-LAB

Data: 2026-08-31 UTC

Baseline: R15-07, arhivă SHA256
`966c5bf655386a5186d6993cd79d73f6d89db456474bde9af9f579316359f932`.

Rollback Git local: `bc2a8b8e0a5a05571c49756b25cabe7da4a075e2`.

## Cauză confirmată

Spinnerul custom presets era creat prin constructorul Android `ArrayAdapter(T[])` și apoi
actualizat prin `clear()/addAll()`. Lista array-backed este fixă și arunca
`UnsupportedOperationException` după primul preset. Scrierea catalogului reușea înainte de
refresh, astfel încât OPT-LAB putea rămâne inaccesibil la următoarea deschidere.

## Schimbări

- eliminat complet `OptLabCustomPresetStore` și toate API-urile/UI/testele sale;
- eliminată ruta Build 42 Presets și toate referințele din `OptLabFragment`;
- schema `OptLabPreferences` devine 13;
- migrare one-shot înaintea oricărei citiri OPT-LAB:
  - șterge `build42_custom_presets_v1` și `build42_custom_preset_active_v1`;
  - pune toate feature-urile registry-owned Build 42 pe OFF;
  - setează profilul Build 42 la Custom;
  - nu modifică General, Native sau Experimental independent;
- diagnosticul expune `customPresets=REMOVED`.

## Host validation

- `OPTLAB_PREFERENCES_INDEPENDENCE_UNIT PASS schema=13 ... presets=removed_migrated`
- `OPTLAB_TABBED_UI_COMPILE PASS javac_release=11 tabs=6`
- `OPTLAB_TABBED_UI_SOURCE PASS ... custom_presets=removed_migrated`
- `OPTLAB_REGISTRY_INTEGRITY_UNIT PASS ... toggle_roundtrip=all`
- `NATIVE_MODULES_PREFERENCES_UNIT PASS`

APK compile: NOT RUN. DEVICE TEST: NO.

