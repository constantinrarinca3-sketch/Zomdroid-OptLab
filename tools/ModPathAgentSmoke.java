import zombie.core.IndieFileLoader;
import zombie.scripting.ScriptManager;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** End-to-end host smoke test for both mod-path bytecode hooks installed by the javaagent. */
public final class ModPathAgentSmoke {
    public static void main(String[] args) throws Exception {
        String configured = System.getProperty("zomdroid.modpath.root", "");
        require(!configured.isEmpty(), "zomdroid.modpath.root is required");
        Path modsRoot = new File(configured).toPath();
        Path real = modsRoot.resolve(
                "AutoTsarTrailers/42.17/media/scripts/vehicles/templates/"
                        + "template_Earthing.txt");
        Files.createDirectories(real.getParent());
        Files.write(real, "agent-smoke-ok".getBytes(StandardCharsets.UTF_8));

        String broken = modsRoot + "/data/user/0/com.zomdroid.mglpz2/files/instances/"
                + "alta instanta aleasa/zomboid/mods/autotsartrailers/42.17/media/scripts/"
                + "vehicles/templates/template_earthing.txt";

        String advised = ScriptManager.LoadFile(broken);
        require(new File(advised).getCanonicalFile().equals(real.toFile().getCanonicalFile()),
                "ScriptManager.LoadFile argument advice did not repair the path");

        IndieFileLoader.pathBuiltInsideMethod = broken;
        try (BufferedReader reader = IndieFileLoader.getStreamReader("ignored-valid-input")) {
            require("agent-smoke-ok".equals(reader.readLine()),
                    "IndieFileLoader constructor-boundary hook opened the wrong file");
        }

        System.out.println("MODPATH_AGENT_SMOKE PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
