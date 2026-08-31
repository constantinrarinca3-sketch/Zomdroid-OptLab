package zombie.iso.fboRenderChunk;

import com.zomdroid.agent.optimization.FboInnerLoopRuntime;

import zombie.core.textures.ColorInfo;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;

import java.util.Random;

/** Randomized semantic parity gate for the CP6.13.4 FBO preparation loop. */
public final class Cp6134FboInnerLoopParityUnit {
    private static final int SEQUENCES = 2_000;
    private static final int PLAYERS = 4;

    private Cp6134FboInnerLoopParityUnit() {}

    public static void main(String[] args) {
        FboInnerLoopRuntime.configure(true);
        Random random = new Random(0x6134F80L);
        long squares = 0L;
        long callsSaved = 0L;

        for (int sequence = 0; sequence < SEQUENCES; sequence++) {
            int player = random.nextInt(PLAYERS);
            int framePlayer = random.nextInt(PLAYERS);
            int min = random.nextInt(3);
            int max = min + random.nextInt(4 - min);
            boolean onScreen = random.nextInt(8) != 0;
            Scenario scenario = Scenario.random(random, min, max, onScreen);
            TestChunk vanillaChunk = scenario.instantiate();
            TestChunk fastChunk = scenario.instantiate();
            IsoCamera.frameState.playerIndex = framePlayer;

            vanilla(player, vanillaChunk, min);
            boolean handled = ZDOptFboPrepareFast.tryPrepare(
                    new FBORenderCell(), player, fastChunk, min);
            require(handled, "fastpath declined sequence " + sequence);
            compareFlags(vanillaChunk, fastChunk, min, max, sequence);
            require(vanillaChunk.cacheCalls == fastChunk.cacheCalls,
                    "cacheLightInfo parity sequence " + sequence);
            require(vanillaChunk.lightCalls >= fastChunk.lightCalls,
                    "light-call regression sequence " + sequence);
            callsSaved += vanillaChunk.lightCalls - fastChunk.lightCalls;
            if (onScreen) squares += (long) (max - min + 1) * 64L;
        }

        require(callsSaved > 0L, "player-light reuse was not exercised");
        System.out.println("CP6134_FBO_INNER_LOOP_PARITY PASS sequences=" + SEQUENCES
                + " squares=" + squares + " light_calls_saved=" + callsSaved
                + " skip=0 defer=0");
    }

    private static void vanilla(int player, TestChunk chunk, int z) {
        if (chunk == null) return;
        FBORenderLevels levels = chunk.getRenderLevels(player);
        if (!levels.isOnScreen(z)) return;
        int framePlayer = IsoCamera.frameState.playerIndex;
        for (int level = levels.getMinLevel(z); level <= levels.getMaxLevel(z); level++) {
            byte[] flags = chunk.getCutawayDataForLevel(level).squareFlags[player];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int index = x + (y << 3);
                    flags[index] = 0;
                    IsoGridSquare square = chunk.getGridSquare(x, y, level);
                    if (square == null) continue;
                    square.cacheLightInfo();
                    ColorInfo playerLight = square.getLightInfo(player);
                    if (playerLight == null) continue;
                    ColorInfo frameLight = square.getLightInfo(framePlayer);
                    if (frameLight == null || square.lighting[framePlayer] == null) continue;
                    if (!FBORenderCutaways.getInstance()
                            .shouldRenderBuildingSquare(framePlayer, square)) continue;
                    flags[index] = (byte) (flags[index] | 1);
                }
            }
        }
    }

    private static void compareFlags(TestChunk vanilla, TestChunk fast, int min, int max,
                                     int sequence) {
        for (int level = min; level <= max; level++) {
            for (int player = 0; player < PLAYERS; player++) {
                byte[] left = vanilla.data[level].squareFlags[player];
                byte[] right = fast.data[level].squareFlags[player];
                for (int index = 0; index < 64; index++) {
                    require(left[index] == right[index], "flag mismatch sequence=" + sequence
                            + " level=" + level + " player=" + player
                            + " index=" + index);
                }
            }
        }
    }

    private static final class Scenario {
        final int min;
        final int max;
        final boolean onScreen;
        final SquareModel[][] squares;

        Scenario(int min, int max, boolean onScreen, SquareModel[][] squares) {
            this.min = min;
            this.max = max;
            this.onScreen = onScreen;
            this.squares = squares;
        }

        static Scenario random(Random random, int min, int max, boolean onScreen) {
            SquareModel[][] squares = new SquareModel[4][64];
            for (int level = min; level <= max; level++) {
                for (int index = 0; index < 64; index++) {
                    if (random.nextInt(7) == 0) continue;
                    boolean[] lightInfo = new boolean[PLAYERS];
                    boolean[] lighting = new boolean[PLAYERS];
                    for (int player = 0; player < PLAYERS; player++) {
                        lightInfo[player] = random.nextInt(6) != 0;
                        lighting[player] = random.nextInt(7) != 0;
                    }
                    squares[level][index] = new SquareModel(lightInfo, lighting);
                }
            }
            return new Scenario(min, max, onScreen, squares);
        }

        TestChunk instantiate() {
            return new TestChunk(this);
        }
    }

    private static final class SquareModel {
        final boolean[] lightInfo;
        final boolean[] lighting;
        SquareModel(boolean[] lightInfo, boolean[] lighting) {
            this.lightInfo = lightInfo;
            this.lighting = lighting;
        }
    }

    private static final class TestChunk extends IsoChunk {
        final TestLevels levels;
        final FBORenderCutaways.ChunkLevelData[] data =
                new FBORenderCutaways.ChunkLevelData[4];
        final TestSquare[][] squares = new TestSquare[4][64];
        long cacheCalls;
        long lightCalls;

        TestChunk(Scenario scenario) {
            levels = new TestLevels(scenario.min, scenario.max, scenario.onScreen);
            for (int level = 0; level < data.length; level++) {
                data[level] = new FBORenderCutaways.ChunkLevelData();
                for (int player = 0; player < PLAYERS; player++) {
                    for (int index = 0; index < 64; index++) {
                        data[level].squareFlags[player][index] = (byte) 0x5a;
                    }
                }
                for (int index = 0; index < 64; index++) {
                    SquareModel model = scenario.squares[level][index];
                    if (model != null) squares[level][index] = new TestSquare(this, model);
                }
            }
        }

        @Override public FBORenderLevels getRenderLevels(int player) { return levels; }
        @Override public FBORenderCutaways.ChunkLevelData getCutawayDataForLevel(int z) {
            return data[z];
        }
        @Override public IsoGridSquare getGridSquare(int x, int y, int z) {
            return squares[z][x + (y << 3)];
        }
    }

    private static final class TestLevels extends FBORenderLevels {
        final int min;
        final int max;
        final boolean onScreen;
        TestLevels(int min, int max, boolean onScreen) {
            this.min = min;
            this.max = max;
            this.onScreen = onScreen;
        }
        @Override public boolean isOnScreen(int z) { return onScreen; }
        @Override public int getMinLevel(int z) { return min; }
        @Override public int getMaxLevel(int z) { return max; }
    }

    private static final class TestSquare extends IsoGridSquare {
        final TestChunk owner;
        final ColorInfo[] lightInfo = new ColorInfo[PLAYERS];
        TestSquare(TestChunk owner, SquareModel model) {
            this.owner = owner;
            for (int player = 0; player < PLAYERS; player++) {
                if (model.lightInfo[player]) lightInfo[player] = new ColorInfo();
                if (model.lighting[player]) lighting[player] = new ILighting() {};
            }
        }
        @Override public void cacheLightInfo() { owner.cacheCalls++; }
        @Override public ColorInfo getLightInfo(int player) {
            owner.lightCalls++;
            return lightInfo[player];
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
