package com.zomdroid;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.view.Surface;
import android.util.Log;

import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.input.InputControlsView;
import com.zomdroid.game.GameInstance;
import com.zomdroid.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;

public class GameLauncher {
    private static final String JASSIMP_DIRECT_SHA256 =
            "a73942ea3a4cdb25cd989661306151f35368af314e5e6d5b2fd20688f6609ac4";
    private static final String JASSIMP_R9_ROLLBACK_SHA256 =
            "095da5c4acb15cc43270c7f98bde1cf4f83e26abbc9c23bd3352fb97d7dbb849";

    public static void launch(GameInstance gameInstance, Context context) throws ErrnoException {

        OptLabPreferences optLab = OptLabPreferences.from(context);
        NativeModulesPreferences nativeModules = NativeModulesPreferences.from(context);
        String home = AppStorage.requireSingleton().getHomePath();
        LauncherPreferences.Renderer selectedRenderer =
                LauncherPreferences.requireSingleton().getRenderer();

        // Preserve a real custom renderer, otherwise install/upgrade to the audited MobileGL
        // PZCompat V1.2 present-fastpath ThinLTO binary packaged in this APK.
        com.zomdroid.patch.MobileGlDefaultRendererManager.Result mobileGlRenderer =
                com.zomdroid.patch.MobileGlDefaultRendererManager.ensureAvailable(
                        selectedRenderer == LauncherPreferences.Renderer.MOBILEGL_PZCOMPAT);

        // B42: make sure ShaderUnit.class carries the combineShaderSources patch (needed by
        // NG_GL4ES). Normally done at instance creation; doing it here too picks up instances
        // created by older launcher versions whose md5-table didn't know their game version.
        // Self-quenching: once the .bak exists this is a single stat call.
        com.zomdroid.patch.ShaderUnitPatchApplier.applyIfNeeded(gameInstance);
        // Also covers Build 42.20+ instances installed with an older launcher.
        com.zomdroid.patch.FmodLoadPatchApplier.applyIfNeeded(gameInstance);
        // Bink is only provided for x86_64 and cannot be loaded by the ARM64 HotSpot VM. Avoid a
        // full NoClassDefFoundError stack trace from UI panels on every rendered frame.
        com.zomdroid.patch.BinkVideoPatchApplier.applyIfNeeded(gameInstance);
        // Heal instances a previous launcher version stubbed: put the original LightingJNI.class
        // back so the emulated Linux Lighting (which really exports squareSetLightTransmission)
        // gets the native call instead of a leftover Java no-op. Running at launch covers already
        // installed instances without reinstalling the game.
        com.zomdroid.patch.LightingTransmissionPatchApplier.restoreOriginalIfStubbed(gameInstance);
        // R1 accidentally dropped the AB3 native Lighting/PZClipper route and immediately
        // disabled Lighting below. Restore the exact audited payloads before the generic native
        // workarounds run. MobileGL on the target device requires this path.
        com.zomdroid.patch.LightingArm64AbManager.InstallResult arm64Native =
                com.zomdroid.patch.LightingArm64AbManager.installRequired(
                        gameInstance, nativeModules);
        com.zomdroid.patch.PathfindingNativeManager.Result pathfindingNative =
                com.zomdroid.patch.PathfindingNativeManager.apply(gameInstance, nativeModules);
        // Select safe native implementations after the class-level patches are known to be ready.
        com.zomdroid.patch.NativeLibraryWorkarounds.disableIncompleteNativeLibraries(gameInstance);
        com.zomdroid.patch.PopManNativeManager.Result popManNative =
                com.zomdroid.patch.PopManNativeManager.apply(gameInstance, nativeModules);
        if (selectedRenderer == LauncherPreferences.Renderer.MOBILEGL_PZCOMPAT
                && (!mobileGlRenderer.ready || !arm64Native.isComplete())) {
            throw new IllegalStateException("MOBILEGL_PZCOMPAT preflight failed; "
                    + mobileGlRenderer.machineReadable() + " " + arm64Native.machineReadable());
        }
        // Re-apply the 42.13 case workaround against where this instance lives right now. The mod
        // aliases and the doubled path spell out an absolute location, so they go stale when an
        // instance is renamed or copied; this also reaches mods installed before any of it existed,
        // and sweeps the instance-level aliases b39a80a briefly shipped.
        com.zomdroid.patch.LowercasePathAliases.repair(gameInstance);

/*        // for debug
        Os.setenv("MESA_DEBUG", "1", false);
        Os.setenv("MESA_LOG_LEVEL", "debug", false);
        Os.setenv("ZINK_DEBUG", "validation", false);
        Os.setenv("mesa_glthread", "false", false);
        Os.setenv("GALLIUM_THREAD", "0", false);
        Os.setenv("VK_LOADER_DEBUG", "all", false);
        Os.setenv("VK_DEBUG", "all", false);
        Os.setenv("GALLIUM_DEBUG", "all", false);
        Os.setenv("VK_LOADER_LAYERS_ENABLE", "VK_LAYER_KHRONOS_validation", false);
        Os.setenv("BOX64_LOG", "3", false);
        Os.setenv("BOX64_DYNAREC", "0", false);*/

        //Os.setenv("LIBGL_NOERROR", "1", false);
        //Os.setenv("LIBGL_LOGSHADERERROR", "1", false);
        //Os.setenv("ZINK_DEBUG", "spirv", false);

        Os.setenv("LIBGL_MIPMAP", "1", false);

        boolean verboseNativeLogs = !optLab.isQuietRuntime() && (BuildConfig.DEBUG
                || LauncherPreferences.requireSingleton().isDebug());
        Os.setenv("BOX64_LOG", verboseNativeLogs ? "1" : "0", false);
        Os.setenv("BOX64_SHOWBT", verboseNativeLogs ? "1" : "0", false);
        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), false);

        // Emulate x86's Total Store Order for the emulated libraries. box64 defaults to no
        // barriers at all, which is fine for single-threaded code but breaks x86 code written
        // against TSO once ARM's weaker model is allowed to reorder. box64 itself force-enables
        // this combination for the multithreaded libraries it knows about (libjvm, libtbb,
        // MonoBleedingEdge - see box64 librarian/library.c); PZ's Lighting is multithreaded too
        // (it runs its own thread, "LightingFPS set to 15") but is not on that list.
        // Level 3 = barrier on every third guest store, the strongest setting; BIGBLOCK=0 stops
        // block merging from moving stores across the barriers.
        switch (optLab.getBox64Policy()) {
            case LEVEL1_0:
                Os.setenv("BOX64_DYNAREC_STRONGMEM", "1", true);
                Os.setenv("BOX64_DYNAREC_BIGBLOCK", "0", true);
                break;
            case DEFAULT:
                Os.unsetenv("BOX64_DYNAREC_STRONGMEM");
                Os.unsetenv("BOX64_DYNAREC_BIGBLOCK");
                break;
            case LEGACY_3_0:
            default:
                Os.setenv("BOX64_DYNAREC_STRONGMEM", "3", true);
                Os.setenv("BOX64_DYNAREC_BIGBLOCK", "0", true);
                break;
        }

        Os.setenv("ZOMDROID_STDIO_MODE", optLab.getStdioMode().name(), true);
        Os.setenv("ZOMDROID_SURFACE_MODE", optLab.getSurfaceMode().name(), true);
        Os.setenv("ZOMDROID_INPUT_QUEUE", optLab.getInputQueueMode().name(), true);
        Os.setenv("ZOMDROID_INPUT_ANALOG_FILTER", optLab.isAnalogFilter() ? "1" : "0", true);
        Os.setenv("ZOMDROID_INPUT_COALESCE", optLab.isInputCoalesce() ? "1" : "0", true);

        Os.setenv("GALLIUM_DRIVER", "zink", false);

        Os.setenv("ZOMDROID_CACHE_DIR", AppStorage.requireSingleton().getCachePath(), false);
        Os.setenv("ZOMDROID_RENDERER", selectedRenderer.name(), false);
        switch (selectedRenderer) {
            case ZINK_ZFA:
            case ZINK_OSMESA:
                String vulkanDriverName = LauncherPreferences.requireSingleton().getVulkanDriver().libName;
                if (vulkanDriverName != null) {
                    Os.setenv("ZOMDROID_VULKAN_DRIVER_NAME", vulkanDriverName, false);
                }
                break;
            case NG_GL4ES: {
                //Os.setenv("LIBGL_ES", "3", true);
                //Os.setenv("LIBGL_GL", "21", true); // если нужен OpenGL 2.1 для движка
                //Os.setenv("LIBGL_NOBANNER", "0", true);
                //Os.setenv("LIBGL_SILENTSTUB", "0", true); // если хотите убрать шум
                //Os.setenv("LIBGL_FB", "2", true);
                //Os.setenv("LIBGL_FBONOALPHA", "1", true);
                //Os.setenv("LIBGL_SIMPLE_SHADERCONV", "1", true);
                //Os.setenv("LIBGL_DBGSHADERCONV", "15", true);
                // Force SPIRV-Cross path instead of old ConvertShader
                // Without this, esversion stays 200 and shaders go through
                // the old converter that doesn't understand modern GLSL
                //Os.setenv("LIBGL_VGPU_FORCE", "1", true);
                //Os.setenv("LIBGL_VGPU_PRECISION", "1", true);
                // The DECISIVE knob is the real EGL context version, not the GL version the game
                // sees. On a true ES3 context Mali runs NG's internal ES3 paths, which
                // deterministically kill box64/physics at Bullet.init; a 2.1 context yields Mali's
                // ES2 profile and clean ES2 paths (proven playable). So: Qualcomm/Adreno -> ES3.2
                // context, everyone else -> ES2.1 context. We own the context; the lib (RC13+) owns
                // the badge and picks it from the actual context — do NOT set LIBGL_GL here, it
                // would override the lib's decision. override=false keeps manual env overrides.
                // Memory saver (Settings → Advanced): live-texture budget in MB. Past this
                // threshold NG_GL4ES loads new large textures at half resolution — caps runaway
                // texture memory at the cost of tile detail. Unset = 0 = the mechanism sleeps.
                // override=false so a manual LIBGL_TEXBUDGET in the env-vars field still wins.
                if (LauncherPreferences.requireSingleton().isMemorySaver()) {
                    Os.setenv("LIBGL_TEXBUDGET", "800", false);
                }
                boolean isQualcomm = isQualcommGpu();
                Os.setenv("ZOMDROID_GLES_MAJOR", isQualcomm ? "3" : "2", false);
                Os.setenv("ZOMDROID_GLES_MINOR", isQualcomm ? "2" : "1", false);
                Os.setenv("LIBGL_ES", "2", false);
                Os.setenv("LIBGL_MIPMAP", "1", false);
                Os.setenv("LIBGL_LOGSHADERERROR", "1", false);
                Os.setenv("LIBGL_VGPU_DUMP", "1", false);
                // DEBUG: red-clear bisection — disabled now that swap/context are
                // confirmed alive; uncomment to mask frames again if needed.
                //Os.setenv("ZOMDROID_DEBUG_RED_CLEAR", "1", false);
                break;
            }
            case MOBILEGL_PZCOMPAT: {
                // Restore the exact working P1 ownership model: MobileGL owns both EGL and GL,
                // while DirectGLES talks to the system Adreno driver.  Start 022 on its exercised
                // V1.2 recommended control set; the user's environment field is applied later
                // and can replace every value below.
                String mobileGlHome = AppStorage.requireSingleton().getHomePath();
                Os.setenv("MOBILEGL_BACKEND_TYPE", "DirectGLES", false);
                Os.setenv("MOBILEGL_RELAXED_SEMANTICS", "1", false);
                Os.setenv("MOBILEGL_PZ_PRESENT_FASTPATH", "1", false);
                Os.setenv("MOBILEGL_PZ_OPT_SET", "002,003", false);
                // Override the binary's package-specific fallback paths so both install flavors
                // use their own sandbox and never try to write into another application package.
                Os.setenv("MOBILEGL_PZ_FILES_DIR", mobileGlHome, false);
                Os.setenv("MOBILEGL_PZ_OPT_FILE", mobileGlHome + "/mglpz-opt-set.txt", false);
                Os.setenv("MOBILEGL_PZ_PROOF_FILE", mobileGlHome + "/mglpz-opt-proof.log", false);
                Os.setenv("MOBILEGL_PZ_ETC2_CACHE_DIR",
                        mobileGlHome + "/mglpz-etc2-cache-v1", false);
                Os.setenv("MOBILEGL_PZ_PROGRAM_CACHE_DIR",
                        mobileGlHome + "/mglpz-program-cache-v1", false);
                Os.setenv("MOBILEGL_PZ_SHADER_SOURCE_CACHE_DIR",
                        mobileGlHome + "/mglpz-essl-cache-v1", false);
                Os.setenv("MOBILEGL_LOG_FILE_PATH", optLab.isMobileGlFileLogEnabled()
                                ? AppStorage.requireSingleton().getHomePath() + "/mobilegl-pzcompat.log"
                                : "/dev/null",
                        true);
                Os.setenv("ZOMDROID_GLES_MAJOR", "3", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "2", false);
                Log.i("MGLPZ_GATE", "MobileGL PZCompat V1.2 selected: DirectGLES, "
                        + "GLES 3.2, PRESENT_FASTPATH=1, OPT_SET=002,003");
                break;
            }
            default: {
                Os.setenv("ZOMDROID_GLES_MAJOR", "2", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "1", false);
                break;
            }
        }

        Os.setenv("ZOMDROID_AUDIO_API", LauncherPreferences.requireSingleton().getAudioAPI().name(), false);

        if (BuildConfig.DEBUG && !optLab.isQuietRuntime()) {
            //for debugging GL calls, only supported on GL ES 3.2+ with GL_KHR_debug extension present
            Os.setenv("LIBGL_STACKTRACE","1", false);
            Os.setenv("LIBGL_LOGSHADERERROR","1", false);
        }
        initZomdroidWindow();
        InputNativeInterface.sendJoystickConnected();

        // JVM args [variables] from user settings
        ArrayList<String> jvmArgs = gameInstance.getJvmArgsAsList();
        String rawArgs = LauncherPreferences.requireSingleton().getJvmArgs();

        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            String[] splitArgs = rawArgs.trim().split("\\s+");
            for (String arg : splitArgs) {
                jvmArgs.add(arg);
            }
        }
        // The instance and free-form field survive APK upgrades.  Never let an old OPT-LAB
        // property compete with the current preference snapshot; OFF must be authoritative too.
        OptLabLaunchContract.clearManagedProperties(jvmArgs);
        if (optLab.isQuietRuntime()) {
            for (Iterator<String> it = jvmArgs.iterator(); it.hasNext();) {
                String value = it.next();
                if (value.startsWith("-Dorg.lwjgl.util.Debug=")
                        || value.startsWith("-Dorg.lwjgl.util.DebugLoader=")
                        || value.equals("-XX:+PrintFlagsFinal")) {
                    it.remove();
                }
            }
        }

        // Environment variables from user settings
        String rawEnvVars = LauncherPreferences.requireSingleton().getEnvVars();
        if (rawEnvVars != null && !rawEnvVars.trim().isEmpty()) {
            for (String token : rawEnvVars.trim().split("\\s+")) {
                String[] parts = token.split("=", 2);
                if (parts.length == 2) {
                    Os.setenv(parts[0].trim(), parts[1].trim(), true);
                }
            }
        }

        jvmArgs.add("-Dorg.lwjgl.opengl.libname=" + LauncherPreferences.requireSingleton().getRenderer().libName);
        jvmArgs.add("-Dzomdroid.renderer=" + LauncherPreferences.requireSingleton().getRenderer().name());

        String pzJarSha = sha256(new File(gameInstance.getGamePath(), "projectzomboid.jar"));
        OptLabFeatureRegistry.Snapshot compatibility = OptLabFeatureRegistry.evaluate(
                gameInstance, optLab, nativeModules, pzJarSha, arm64Native.lightingActive,
                arm64Native.clipperActive, pathfindingNative.active, popManNative.active);
        compatibility.log();
        String optLabSession = System.currentTimeMillis() + "-" + android.os.Process.myPid();
        File optProofFile = new File(home, "opt-proof.log");
        prepareOptProof(optProofFile, optLabSession, optLab.machineReadable(), compatibility,
                pathfindingNative, popManNative);
        compatibility.addAgentIdentityProperties(jvmArgs);
        compatibility.addAgentFeatureProperties(jvmArgs);
        pathfindingNative.addAgentProperties(jvmArgs);
        popManNative.addAgentProperties(jvmArgs);
        OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.session", optLabSession);
        OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.proof.path",
                optProofFile.getAbsolutePath());
        OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.fbo.budget", "6");
        OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.fbo.urgent.budget", "2");
        OptLabLaunchContract.putProperty(jvmArgs,
                "zomdroid.optlab.fbo.max.defer.frames", "2");
        if (optLab.isMainloopPacing()) {
            OptLabLaunchContract.putProperty(jvmArgs,
                    "zomdroid.optlab.pacing.spin.us", "150");
            OptLabLaunchContract.putProperty(jvmArgs,
                    "zomdroid.optlab.pacing.max.park.us", "500");
        }
        boolean agentWorkRequested = optLab.isAnyAgentOptimizationEnabled()
                || pathfindingNative.active || popManNative.active;
        if (agentWorkRequested) {
            jvmArgs.add("-Dnet.bytebuddy.experimental=true");
        }
        if (agentWorkRequested && !compatibility.isBuild42()) {
            if (compatibility.isOnlyBuild42()) {
                Log.w("ZD-OPT-LAB", "Build 42 agent features blocked for build family "
                        + gameInstance.getBuildVersion() + " by Only Build 42 policy");
            } else {
                Log.w("ZD-OPT-LAB", "Cross-family agent probes requested for build family "
                        + gameInstance.getBuildVersion()
                        + "; each mechanism must pass its own structural gate");
            }
        }

        if (optLab.isSqliteAndroidNative()) {
            jvmArgs.add("-Dorg.sqlite.lib.path=" + AppStorage.requireSingleton().getHomePath()
                    + "/" + C.deps.LIBS_ANDROID_ARM64_v8a);
            jvmArgs.add("-Dorg.sqlite.lib.name=libsqlitejdbc.so");
        }

        if (BuildConfig.DEBUG && !optLab.isQuietRuntime()) {
            jvmArgs.add("-Dorg.lwjgl.util.Debug=true"); //print LWJGL library errors
            //jvmArgs.add("-Dorg.lwjgl.util.DebugLoader=true");
            jvmArgs.add("-XX:+PrintFlagsFinal"); // for debugging
        }

        jvmArgs.add("-XX:ErrorFile=/dev/stdout"); // print jvm crash report to stdout for now


        ArrayList<String> args = gameInstance.getArgsAsList();
        if (BuildConfig.DEBUG && !optLab.isQuietRuntime()) {
            //args.add("-debug");
            //args.add("-debuglog=Shader");
        }
        Log.i("Zomdroid", "JVM ARGS: " + jvmArgs);
        Log.i("Zomdroid", "GAME ARGS: " + args);

        if (!optLab.isQuietRuntime()
                && (BuildConfig.DEBUG || LauncherPreferences.requireSingleton().isDebug())) {
            args.add("-debug");
        }

        if (!optLab.isQuietRuntime()
                && LauncherPreferences.requireSingleton().getRenderer() == LauncherPreferences.Renderer.NG_GL4ES) {
            args.add("-debuglog=Shader");
        }

        //String javaHomePath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE;
        // Prefer JRE21 when using GL4ES-style renderers (Build 41 tends to rely on that path).
        // This isolates "old GL4ES pipeline" from "new Java 25 runtime" regressions.
        boolean preferJre21ForRenderer = isLegacyRendererNeedingJre21(LauncherPreferences.requireSingleton().getRenderer());
        // ZombieBuddy agent — loaded if jar present in game folder AND enabled in settings
        android.content.SharedPreferences zbPrefs = LauncherPreferences.requireSingleton().getSharedPrefs();

        String instanceName = gameInstance.getName();
        String zombieBuddyPath = gameInstance.getGamePath() + "/" + C.deps.ZOMBIE_BUDDY_JAR;
        boolean zombieBuddyEnabled = zbPrefs.getBoolean("zombiebuddy_enabled_" + instanceName, false);
        if (new File(zombieBuddyPath).exists() && zombieBuddyEnabled) {
            jvmArgs.add("-javaagent:" + zombieBuddyPath + "=policy=allow-all");
            jvmArgs.add("-Dnet.bytebuddy.processor=ASM_ONLY");
            jvmArgs.add("-Dnet.bytebuddy.experimental=true");
            // On JRE25 (ZINK) we do NOT set classfile.version — ByteBuddy must handle
            if (!preferJre21ForRenderer) {
                Log.i("ZombieBuddy", "in GameLauncher ZINK loaded, ZombieBuddy loaded.");
                //jvmArgs.add("-Dnet.bytebuddy.classfile.version=65");
                //jvmArgs.add("-Dnet.bytebuddy.unsupported.classfile.version=69");
            }
        }

        // Try to use dedicated folders if present (jre21 / jre25). If not present, fall back to C.deps.JRE.
        String jreFolder = preferJre21ForRenderer ? C.deps.JRE_21 : C.deps.JRE_25;
        String candidateJavaHomePath = home + "/" + jreFolder;
        String javaHomePath;

        if (new File(candidateJavaHomePath).exists()) {
            javaHomePath = candidateJavaHomePath;
        } else {
            // fallback for setups that still package only one JRE folder (legacy behavior)
            javaHomePath = home + "/" + C.deps.JRE_ROOT;
        }
        if (BuildConfig.DEBUG) {
            Log.i("Zomdroid", "jreFolder: " + jreFolder+", candidateJavaHomePath: "+candidateJavaHomePath+", javaHomePath: "+javaHomePath);
        }
        String ldLibraryPath = AppStorage.requireSingleton().getLibraryPath() + ":/system/lib64:"
                + javaHomePath + "/lib:" + javaHomePath + "/lib/server:" + gameInstance.getJavaLibraryPath();
        File rendererFile = new File(AppStorage.requireSingleton().getHomePath() + "/"
                + C.deps.LIBS_ANDROID_ARM64_v8a + "/"
                + LauncherPreferences.requireSingleton().getRenderer().libName);
        // CP3 is a direct Assimp/JAssimp fix, not another Java transformer. java.library.path puts
        // this bundled ARM64 directory before the game working directory, so record the exact
        // extracted payload that Jassimp.loadLibrary("jassimp64") is expected to resolve. This is
        // diagnostic only and deliberately does not pre-load the library from Android's app class
        // loader (doing that would bind it to the wrong ClassLoader before PZ starts).
        File jassimpFile = new File(AppStorage.requireSingleton().getHomePath() + "/"
                + C.deps.LIBS_ANDROID_ARM64_v8a + "/libjassimp64.so");
        String jassimpSha = sha256(jassimpFile);
        String jassimpMode;
        if (JASSIMP_DIRECT_SHA256.equals(jassimpSha)) {
            jassimpMode = "DIRECT_A53_COMPAT";
        } else if (JASSIMP_R9_ROLLBACK_SHA256.equals(jassimpSha)) {
            jassimpMode = "ROLLBACK_UNPATCHED";
        } else if ("MISSING".equals(jassimpSha)) {
            jassimpMode = "MISSING_FALLBACK";
        } else {
            jassimpMode = "UNKNOWN_FALLBACK";
        }
        jvmArgs.add("-Dzomdroid.jassimp.mode=" + jassimpMode);
        jvmArgs.add("-Dzomdroid.jassimp.sha256=" + jassimpSha);
        // This is deliberately the last validation before JNI receives the array.  A saved ON
        // value, a duplicate free-form -D flag, or any later launcher mutation must not make an
        // explicit OFF ambiguous inside the new game JVM.
        OptLabLaunchContract.requireUniqueManagedProperties(jvmArgs);
        compatibility.requireAgentFeatureProperties(jvmArgs);
        OptLabLaunchContract.requireValue(jvmArgs,
                "zomdroid.native.pathfinding.requested",
                pathfindingNative.requested ? "1" : "0");
        OptLabLaunchContract.requireValue(jvmArgs,
                "zomdroid.native.pathfinding.active",
                pathfindingNative.active ? "1" : "0");
        OptLabLaunchContract.requireValue(jvmArgs,
                "zomdroid.native.popman.requested", popManNative.requested ? "1" : "0");
        OptLabLaunchContract.requireValue(jvmArgs,
                "zomdroid.native.popman.active", popManNative.active ? "1" : "0");
        Log.i("ZD-OPT-LAB", "[ZD-OPT-LAB] pkg=" + BuildConfig.APPLICATION_ID
                + " version=" + BuildConfig.VERSION_NAME
                + " " + optLab.machineReadable()
                + " " + nativeModules.machineReadable()
                + " " + compatibility.identityMachineReadable()
                + " optProofSession=" + optLabSession
                + " optProofPath=" + optProofFile.getAbsolutePath()
                + " rendererPath=" + rendererFile.getAbsolutePath()
                + " rendererBytes=" + (rendererFile.isFile() ? rendererFile.length() : -1)
                + " rendererSha256=" + sha256(rendererFile)
                + " jassimpMode=" + jassimpMode
                + " jassimpPath=" + jassimpFile.getAbsolutePath()
                + " jassimpBytes=" + (jassimpFile.isFile() ? jassimpFile.length() : -1)
                + " jassimpSha256=" + jassimpSha
                + " " + arm64Native.machineReadable()
                + " " + pathfindingNative.machineReadable()
                + " " + popManNative.machineReadable()
                + " box64Strongmem=" + envOrUnset("BOX64_DYNAREC_STRONGMEM")
                + " box64Bigblock=" + envOrUnset("BOX64_DYNAREC_BIGBLOCK"));
        //Log.d("zomdroid-main", ldLibraryPath);
        GameLauncher.startGame(gameInstance.getGamePath(), ldLibraryPath, jvmArgs.toArray(new String[0]),
                gameInstance.getMainClassName(), args.toArray(new String[0]));
    }

    private static String envOrUnset(String name) {
        String value = Os.getenv(name);
        return value == null ? "UNSET" : value;
    }

    private static String boolArg(boolean value) {
        return value ? "1" : "0";
    }

    private static void prepareOptProof(File file, String session, String settings,
                                        OptLabFeatureRegistry.Snapshot compatibility,
                                        com.zomdroid.patch.PathfindingNativeManager.Result
                                                pathfindingNative,
                                        com.zomdroid.patch.PopManNativeManager.Result
                                                popManNative) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("cannot create proof directory");
            }
            String header = "[ZD-OPT-PROOF] session=" + session
                    + " mechanism=launcher state=requested detail="
                    + settings.replace(' ', '_') + "\n";
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(header.getBytes(StandardCharsets.UTF_8));
                output.write(compatibility.proofLines(session).getBytes(StandardCharsets.UTF_8));
                output.write(pathfindingNative.proofLines(session)
                        .getBytes(StandardCharsets.UTF_8));
                output.write(popManNative.proofLines(session)
                        .getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (Throwable error) {
            Log.w("ZD-OPT-LAB", "Cannot initialize OPT_PROOF at " + file, error);
        }
    }

    private static String sha256(File file) {
        if (file == null || !file.isFile()) return "MISSING";
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            Log.w("ZD-OPT-LAB", "Cannot hash " + file, error);
            return "ERROR";
        }
    }

    private static boolean isLegacyRendererNeedingJre21(LauncherPreferences.Renderer r) {
        // NG_GL4ES dropped from this list on purpose: it is being tested against JRE25 (Java 25),
        // which is also what Build 42.12+ requires. Only stock GL4ES stays pinned to JRE21.
        boolean result = (r == LauncherPreferences.Renderer.GL4ES);

        if (BuildConfig.DEBUG) {
            Log.i("Zomdroid", "isLegacyRendererNeedingJre21: " + result + ", Renderer: " + r.name());
        }
        return result;
    }

    // Positive-ID Qualcomm/Adreno only (they tolerate the ES3 EGL context). Everything else —
    // MediaTek/Mali, and any unknown, to stay safe — returns false so NG gets the ES2 context.
    // Same GPU split RC13 uses on the lib side; sourced from Android-level info that the GL
    // stack can't hide (GL_RENDERER comes back '<unknown>' through box64/Krypton).
    private static boolean isQualcommGpu() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.toLowerCase(java.util.Locale.ROOT);
                if (l.contains("qualcomm") || l.contains("snapdragon")) return true;
                if (l.contains("mediatek") || l.contains("dimensity") || l.contains("helio")) return false;
            }
        } catch (Exception ignored) {}

        String[] fields;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            fields = new String[]{android.os.Build.HARDWARE, android.os.Build.BOARD,
                    android.os.Build.SOC_MODEL, android.os.Build.SOC_MANUFACTURER};
        } else {
            fields = new String[]{android.os.Build.HARDWARE, android.os.Build.BOARD};
        }
        for (String f : fields) {
            if (f == null) continue;
            String l = f.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("qcom") || l.contains("qualcomm") || l.contains("snapdragon")) return true;
        }
        return false;
    }

    public static native int initZomdroidWindow();
    public static native void destroyZomdroidWindow();
    public static native int setSurface(Surface surface, int width, int height, float refreshRate);
    public static native void destroySurface();
    static native void startGame(String gameDirPath, String libraryDirPath, String[] jvmArgs, String mainClassName, String[] args);
}
