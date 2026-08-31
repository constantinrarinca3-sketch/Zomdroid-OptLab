package zombie;
public class GameProfiler {
  public static volatile boolean running;
  public int profileCalls;
  public static boolean isRunning() { return running; }
  public ProfileArea profile(String key){ profileCalls++; return running ? new ProfileArea(key) : null; }
  public static final class ProfileArea { public final String key; public ProfileArea(String k){key=k;} }
}
