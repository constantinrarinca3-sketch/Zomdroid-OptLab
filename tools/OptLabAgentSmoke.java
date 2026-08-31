/** Host-only smoke test. Java 17 cannot execute PZ's Java 25 classes, but the agent must
 * still parse and transform them before the VM rejects their class-file version. */
public final class OptLabAgentSmoke {
    private static final String[] TARGETS = {
            "zombie.iso.WorldStreamer",
            "zombie.iso.WorldStreamer$ChunkComparator",
            "zombie.iso.fboRenderChunk.FBORenderLevels$NLevels",
            "zombie.pathfind.nativeCode.PathfindNative",
            "zombie.pathfind.nativeCode.PathfindNativeThread"
    };

    public static void main(String[] args) {
        for (String target : TARGETS) {
            try {
                Class.forName(target, false, OptLabAgentSmoke.class.getClassLoader());
                System.out.println("SMOKE loaded=" + target);
            } catch (UnsupportedClassVersionError expectedOnHostJdk17) {
                System.out.println("SMOKE transformed_then_host_rejected=" + target);
            } catch (Throwable error) {
                System.out.println("SMOKE failed=" + target + " error=" + error);
                error.printStackTrace(System.out);
            }
        }
    }
}
