package zombie.core.physics;

import java.lang.reflect.Field;
import mglpz.chunkagent.Profiler;
import zombie.GameTime;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/** CP6.13.3 conservative Bullet catch-up smoother.
 * Ordinary frames are exact vanilla. Catastrophic catch-up is smoothed for at most a small number
 * of consecutive frames; if debt persists, the next call falls through to exact vanilla so physics
 * cannot remain permanently behind gameplay. No accumulated simulation time is dropped.
 */
public final class MGLPZCP613PhysicsFix {
    private static final float STEP=0.01f;
    private static final boolean ENABLED=flag("mglpz.cp613.physicsFix",true);
    private static final int THRESHOLD=intProp("mglpz.cp613.physicsBurstThreshold",8,4,30);
    private static final int MAX_STEPS=intProp("mglpz.cp613.physicsMaxCatchupSteps",6,2,16);
    private static final int MAX_CONSECUTIVE=intProp("mglpz.cp613.physicsMaxConsecutiveBursts",2,1,4);
    private static volatile boolean initTried,initOk;
    private static Field fLocalTime,fPeriodSec,fBulletFrameNo,fTime;
    private static int consecutiveBursts;
    private static long calls,bursts,deferredSteps,executedSteps,forcedVanillaDrains,maxPending;
    private MGLPZCP613PhysicsFix(){}
    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    private static int intProp(String k,int d,int lo,int hi){int n=d;try{n=Integer.parseInt(System.getProperty(k,Integer.toString(d)));}catch(Throwable ignored){}return n<lo?lo:(n>hi?hi:n);}
    public static boolean enabled(){return ENABLED;}
    private static int threshold(){return THRESHOLD;}
    private static int maxSteps(){return MAX_STEPS;}
    private static int maxConsecutive(){return MAX_CONSECUTIVE;}

    public static boolean tryUpdate(WorldSimulation ws){
        if(!enabled()||!Profiler.cp613GameplayReadyFast()||ws==null){consecutiveBursts=0;return false;}
        ++calls;
        if(!ensureFields())return false;
        final GameTime gt=GameTime.instance;if(gt==null)return false;
        final float oldLocal;final int oldFrame;final long oldTime;
        try{oldLocal=fLocalTime.getFloat(ws);oldFrame=fBulletFrameNo.getInt(ws);oldTime=fTime.getLong(ws);}catch(Throwable t){return false;}
        final float delta=gt.getPhysicsSecondsSinceLastUpdate();
        final float total=oldLocal+delta;
        if(!(total>=STEP)){consecutiveBursts=0;return false;}
        final int pending=(int)(total/STEP);
        if(pending>maxPending)maxPending=pending;
        if(pending<=threshold()){consecutiveBursts=0;return false;} // exact vanilla ordinary/moderate catch-up

        // Never let smoothing create an unbounded debt chain. After N handled frames, exact vanilla
        // drains the backlog on this call. Returning false is pre-side-effect, so the renamed vanilla
        // method receives the untouched localTime and the same GameTime delta exactly once.
        if(consecutiveBursts>=maxConsecutive()){
            consecutiveBursts=0;++forcedVanillaDrains;return false;
        }

        final int run=Math.min(pending,maxSteps());
        if(run>=pending){consecutiveBursts=0;return false;} // no reason to replace vanilla if nothing is deferred
        final float remain=total-run*STEP;
        try{
            fLocalTime.setFloat(ws,remain);
            for(int i=0;i<run;i++){updateVehiclePhysics();Bullet.stepSimulation(STEP,0,0.0f);}
            fPeriodSec.setFloat(ws,run*STEP);fBulletFrameNo.setInt(ws,oldFrame+1);
            final long now=GameTime.getServerTimeMills();
            if(Math.abs(oldTime-now)>100L)fTime.setLong(ws,now);else fTime.setLong(ws,oldTime+10L*run);
            ++bursts;++consecutiveBursts;executedSteps+=run;deferredSteps+=(pending-run);return true;
        }catch(RuntimeException e){throw e;}catch(Error e){throw e;}catch(Throwable t){throw new RuntimeException("CP6.13.3 physics smoother failed after commit",t);}
    }

    private static void updateVehiclePhysics(){
        if(IsoWorld.instance==null||IsoWorld.instance.currentCell==null)return;
        for(BaseVehicle v:IsoWorld.instance.currentCell.getVehicles()){
            if(v==null)continue;v.applyAccumulatedImpulsesFromHitObjectsToPhysics();v.applyAllImpulsesFromProneCharacters();v.checkSurroundingChunks();
        }
    }
    private static boolean ensureFields(){
        if(initTried)return initOk;
        synchronized(MGLPZCP613PhysicsFix.class){if(initTried)return initOk;initTried=true;
            try{Class<?>c=WorldSimulation.class;fLocalTime=c.getDeclaredField("localTime");fLocalTime.setAccessible(true);fPeriodSec=c.getDeclaredField("periodSec");fPeriodSec.setAccessible(true);fBulletFrameNo=c.getDeclaredField("bulletFrameNo");fBulletFrameNo.setAccessible(true);fTime=c.getDeclaredField("time");fTime.setAccessible(true);initOk=true;}
            catch(Throwable t){initOk=false;Profiler.note("MGLPZ_CP6_13_GUARD_FAIL module=physics error="+t.getClass().getName()+" action=vanilla");}
            return initOk;}
    }
    public static String summary(){return "physics_calls="+calls+" physics_bursts="+bursts+" physics_deferred_steps="+deferredSteps+" physics_executed_steps="+executedSteps+" physics_forced_vanilla_drains="+forcedVanillaDrains+" physics_max_pending="+maxPending+" threshold="+threshold()+" cap="+maxSteps()+" max_consecutive="+maxConsecutive();}
}
