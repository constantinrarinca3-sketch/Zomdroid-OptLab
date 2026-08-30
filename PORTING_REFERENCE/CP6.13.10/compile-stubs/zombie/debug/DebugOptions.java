package zombie.debug;
public class DebugOptions {
  public static DebugOptions instance = new DebugOptions();
  public Checks checks = new Checks();
  public static class Checks { public BooleanDebugOption boundTextures = new BooleanDebugOption(); public BooleanDebugOption boundShader = new BooleanDebugOption(); }
}
