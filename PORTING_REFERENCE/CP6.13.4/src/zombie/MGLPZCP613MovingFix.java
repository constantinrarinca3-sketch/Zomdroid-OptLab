package zombie;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import mglpz.chunkagent.Profiler;
import zombie.characters.IsoZombie;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;

/** CP6.13 moving-object burst smoother.
 * Ordinary buckets execute the exact vanilla loop. If a bucket is still running after the bounded
 * wall-time budget, the unprocessed tail is deferred to that bucket's next scheduled turn; no
 * object is deleted and a rotating cursor prevents permanent tail starvation. postupdate is run
 * only for the subset that was actually updated when a partial batch occurred.
 */
public final class MGLPZCP613MovingFix {
    private static final boolean ENABLED=flag("mglpz.cp613.movingFix",true);
    private static final long BUDGET_NS=longPropMs("mglpz.cp613.movingBudgetMs",8,4,30)*1000000L;
    private static final int CHECK_EVERY=intProp("mglpz.cp613.movingCheckEvery",8,1,32);
    private static final int MIN_BOUNDED=intProp("mglpz.cp613.movingMinBucket",16,4,256);
    private static final boolean LEAN=flag("mglpz.cp6134.movingLean",true);
    private static final IdentityHashMap<Object,Batch> BATCHES=new IdentityHashMap<Object,Batch>();
    private static volatile Access access;private static volatile boolean initTried;
    private static long updateCalls,postCalls,objects,zombies,deadBodies,budgetStops,deferredObjects,maxBatchNs;
    private MGLPZCP613MovingFix(){}
    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    private static int intProp(String k,int d,int lo,int hi){int n=d;try{n=Integer.parseInt(System.getProperty(k,Integer.toString(d)));}catch(Throwable ignored){}return n<lo?lo:(n>hi?hi:n);}
    private static long longPropMs(String k,long d,long lo,long hi){long n=d;try{n=Long.parseLong(System.getProperty(k,Long.toString(d)));}catch(Throwable ignored){}return n<lo?lo:(n>hi?hi:n);}
    public static boolean enabled(){return ENABLED;}
    public static boolean tryUpdate(MovingObjectUpdateSchedulerUpdateBucket self,int frame){return runUpdate(self,frame);}
    public static boolean tryPostUpdate(MovingObjectUpdateSchedulerUpdateBucket self,int frame){return runPost(self,frame);}

    @SuppressWarnings("unchecked") private static boolean runUpdate(Object self,int frame){
        if(!enabled()||!Profiler.cp613GameplayReadyFast()||self==null)return false;Access a=access();if(a==null)return false;
        GameTime gt=GameTime.getInstance();if(gt==null)return false;
        try{
            int mod=a.frameMod.getInt(self);if(mod<=0)return false;Object arr=a.buckets.get(self);if(!(arr instanceof Object[]))return false;Object[] bs=(Object[])arr;if(bs.length<mod)return false;
            int bi=frame%mod;if(bi<0)return false;Object raw=bs[bi];if(!(raw instanceof List))return false;List<IsoMovingObject> list=(List<IsoMovingObject>)raw;
            int n=list.size();if(LEAN&&n<MIN_BOUNDED)return false;
            UpdateSchedulerSimulationLevel level=(UpdateSchedulerSimulationLevel)a.simulationLevel.get(self);Batch b=batch(self);b.frame=frame;b.selectedCount=0;b.partial=false;b.handledUpdate=true;
            gt.perObjectMultiplier=(float)mod;++updateCalls;
            if(n==0){b.cursor=0;return true;}
            final boolean bounded=n>=MIN_BOUNDED;final long start=bounded?System.nanoTime():0L;int startIndex=bounded?(b.cursor%n):0;int processed=0;
            for(int k=0;k<n;k++){
                int i=startIndex+k;if(i>=n)i-=n;IsoMovingObject obj=list.get(i);if(bounded)b.add(obj);++objects;++processed;
                updateOne(obj,level);
                if(bounded&&processed<n&&(processed%CHECK_EVERY)==0){long elapsed=System.nanoTime()-start;if(elapsed>=BUDGET_NS){b.partial=true;b.cursor=i+1;if(b.cursor>=n)b.cursor=0;++budgetStops;deferredObjects+=(n-processed);if(elapsed>maxBatchNs)maxBatchNs=elapsed;break;}}
            }
            if(!b.partial){b.cursor=0;if(bounded){long elapsed=System.nanoTime()-start;if(elapsed>maxBatchNs)maxBatchNs=elapsed;}}
            return true;
        }catch(RuntimeException e){throw e;}catch(Error e){throw e;}catch(Throwable t){throw new RuntimeException(t);}finally{gt.perObjectMultiplier=1.0f;}
    }

    @SuppressWarnings("unchecked") private static boolean runPost(Object self,int frame){
        if(!enabled()||!Profiler.cp613GameplayReadyFast()||self==null)return false;Access a=access();if(a==null)return false;GameTime gt=GameTime.getInstance();if(gt==null)return false;
        try{
            int mod=a.frameMod.getInt(self);if(mod<=0)return false;gt.perObjectMultiplier=(float)mod;++postCalls;Batch b=BATCHES.get(self);
            if(b!=null&&b.partial&&b.frame==frame){for(int i=0;i<b.selectedCount;i++)postOne(b.selected[i]);b.selectedCount=0;b.partial=false;b.handledUpdate=false;return true;}
            if(LEAN&&b!=null&&b.handledUpdate&&b.frame==frame){b.handledUpdate=false;return false;}
            Object arr=a.buckets.get(self);if(!(arr instanceof Object[]))return false;Object[] bs=(Object[])arr;if(bs.length<mod)return false;int bi=frame%mod;if(bi<0)return false;Object raw=bs[bi];if(!(raw instanceof List))return false;List<IsoMovingObject> list=(List<IsoMovingObject>)raw;
            for(int i=0;i<list.size();i++)postOne(list.get(i));return true;
        }catch(RuntimeException e){throw e;}catch(Error e){throw e;}catch(Throwable t){throw new RuntimeException(t);}finally{gt.perObjectMultiplier=1.0f;}
    }

    private static void updateOne(IsoMovingObject obj,UpdateSchedulerSimulationLevel level){
        if(obj instanceof IsoDeadBody){++deadBodies;Set<IsoMovingObject> rm=IsoWorld.instance.getCell().getRemoveList();rm.add(obj);return;}
        if(obj instanceof IsoZombie){++zombies;IsoZombie z=(IsoZombie)obj;if(VirtualZombieManager.instance.isReused(z)){DebugLog.log(DebugType.Zombie,"REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED "+String.valueOf(obj));return;}}
        obj.setCurrentSimulationLevel(level);obj.preupdate();obj.frameStep();obj.update();
    }
    private static void postOne(IsoMovingObject obj){
        if(obj instanceof IsoZombie){IsoZombie z=(IsoZombie)obj;if(VirtualZombieManager.instance.isReused(z)){DebugLog.log(DebugType.Zombie,"REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED "+String.valueOf(obj));return;}}
        obj.postupdate();
    }
    private static Batch batch(Object self){Batch b=BATCHES.get(self);if(b==null){b=new Batch();BATCHES.put(self,b);}return b;}
    public static String summary(){return "moving_update_calls="+updateCalls+" moving_post_calls="+postCalls+" moving_objects="+objects+" moving_zombies="+zombies+" moving_dead_bodies="+deadBodies+" moving_budget_stops="+budgetStops+" moving_deferred_objects="+deferredObjects+" moving_max_batch_ms="+(maxBatchNs/1000000.0)+" moving_budget_ms="+(BUDGET_NS/1000000L)+" moving_lean="+LEAN;}
    private static Access access(){Access a=access;if(a!=null)return a;if(initTried)return null;synchronized(MGLPZCP613MovingFix.class){if(access!=null)return access;if(initTried)return null;initTried=true;try{access=new Access();return access;}catch(Throwable t){Profiler.note("MGLPZ_CP6_13_GUARD_FAIL module=moving error="+t.getClass().getName()+" action=vanilla");return null;}}}
    private static final class Batch{int cursor,frame,selectedCount;boolean partial,handledUpdate;IsoMovingObject[] selected=new IsoMovingObject[64];void add(IsoMovingObject o){if(selectedCount==selected.length){IsoMovingObject[] n=new IsoMovingObject[selected.length<<1];System.arraycopy(selected,0,n,0,selected.length);selected=n;}selected[selectedCount++]=o;}}
    private static final class Access{final Field frameMod,buckets,simulationLevel;Access()throws Exception{Class<?>c=Class.forName("zombie.MovingObjectUpdateSchedulerUpdateBucket");frameMod=f(c,"frameMod");buckets=f(c,"buckets");simulationLevel=f(c,"simulationLevel");}static Field f(Class<?>c,String n)throws Exception{Field x=c.getDeclaredField(n);x.setAccessible(true);return x;}}
}
