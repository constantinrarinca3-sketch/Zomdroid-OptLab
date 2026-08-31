package zombie.iso;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import mglpz.chunkagent.Optimizer;
import zombie.characters.IsoPlayer;
public final class MGLPZLoadPreDrain {
 private static final AtomicLong ticks=new AtomicLong(), chunks=new AtomicLong(), budgetStops=new AtomicLong(), maxBatchNs=new AtomicLong(), guardFails=new AtomicLong();
 private static volatile boolean sessionFailed;
 private MGLPZLoadPreDrain(){}
 public static void resetSession(){sessionFailed=false;}
 public static boolean service(){
  if(!Optimizer.zoneChunkPreDrainFastEnabled()||sessionFailed)return false;
  try{
   WorldStreamer ws=WorldStreamer.instance;
   if(ws==null||ws.isBusy())return true;
   IsoWorld w=IsoWorld.instance;
   if(w==null||w.currentCell==null||w.currentCell.chunkMap==null||w.currentCell.chunkMap.length==0||w.currentCell.chunkMap[0]==null)return true;
   int before=IsoChunk.loadGridSquare.size(); if(before<=0)return false;
   long start=System.nanoTime(), budget=Optimizer.zoneChunkBudgetNs(); int max=Optimizer.zoneChunkMaxPerTick(), done=0;
   do{ IsoChunk c=IsoChunk.loadGridSquare.poll(); if(c==null)break; processOne(c); done++; if(done>=max||System.nanoTime()-start>=budget)break; }while(true);
   long dur=System.nanoTime()-start; ticks.incrementAndGet(); chunks.addAndGet(done); updateMax(maxBatchNs,dur); int after=IsoChunk.loadGridSquare.size(); if(after>0)budgetStops.incrementAndGet(); Optimizer.noteZoneChunkBatch(before,after,done,dur); return after>0;
  }catch(Throwable t){sessionFailed=true;guardFails.incrementAndGet();Optimizer.noteZoneGuardFail("chunk_pre_drain",t);return false;}
 }
 private static void processOne(IsoChunk chunk){ ReentrantLock lock=IsoChunkMap.bSettingChunk; lock.lock(); try{ boolean accepted=false; for(int i=0;i<IsoPlayer.numPlayers;i++){ IsoChunkMap m=IsoWorld.instance.currentCell.chunkMap[i]; if(!m.ignore&&m.setChunkDirect(chunk,false))accepted=true;} if(!accepted)WorldReuserThread.instance.addReuseChunk(chunk); else chunk.doLoadGridsquare(); }finally{lock.unlock();} }
 private static void updateMax(AtomicLong a,long v){long p;do{p=a.get();if(v<=p)return;}while(!a.compareAndSet(p,v));}
 public static String telemetry(){return " zone_chunk_ticks="+ticks.get()+" zone_chunk_processed="+chunks.get()+" zone_chunk_budget_stops="+budgetStops.get()+" zone_chunk_max_batch_us="+(maxBatchNs.get()/1000L)+" zone_chunk_guard_fails="+guardFails.get();}
}
