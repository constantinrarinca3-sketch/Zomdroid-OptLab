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
    public static void launch(GameInstance gameInstance, Context context) throws ErrnoException {

        OptLabPreferences optLab = OptLabPreferences.from(context);
        String home = AppStorage.requireSingleton().getHomePath();
        LauncherPreferences.Renderer selectedRenderer =
                LauncherPreferences.requireSingleton().getRenderer();

        // Preserve a real custom renderer, otherwise install/upgrade to the audited MobileGL
        // OPT-LAB V3 022 binary packaged in this APK.
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
                com.zomdroid.patch.LightingArm64AbManager.installRequired(gameInstance);
        // Select safe native implementations after the class-level patches are known to be ready.
        com.zomdroid.patch.NativeLibraryWorkarounds.disableIncompleteNativeLibraries(gameInstance);
        if (selectedRenderer == LauncherPreferences.Renderer.MOBILEGL_PZCOMPAT
                && (!mobileGlRenderer.ready || !arm64Native.isComplete())) {
            throw new IllegalStateException("MOBILEGL_PZCOMPAT preflight failed; "
                    + mobileGlRenderer.machineReadable() + " " + arm64Native.machineReadable());
        }
        // Build 42.12+'s ARM64 PathFind implementation is under test after reports of characters
        // choosing incorrect interaction routes. Use PZ's own Java fallback without affecting
        // Build 41 or the older pre-fat-jar Build 42 releases.
        com.zomdroid.patch.PathfindingWorkaround.forceJavaPathfinderFor4212Plus(gameInstance);

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
                // control set; the user's environment field is applied later and can replace it.
                Os.setenv("MOBILEGL_BACKEND_TYPE", "DirectGLES", false);
                Os.setenv("MOBILEGL_RELAXED_SEMANTICS", "1", false);
                Os.setenv("MOBILEGL_PZ_OPT_SET", "019,020,021A,021B,021D", false);
                Os.setenv("MOBILEGL_LOG_FILE_PATH", optLab.isMobileGlFileLogEnabled()
                                ? AppStorage.requireSingleton().getHomePath() + "/mobilegl-pzcompat.log"
                                : "/dev/null",
                        true);
                Os.setenv("ZOMDROID_GLES_MAJOR", "3", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "2", false);
                Log.i("MGLPZ_GATE", "MobileGL 022 selected: DirectGLES, GLES 3.2, "
                        + "OPT_SET=019,020,021A,021B,021D");
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
        boolean pzJarGate = OptLabPreferences.EXPECTED_PZ_JAR_SHA256.equals(pzJarSha);
        String optLabSession = System.currentTimeMillis() + "-" + android.os.Process.myPid();
        File optProofFile = new File(home, "opt-proof.log");
        prepareOptProof(optProofFile, optLabSession, optLab.machineReadable());
        jvmArgs.add("-Dzomdroid.optlab.jar.sha256=" + pzJarSha);
        jvmArgs.add("-Dzomdroid.optlab.jar.gate=" + (pzJarGate ? "1" : "0"));
        jvmArgs.add("-Dzomdroid.optlab.session=" + optLabSession);
        jvmArgs.add("-Dzomdroid.optlab.proof.path=" + optProofFile.getAbsolutePath());

        // Correctness policy, independent from the experimental OPT-LAB master switch. PZ can
        // feed IndieFileLoader an already-absolute mod-script path after prefixing mods/ again and
        // lowercasing it. The internal agent receives the one authoritative, casing-preserving
        // root and only rewrites a path when the real target can be proven to exist below it.
        File modsRoot = new File(gameInstance.getHomePath(), "Zomboid/mods");
        jvmArgs.add("-Dzomdroid.modpath.fix=1");
        jvmArgs.add("-Dzomdroid.modpath.root=" + modsRoot.getAbsolutePath());
        jvmArgs.add("-Dnet.bytebuddy.experimental=true");
        if (new File(modsRoot, "data").exists()) {
            Log.w("ZD-MODPATH", "legacy shadow tree is still present; remove mods/data "
                    + "before the acceptance test");
        }

        jvmArgs.add("-Dzomdroid.optlab.pacing=" + boolArg(optLab.isMainloopPacing()));
        jvmArgs.add("-Dzomdroid.optlab.stream.wake=" + boolArg(optLab.isStreamWake()));
        jvmArgs.add("-Dzomdroid.optlab.stream.queue.fast="
                + boolArg(optLab.isStreamQueueFast()));
        jvmArgs.add("-Dzomdroid.optlab.stream.velocity.eta="
                + boolArg(optLab.isStreamVelocityEta()));
        jvmArgs.add("-Dzomdroid.optlab.fbo.dirty.dedup="
                + boolArg(optLab.isFboDirtyDedup()));
        jvmArgs.add("-Dzomdroid.optlab.fbo.frame.budget="
                + boolArg(optLab.isFboFrameBudget()));
        jvmArgs.add("-Dzomdroid.optlab.stream.fbo.coordinator="
                + boolArg(optLab.isStreamFboCoordinator()));
        jvmArgs.add("-Dzomdroid.optlab.fbo.budget=6");
        jvmArgs.add("-Dzomdroid.optlab.fbo.urgent.budget=2");
        jvmArgs.add("-Dzomdroid.optlab.fbo.max.defer.frames=2");
        if (optLab.isMainloopPacing()) {
            jvmArgs.add("-Dzomdroid.optlab.pacing.spin.us=150");
            jvmArgs.add("-Dzomdroid.optlab.pacing.max.park.us=500");
        }
        if (optLab.isAnyAgentOptimizationEnabled() && !pzJarGate) {
            Log.w("ZD-OPT-LAB", "agent optimizations blocked by PZ JAR identity gate: "
                    + pzJarSha);
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
        Log.i("ZD-OPT-LAB", "[ZD-OPT-LAB] pkg=" + BuildConfig.APPLICATION_ID
                + " version=" + BuildConfig.VERSION_NAME
                + " " + optLab.machineReadable()
                + " pzJarSha256=" + pzJarSha
                + " pzJarGate=" + (pzJarGate ? 1 : 0)
                + " optProofSession=" + optLabSession
                + " optProofPath=" + optProofFile.getAbsolutePath()
                + " rendererPath=" + rendererFile.getAbsolutePath()
                + " rendererBytes=" + (rendererFile.isFile() ? rendererFile.length() : -1)
                + " rendererSha256=" + sha256(rendererFile)
                + " " + arm64Native.machineReadable()
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

    private static void prepareOptProof(File file, String session, String settings) {
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
