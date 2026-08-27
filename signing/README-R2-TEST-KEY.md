# OPT-LAB R2 test signing key

`optlab-r2-test.jks` is a deliberately public, reproducible test key. It is not a production or
Google Play signing key.

- alias: `optlab-r2`
- store/key password: `optlabr2test`
- certificate SHA-256:
  `8b06ddea8e2f1ee4723b3a3f2a142719a8076bd1bb015adee0675ae2e042aab2`
- certificate subject: `CN=ZomDroid OPT-LAB R2 Test, OU=Local Test, O=Codex, L=Bucharest, C=RO`

The key is bundled because the R1 APK was signed by an ephemeral Android debug key whose private
part was not preserved in the R1 source or Drive folder. R2 therefore uses a new stable test key
for all future R2 lab updates. Anyone with this source can sign an APK accepted as an update to an
R2 lab package, so do not reuse either the key or an R2 package identity for production software.

The primary build is the side-by-side flavor:

```bash
./gradlew :app:assembleSideBySideDebug
```

Its package is `com.zomdroid.mglpz2`, so it can coexist with R1 (`com.zomdroid.mglpz1`) and the
original MobilePZ/ZomDroid package. The optional `replaceR1` flavor has the old package name but
cannot update R1 in place because Android also requires the old signing key.
