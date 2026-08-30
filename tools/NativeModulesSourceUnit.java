import com.zomdroid.AppStorage;
import com.zomdroid.NativeModulesPreferences;
import com.zomdroid.game.GameInstance;
import com.zomdroid.patch.PathfindingNativeManager;
import com.zomdroid.patch.PathfindingWorkaround;
import com.zomdroid.patch.MobileGlDefaultRendererManager;
import com.zomdroid.patch.PopManNativeManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Host checks for persistent selection and fail-open behavior without Android or PZ binaries. */
public final class NativeModulesSourceUnit {
    public static void main(String[] args) throws Exception {
        require(args.length >= 1 && args.length <= 2,
                "packaged JNI directory and optional previous default arguments");
        File root = Files.createTempDirectory("zomdroid-native-modules").toFile();
        File home = new File(root, "instance");
        File game = new File(home, "game");
        require(game.mkdirs(), "game directory");
        require(new File(game, "projectzomboid.jar").createNewFile(), "placeholder jar");
        AppStorage.setLibraryPath(new File(args[0]).getAbsolutePath());
        File appHome = new File(root, "app-home");
        require(appHome.mkdirs(), "app home directory");
        AppStorage.setHomePath(appHome.getAbsolutePath());
        GameInstance instance = new GameInstance("42", home.getAbsolutePath(),
                game.getAbsolutePath());

        require(PathfindingWorkaround.selectPathfinder(instance, true), "native select write");
        require(option(home).contains("Pathfind.UseNativeCode=true"), "native select persisted");
        require(PathfindingWorkaround.selectPathfinder(instance, false), "java select write");
        require(option(home).contains("Pathfind.UseNativeCode=false"), "java select persisted");

        PathfindingNativeManager.Result result = PathfindingNativeManager.apply(instance,
                new NativeModulesPreferences(true, true, true, false));
        require(result.requested, "pathfinding requested");
        require(!result.available && !result.active, "invalid ABI must fail open");
        require(option(home).contains("Pathfind.UseNativeCode=false"),
                "failed gate must force Java fallback");

        PopManNativeManager.Result popMan = PopManNativeManager.apply(instance,
                new NativeModulesPreferences(true, true, false, true));
        require(popMan.requested, "PopMan requested");
        require(!popMan.available && !popMan.active,
                "unknown/placeholder Java ABI must keep PopMan on x86 fallback");

        testMobileGlDefault(appHome, new File(args[0], "libMobileGLPZDefault.so"),
                args.length > 1 ? new File(args[1]) : null);
        System.out.println("NATIVE_MODULES_SOURCE_UNIT PASS");
    }

    private static void testMobileGlDefault(File appHome, File packaged, File previousDefault)
            throws Exception {
        String packagedHash = sha256(packaged);
        require(packagedHash.equals(
                "f8c2851d9c3cbadc40c73f09c5ec9430a53a0e815cee3e2ec1392dcb4f9fa10a"),
                "MobileGL fixture identity");
        MobileGlDefaultRendererManager.Result installed =
                MobileGlDefaultRendererManager.ensureAvailable(true);
        require(installed.ready && "MGL12_DEFAULT_INSTALLED".equals(installed.mode),
                "MobileGL V1.2 install");
        File target = new File(appHome,
                "dependencies/libs/android-arm64-v8a/libMobileGLPZ.so");
        require(packagedHash.equals(sha256(target)), "MobileGL post-copy identity");

        MobileGlDefaultRendererManager.Result present =
                MobileGlDefaultRendererManager.ensureAvailable(true);
        require(present.ready && "MGL12_DEFAULT_PRESENT".equals(present.mode),
                "MobileGL V1.2 present");

        if (previousDefault != null) {
            require(previousDefault.isFile(), "previous MobileGL fixture exists");
            require(sha256(previousDefault).equals(
                    "8dc064f0386d01fccab8b94681921fdf9c304ed8995e87e07f1906119728310f"),
                    "previous MobileGL fixture identity");
            Files.copy(previousDefault.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            MobileGlDefaultRendererManager.Result upgraded =
                    MobileGlDefaultRendererManager.ensureAvailable(true);
            require(upgraded.ready && "MGL12_DEFAULT_UPGRADED".equals(upgraded.mode),
                    "previous default upgraded");
            require(packagedHash.equals(sha256(target)), "upgraded renderer identity");
            System.out.println("MOBILEGL_PREVIOUS_DEFAULT_UPGRADE_UNIT PASS");
        }

        byte[] custom = new byte[600 * 1024];
        custom[0] = 0x7f;
        custom[1] = 'E';
        custom[2] = 'L';
        custom[3] = 'F';
        custom[4] = 2;
        custom[5] = 1;
        custom[18] = (byte) 183;
        custom[19] = 0;
        Files.write(target.toPath(), custom);
        String customHash = sha256(target);
        MobileGlDefaultRendererManager.Result preserved =
                MobileGlDefaultRendererManager.ensureAvailable(true);
        require(preserved.ready && "CUSTOM_ARM64_PRESERVED".equals(preserved.mode),
                "custom renderer preserved");
        require(customHash.equals(sha256(target)), "custom renderer unchanged");

        Files.write(target.toPath(), new byte[64]);
        MobileGlDefaultRendererManager.Result recovered =
                MobileGlDefaultRendererManager.ensureAvailable(true);
        require(recovered.ready && "MGL12_DEFAULT_INSTALLED".equals(recovered.mode),
                "invalid renderer recovery");
        require(packagedHash.equals(sha256(target)), "recovered renderer identity");
        File[] backups = target.getParentFile().listFiles((dir, name) ->
                name.startsWith("libMobileGLPZ.so.zomdroid-r2-invalid"));
        require(backups != null && backups.length == 1, "invalid renderer backup");
    }

    private static String option(File home) throws Exception {
        return new String(Files.readAllBytes(
                new File(home, "Zomboid/debug-options.ini").toPath()), StandardCharsets.UTF_8);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file.toPath()));
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
