package zombie;

public final class GameProfiler {
    public static volatile boolean running;
    public static boolean isRunning() { return running; }
    public ProfileArea profile(String key) { return running ? new ProfileArea(key) : null; }
    public static final class ProfileArea {
        public final String key;
        public ProfileArea(String value) { key = value; }
    }
}
