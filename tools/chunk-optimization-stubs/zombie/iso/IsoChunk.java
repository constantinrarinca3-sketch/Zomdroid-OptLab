package zombie.iso;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.zones.Zone;
public class IsoChunk {
    public int wx, wy;
    public boolean isSpawnedRoom(long id) { return false; }
    public void addSpawnedRoom(long id) {}
    public FBORenderLevels getRenderLevels(int player) { return null; }
    public FBORenderCutaways.ChunkLevelData getCutawayDataForLevel(int z) { return null; }
    public IsoGridSquare getGridSquare(int x, int y, int z) { return null; }
}
