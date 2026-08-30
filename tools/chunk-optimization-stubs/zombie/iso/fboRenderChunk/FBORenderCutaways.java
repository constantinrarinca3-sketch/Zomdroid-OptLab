package zombie.iso.fboRenderChunk;
import zombie.iso.IsoGridSquare;
public class FBORenderCutaways {
    private static final FBORenderCutaways INSTANCE = new FBORenderCutaways();
    public static FBORenderCutaways getInstance() { return INSTANCE; }
    public boolean shouldRenderBuildingSquare(int player, IsoGridSquare square) { return true; }
    public static class ChunkLevelData {
        public final byte[][] squareFlags = new byte[4][64];
    }
}
