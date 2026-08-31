package zombie.iso.fboRenderChunk;

import com.zomdroid.agent.optimization.FboInnerLoopRuntime;

import zombie.core.textures.ColorInfo;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;

/**
 * Exact CP6.13.4 preparation loop.
 *
 * <p>No dirty work is skipped, budgeted or deferred. The only changes are reusing the selected
 * squareFlags row and the already fetched light when the method player is the frame player.</p>
 */
public final class ZDOptFboPrepareFast {
    private ZDOptFboPrepareFast() {}

    public static boolean tryPrepare(Object self, int playerIndex,
                                     Object chunkObject, int z) {
        if (!FboInnerLoopRuntime.isEnabled()) return false;
        IsoChunk chunk = (IsoChunk) chunkObject;
        if (chunk == null) {
            FboInnerLoopRuntime.noteHandled();
            return true;
        }

        FBORenderLevels levels = chunk.getRenderLevels(playerIndex);
        if (!levels.isOnScreen(z)) {
            FboInnerLoopRuntime.noteHandled();
            return true;
        }

        int min = levels.getMinLevel(z);
        int max = levels.getMaxLevel(z);
        int framePlayer = IsoCamera.frameState.playerIndex;
        FBORenderCutaways cutaways = FBORenderCutaways.getInstance();
        for (int level = min; level <= max; level++) {
            FBORenderCutaways.ChunkLevelData levelData =
                    chunk.getCutawayDataForLevel(level);
            byte[] flags = levelData.squareFlags[playerIndex];
            for (int y = 0; y < 8; y++) {
                int row = y << 3;
                for (int x = 0; x < 8; x++) {
                    int index = x + row;
                    flags[index] = 0;
                    IsoGridSquare square = chunk.getGridSquare(x, y, level);
                    if (square == null) continue;
                    square.cacheLightInfo();
                    ColorInfo playerLight = square.getLightInfo(playerIndex);
                    if (playerLight == null) continue;
                    ColorInfo frameLight = framePlayer == playerIndex
                            ? playerLight : square.getLightInfo(framePlayer);
                    if (frameLight == null || square.lighting[framePlayer] == null) continue;
                    if (!cutaways.shouldRenderBuildingSquare(framePlayer, square)) continue;
                    flags[index] = (byte) (flags[index] | 1);
                }
            }
        }
        FboInnerLoopRuntime.noteHandled();
        return true;
    }
}
