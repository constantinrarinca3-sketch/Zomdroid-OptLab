package zombie.iso;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import mglpz.chunkagent.Profiler;

/** CP6.13 non-blocking LightingJNI gate.
 * Vanilla LightingJNI.update() joins checkLightsFuture when it exists. If that async job is still
 * running, this defers the whole update to a later game tick instead of blocking MainThread. Once
 * the future completes, vanilla update runs unchanged and consumes it.
 */
public final class MGLPZCP613LightingFix {
    private static final boolean ENABLED=flag("mglpz.cp613.lightingFix",true);
    private static volatile Field futureField;private static volatile boolean initTried,initOk;
    private static long calls,deferred,ready,maxPendingNs;private static long pendingSinceNs;
    private MGLPZCP613LightingFix(){}
    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    public static boolean enabled(){return ENABLED;}
    public static boolean shouldDefer(){
        if(!enabled()||!Profiler.cp613GameplayReadyFast())return false;++calls;if(!ensure())return false;
        try{
            Object o=futureField.get(null);if(!(o instanceof CompletableFuture)){pendingSinceNs=0L;return false;}
            CompletableFuture<?> f=(CompletableFuture<?>)o;
            if(f.isDone()){++ready;if(pendingSinceNs!=0L){long d=System.nanoTime()-pendingSinceNs;if(d>maxPendingNs)maxPendingNs=d;pendingSinceNs=0L;}return false;}
            if(pendingSinceNs==0L)pendingSinceNs=System.nanoTime();++deferred;return true;
        }catch(Throwable t){return false;}
    }
    private static boolean ensure(){if(initTried)return initOk;synchronized(MGLPZCP613LightingFix.class){if(initTried)return initOk;initTried=true;try{Class<?>c=Class.forName("zombie.iso.LightingJNI");futureField=c.getDeclaredField("checkLightsFuture");futureField.setAccessible(true);initOk=true;}catch(Throwable t){initOk=false;Profiler.note("MGLPZ_CP6_13_GUARD_FAIL module=lighting error="+t.getClass().getName()+" action=vanilla");}return initOk;}}
    public static String summary(){return "lighting_calls="+calls+" lighting_deferred="+deferred+" lighting_ready="+ready+" lighting_max_pending_ms="+(maxPendingNs/1000000.0);}
}
