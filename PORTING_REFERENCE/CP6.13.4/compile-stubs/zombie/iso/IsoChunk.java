package zombie.iso;
import zombie.iso.zones.Zone;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
public final class IsoChunk {
  public int wx,wy,minLevel,maxLevel; public boolean loaded;
  public static final zombie.util.CappedConcurrentQueue<IsoChunk> loadGridSquare=null;
  public boolean isSpawnedRoom(long id){return false;} public void addSpawnedRoom(long id){} public void doLoadGridsquare(){}
  public FBORenderLevels getRenderLevels(int p){return null;}
  public FBORenderCutaways.ChunkLevelData getCutawayDataForLevel(int z){return null;}
  public IsoGridSquare getGridSquare(int x,int y,int z){return null;}
}
