package zombie.vehicles;
import java.util.concurrent.atomic.AtomicLong;
import mglpz.chunkagent.Optimizer;
import zombie.network.GameClient;
import zombie.network.GameServer;
public final class MGLPZVehicleMetaPreload {
 private static volatile boolean started,wsDone,mainDone,failed; private static volatile long startNs;
 private static final AtomicLong starts=new AtomicLong(),updateCalls=new AtomicLong(),duplicateSkips=new AtomicLong(),failures=new AtomicLong();
 private MGLPZVehicleMetaPreload(){}
 public static void resetSession(){started=false;wsDone=false;mainDone=false;failed=false;startNs=0L;}
 public static boolean service(){
  if(!Optimizer.zoneVehicleMetaPreloadFastEnabled()||failed)return false; if(GameClient.client||GameServer.server)return false;
  try{
   if(!started){startNs=System.nanoTime();started=true;starts.incrementAndGet();VehiclesDB2.instance.loadVehiclesInMeta();Optimizer.noteZoneMeta("kick",0L);return true;}
   if(mainDone)return false;
   if(System.nanoTime()-startNs>Optimizer.zoneVehicleMetaTimeoutNs()){failed=true;failures.incrementAndGet();Optimizer.noteZoneGuardFail("vehicle_meta_timeout",null);return false;}
   if(!wsDone)return true;
   long t=System.nanoTime();VehiclesDB2.instance.updateMain();updateCalls.incrementAndGet();Optimizer.noteZoneMeta("updateMain",System.nanoTime()-t);return !mainDone;
  }catch(Throwable t){failed=true;failures.incrementAndGet();Optimizer.noteZoneGuardFail("vehicle_meta_preload",t);return false;}
 }
 public static void markWorldStreamerDone(){if(started)wsDone=true;}
 public static void markMainDone(){if(started)mainDone=true;}
 public static boolean shouldSkipVanillaKick(){boolean s=Optimizer.zoneVehicleMetaPreloadFastEnabled()&&started&&mainDone&&!failed;if(s)duplicateSkips.incrementAndGet();return s;}
 public static String telemetry(){return " zone_meta_started="+started+" zone_meta_ws_done="+wsDone+" zone_meta_main_done="+mainDone+" zone_meta_failed="+failed+" zone_meta_starts="+starts.get()+" zone_meta_update_calls="+updateCalls.get()+" zone_meta_duplicate_skips="+duplicateSkips.get()+" zone_meta_failures="+failures.get();}
}
