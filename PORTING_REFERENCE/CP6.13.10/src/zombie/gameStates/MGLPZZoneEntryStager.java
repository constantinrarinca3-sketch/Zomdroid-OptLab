package zombie.gameStates;
import mglpz.chunkagent.Optimizer;
import zombie.iso.MGLPZLoadPreDrain;
import zombie.vehicles.MGLPZVehicleMetaPreload;
public final class MGLPZZoneEntryStager {
 private static volatile boolean continueRequested;
 private MGLPZZoneEntryStager(){}
 public static void resetSession(){continueRequested=false;MGLPZLoadPreDrain.resetSession();MGLPZVehicleMetaPreload.resetSession();}
 public static GameStateMachine.StateAction adjustResult(GameStateMachine.StateAction original,boolean done){
  if(!Optimizer.zoneEntryPrestageFastEnabled()||!done)return original;
  boolean cp=MGLPZLoadPreDrain.service(); boolean mp=false; if(!cp)mp=MGLPZVehicleMetaPreload.service(); boolean pending=cp||mp;
  if(original==GameStateMachine.StateAction.Continue&&pending){continueRequested=true;return GameStateMachine.StateAction.Remain;}
  if(!pending&&continueRequested){continueRequested=false;return GameStateMachine.StateAction.Continue;}
  return original;
 }
}
