package zombie.scripting;

/** Minimal host-only shape used to smoke-test the LoadFile argument advice. */
public final class ScriptManager {
    private ScriptManager() {}

    public static String LoadFile(String path) {
        return path;
    }
}
