package zombie.core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Minimal host-only shape used to smoke-test the runtime agent transformer. */
public final class IndieFileLoader {
    public static String pathBuiltInsideMethod;

    private IndieFileLoader() {}

    public static BufferedReader getStreamReader(String ignored) throws IOException {
        // Intentionally ignore the advised argument. This proves the ASM guard immediately before
        // FileReader.<init> repairs a bad path constructed inside IndieFileLoader itself.
        return new BufferedReader(new FileReader(pathBuiltInsideMethod));
    }
}
