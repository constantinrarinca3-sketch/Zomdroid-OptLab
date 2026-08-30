import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val hasSigningConfig = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
).all { localProperties[it] != null }

val prepareOptLabAgentBundle by tasks.registering(Exec::class) {
    group = "build"
    description = "Rebuilds and verifies the OPT-LAB Java agent bundled in jars.tar"
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("tools/build-optlab-agent.sh").absolutePath)

    inputs.file(rootProject.file("tools/build-optlab-agent.sh"))
    inputs.files(fileTree(rootProject.file(
        "optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5/" +
                "zomdroid-agent/src/main/java"
    )) { include("**/*.java") })
    outputs.file(file("src/main/assets/bundles/jars.tar"))

    // The committed bundle is an input seed as well as the output. Always rebuilding keeps a
    // direct Gradle/Android Studio build from packaging a stale agent after source handoff.
    outputs.upToDateWhen { false }
}

val verifyJassimpDirectBundle by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the CP3 direct ARM64 JAssimp payload, ABI, source patch and rollback"
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("tools/test-jassimp-direct.sh").absolutePath)

    inputs.file(rootProject.file("tools/test-jassimp-direct.sh"))
    inputs.file(rootProject.file("app/src/main/assets/bundles/libs.tar.xz"))
    inputs.file(rootProject.file(
        "optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5/" +
                "patches/assimp/0002.patch"
    ))
    inputs.dir(rootProject.file(
        "optlab_deps/jassimp-direct-0dbe092850d5cf528dbdfac01603d9d1bb799d04"
    ))

    // The task validates a compressed APK input and its independently retained rollback on every
    // direct Gradle/Android Studio build. A stale or agent-based handoff must fail before packaging.
    outputs.upToDateWhen { false }
}

android {
    namespace = "com.zomdroid"
    compileSdk = 35

    signingConfigs {
        create("optLabR2Test") {
            // Public test key kept with this lab source so later R2 test builds remain
            // update-compatible. It is deliberately not a production/Play signing key.
            storeFile = rootProject.file("signing/optlab-r2-test.jks")
            storePassword = "optlabr2test"
            keyAlias = "optlab-r2"
            keyPassword = "optlabr2test"
        }
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(localProperties["RELEASE_STORE_FILE"].toString())
                storePassword = localProperties["RELEASE_STORE_PASSWORD"].toString()
                keyAlias = localProperties["RELEASE_KEY_ALIAS"].toString()
                keyPassword = localProperties["RELEASE_KEY_PASSWORD"].toString()

            }
        }
    }

    defaultConfig {
        // The default is the safe side-by-side identity. The replaceR1 flavor exists only for a
        // deliberate fresh install after removing R1; its lost debug key makes an in-place update
        // cryptographically impossible.
        applicationId = "com.zomdroid.mglpz2"
        minSdk = 30
        targetSdk = 35
        versionCode = 14755
        versionName = "1.4.7v5-optlab-r14-cp66-modular-side"
        manifestPlaceholders["optLabAppLabel"] = "ZomDroid OPT LAB R14"

        // JavaSteam + protobuf + kotlin stack push past the 64K method limit.
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Align native .so segments to 16 KB pages (Android 15+ requirement).
                // NDK r27 doesn't enable this by default; the flag adds -Wl,-z,max-page-size=16384.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    flavorDimensions += "installMode"
    productFlavors {
        create("sideBySide") {
            dimension = "installMode"
            applicationId = "com.zomdroid.mglpz2"
            versionCode = 14755
            versionName = "1.4.7v5-optlab-r14-cp66-modular-side"
            manifestPlaceholders["optLabAppLabel"] = "ZomDroid OPT LAB R14"
        }
        create("replaceR1") {
            dimension = "installMode"
            applicationId = "com.zomdroid.mglpz1"
            versionCode = 14755
            versionName = "1.4.7v5-optlab-r14-cp66-modular-replace"
            manifestPlaceholders["optLabAppLabel"] = "ZomDroid OPT LAB R14 Replace"
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "ZomDroid-${variant.versionName}-${variant.flavorName}-${variant.buildType.name}.apk"
        }
    }

    buildTypes {
        if (hasSigningConfig) {
            release {
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("optLabR2Test")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // These are exact audited AB3 payloads. AGP must not strip their DWARF sections and
            // silently change the runtime identity checked before installation.
            keepDebugSymbols += setOf(
                "**/libLightingLegacy64.so",
                "**/libPZClipperLegacy64.so",
                "**/libPZPathFindB4220.so",
                "**/libPZPopManB4220.so",
                "**/libPZPopManSaveCellBridge.so",
                "**/libMobileGLPZDefault.so"
            )
        }
        resources {
            // JavaSteam / protobuf / bouncycastle / kotlin bring duplicate metadata files.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/{AL2.0,LGPL2.1}",
                "**/*.proto"
            )
        }
    }
  ndkVersion = "27.3.13750724"
}

dependencies {
    implementation(libs.gson)
    implementation(files("jars/fmod.jar"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.commons.io)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.legacy.support.v4)

    // --- In-app Steam downloader (ported from RimDroid, MIT). JavaSteam = SteamKit2 port. ---
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("in.dragonbra:javasteam-depotdownloader:1.8.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")     // crypto provider JavaSteam needs
    implementation("com.google.protobuf:protobuf-java:4.31.1") // must match JavaSteam's protobuf
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")    // zstd depot-chunk decompression (arm64 .so)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

tasks.named("preBuild") {
    dependsOn(prepareOptLabAgentBundle)
    dependsOn(verifyJassimpDirectBundle)
}
