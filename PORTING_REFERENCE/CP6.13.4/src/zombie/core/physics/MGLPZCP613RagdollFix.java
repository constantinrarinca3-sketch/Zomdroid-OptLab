package zombie.core.physics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import mglpz.chunkagent.Profiler;

/** CP6.13 adaptive ragdoll spike governor. First/normal samples stay vanilla. If one controller just
 * exceeded the slow threshold, only its next sample is deferred and the already-computed transform
 * is reused. Rollback is independent.
 */
public final class MGLPZCP613RagdollFix {
    private static final boolean ENABLED=flag("mglpz.cp613.ragdollFix",true);
    private static final long SLOW_NS=longPropMs("mglpz.cp613.ragdollSlowMs",20,10,100)*1000000L;
    private static final int SKIP_FRAMES=intProp("mglpz.cp613.ragdollSkipFrames",1,1,2);
    private static final IdentityHashMap<Object,Integer> cooldown=new IdentityHashMap<Object,Integer>();
    private static final IdentityHashMap<Object,Boolean> skippedThisUpdate=new IdentityHashMap<Object,Boolean>();
    private static volatile Access access;private static volatile boolean initTried;private static long calls,slowCalls,skips,maxNs;
    private MGLPZCP613RagdollFix(){}
    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    private static int intProp(String k,int d,int lo,int hi){int n=d;try{n=Integer.parseInt(System.getProperty(k,Integer.toString(d)));}catch(Throwable ignored){}return n<lo?lo:(n>hi?hi:n);}
    private static long longPropMs(String k,long d,long lo,long hi){long n=d;try{n=Long.parseLong(System.getProperty(k,Long.toString(d)));}catch(Throwable ignored){}return n<lo?lo:(n>hi?hi:n);}
    public static boolean enabled(){return ENABLED;}
    private static long slowNs(){return SLOW_NS;}
    private static int skipFrames(){return SKIP_FRAMES;}
    public static boolean shouldSkip(Object self,Object outPos,Object outRot){
        if(!enabled()||!Profiler.cp613GameplayReadyFast()||self==null||outPos==null||outRot==null)return false;++calls;Integer c=cooldown.get(self);if(c==null||c.intValue()<=0){skippedThisUpdate.remove(self);return false;}Access a=access();if(a==null)return false;
        try{if(!a.initialized.getBoolean(self))return false;Object p=a.worldPos.get(self),r=a.worldRot.get(self);if(p==null||r==null)return false;a.vecSet.invoke(outPos,p);a.quatSet.invoke(outRot,r);int left=c.intValue()-1;if(left<=0)cooldown.remove(self);else cooldown.put(self,Integer.valueOf(left));skippedThisUpdate.put(self,Boolean.TRUE);++skips;return true;}
        catch(Throwable t){cooldown.remove(self);skippedThisUpdate.remove(self);return false;}
    }
    public static void after(Object self,long durationNs){if(!enabled()||!Profiler.cp613GameplayReadyFast()||self==null)return;if(durationNs>maxNs)maxNs=durationNs;skippedThisUpdate.remove(self);if(durationNs>=slowNs()){cooldown.put(self,Integer.valueOf(skipFrames()));++slowCalls;}}
    public static boolean shouldSkipPost(Object self){if(!enabled()||self==null)return false;return skippedThisUpdate.remove(self)!=null;}
    public static String summary(){return "ragdoll_calls="+calls+" ragdoll_slow_calls="+slowCalls+" ragdoll_skips="+skips+" ragdoll_max_ms="+(maxNs/1000000.0)+" ragdoll_slow_ms="+(slowNs()/1000000L);}
    private static Access access(){Access a=access;if(a!=null)return a;if(initTried)return null;synchronized(MGLPZCP613RagdollFix.class){if(access!=null)return access;if(initTried)return null;initTried=true;try{access=new Access();return access;}catch(Throwable t){Profiler.note("MGLPZ_CP6_13_GUARD_FAIL module=ragdoll error="+t.getClass().getName()+" action=vanilla");return null;}}}
    private static final class Access{final Field initialized,worldPos,worldRot;final Method vecSet,quatSet;Access()throws Exception{Class<?>c=Class.forName("zombie.core.physics.RagdollController");initialized=f(c,"isInitialized");worldPos=f(c,"ragdollWorldPosition");worldRot=f(c,"ragdollWorldRotationPzBullet");Class<?>v=Class.forName("org.lwjgl.util.vector.Vector3f"),rv=Class.forName("org.lwjgl.util.vector.ReadableVector3f");vecSet=v.getMethod("set",rv);Class<?>q=Class.forName("org.lwjgl.util.vector.Quaternion"),rq=Class.forName("org.lwjgl.util.vector.ReadableVector4f");quatSet=q.getMethod("set",rq);}static Field f(Class<?>c,String n)throws Exception{Field x=c.getDeclaredField(n);x.setAccessible(true);return x;}}
}
