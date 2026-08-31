package zombie.iso.fboRenderChunk;

/** CP6.13.3 FBO correctness guard.
 * CP6.13.2 proved that returning early from prepareChunkForUpdating can leave permanent black
 * world holes because renderOneLevel may clear dirty state after an unprepared chunk.
 * Production mode therefore never suppresses a vanilla prepareChunkForUpdating call.
 * The helper is retained only for low-frequency pass accounting and future source-level APK work.
 */
public final class MGLPZCP613FBOFix {
    private static final boolean ENABLED=flag("mglpz.cp613.fboFix",true);
    private static long passes;
    private MGLPZCP613FBOFix(){}
    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    public static boolean enabled(){return ENABLED;}
    public static void beginPass(int player){
        if(enabled()&&mglpz.chunkagent.Profiler.cp613GameplayReadyFast())++passes;
    }
    public static String summary(){return "fbo_mode=vanilla_no_skip fbo_passes="+passes+" fbo_deferred_calls=0 fbo_starvation_guard=true";}
}
