package zombie.iso.fboRenderChunk;

import mglpz.chunkagent.Profiler;
import zombie.core.textures.ColorInfo;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;

/** Exact B42.20.3 prepareChunkForUpdating inner-loop fastpath.
 * No dirty work is dropped or deferred.  The only changes are hoisting stable array references and
 * avoiding the duplicate getLightInfo(player) call when the method player equals frameState.player.
 */
public final class MGLPZCP6134FBOFast {
    private static final boolean ENABLED=flag("mglpz.cp6134.fboInnerLoop",true);
    private static long calls,hits,squares,lightReuse,flagsSet;
    private MGLPZCP6134FBOFast(){}
    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    public static boolean tryPrepareChunk(FBORenderCell self,int playerIndex,IsoChunk chunk,int z){
        ++calls;
        if(!ENABLED||!Profiler.cp613GameplayReadyFast())return false;
        if(chunk==null){++hits;return true;}
        final FBORenderLevels levels=chunk.getRenderLevels(playerIndex);
        if(!levels.isOnScreen(z)){++hits;return true;}
        final int min=levels.getMinLevel(z),max=levels.getMaxLevel(z);
        final int framePlayer=IsoCamera.frameState.playerIndex;
        for(int z2=min;z2<=max;z2++){
            final FBORenderCutaways.ChunkLevelData ld=chunk.getCutawayDataForLevel(z2);
            final byte[] flags=ld.squareFlags[playerIndex];
            for(int y=0;y<8;y++){
                final int row=y<<3;
                for(int x=0;x<8;x++){
                    final int idx=x+row;flags[idx]=0;++squares;
                    final IsoGridSquare sq=chunk.getGridSquare(x,y,z2);
                    if(sq==null)continue;
                    sq.cacheLightInfo();
                    final ColorInfo playerLight=sq.getLightInfo(playerIndex);
                    if(playerLight==null)continue;
                    final ColorInfo frameLight;
                    if(framePlayer==playerIndex){frameLight=playerLight;++lightReuse;}
                    else frameLight=sq.getLightInfo(framePlayer);
                    if(frameLight==null||sq.lighting[framePlayer]==null)continue;
                    if(!FBORenderCutaways.getInstance().shouldRenderBuildingSquare(framePlayer,sq))continue;
                    flags[idx]=(byte)(flags[idx]|1);++flagsSet;
                }
            }
        }
        ++hits;return true;
    }
    public static String summary(){return "fbo_inner="+ENABLED+" fbo_inner_calls="+calls+" fbo_inner_hits="+hits+" fbo_squares="+squares+" fbo_light_reuse="+lightReuse+" fbo_flags_set="+flagsSet;}
}
