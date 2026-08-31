package zombie.debug;

public final class DebugOptions {
    public static DebugOptions instance = new DebugOptions();
    public Checks checks = new Checks();
    public static final class Checks {
        public BooleanDebugOption boundTextures = new BooleanDebugOption();
        public BooleanDebugOption boundShader = new BooleanDebugOption();
    }
}
