package zombie.core;

import com.zomdroid.agent.optimization.HotPathOptimizationRuntime;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Random;

import zombie.core.Styles.AlphaOp;
import zombie.core.Styles.Style;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/** Randomized semantic parity and fallback-order checks for the CP6.13.4 direct scalar path. */
public final class Cp6134BuildDirectParityUnit {
    private static final long SEED = 0x6134_4220_42203L;
    private static final int SEQUENCES = 10_000;

    private Cp6134BuildDirectParityUnit() {}

    public static void main(String[] args) {
        HotPathOptimizationRuntime.configure(false, false, false, false,
                false, false, true, false);

        Random random = new Random(SEED);
        Texture[] textures = texturePool();
        TestStyle[] stylePool = {
                new TestStyle(0), new TestStyle(1), new TestStyle(1), new TestStyle(2)
        };
        long commands = 0;
        for (int sequence = 0; sequence < SEQUENCES; sequence++) {
            int count = 1 + random.nextInt(48);
            TextureDraw[] draws = new TextureDraw[count];
            Style[] styles = new Style[count];
            for (int i = 0; i < count; i++) {
                draws[i] = randomDraw(random, textures);
                styles[i] = random.nextInt(16) == 0
                        ? null : stylePool[random.nextInt(stylePool.length)];
            }

            SpriteRenderer.RingBuffer direct = new SpriteRenderer.RingBuffer(
                    count * 4 + 8, count * 6 + 12, count + 8);
            SpriteRenderer.RingBuffer vanilla = new SpriteRenderer.RingBuffer(
                    count * 4 + 8, count * 6 + 12, count + 8);

            SpriteRenderer.ringBuffer = direct;
            require(ZDOptBuildFast.tryBuild(draws, styles, count),
                    "direct loop declined sequence=" + sequence);
            runVanilla(vanilla, draws, styles, count);
            compare(direct, vanilla, sequence);
            commands += count;
        }

        verifyGrowthFallbackIsPreObservation();
        verifyCapacityFallback();
        verifyNullPaths();
        verifyStateRunComposition();

        HotPathOptimizationRuntime.configure(false, false, false, false,
                false, false, false, false);
        System.out.println("CP6134_BUILD_DIRECT_PARITY PASS sequences=" + SEQUENCES
                + " commands=" + commands + " bulk_pack=0 fallback_pre_side_effect=1");
    }

    private static void runVanilla(SpriteRenderer.RingBuffer ring, TextureDraw[] draws,
                                   Style[] styles, int count) {
        TextureDraw previous = null;
        for (int i = 0; i < count; i++) {
            ring.add(draws[i], previous, styles[i]);
            previous = draws[i];
        }
    }

    private static void verifyGrowthFallbackIsPreObservation() {
        SpriteRenderer.RingBuffer ring = new SpriteRenderer.RingBuffer(32, 48, 2);
        TestStyle oldStyle = new TestStyle(4);
        TestStyle newStyle = new TestStyle(5);
        TextureDraw first = draw(TextureDraw.Type.glDraw, new Texture(41));
        ring.add(first, null, oldStyle);
        oldStyle.calls = 0;
        newStyle.calls = 0;
        ring.vanillaAdds = 0;

        TextureDraw second = draw(TextureDraw.Type.glDraw, first.tex);
        SpriteRenderer.ringBuffer = ring;
        require(ZDOptBuildFast.tryBuild(
                new TextureDraw[] {second}, new Style[] {newStyle}, 1),
                "growth-boundary loop");
        require(ring.vanillaAdds == 1 && ring.growCalls == 1,
                "growth boundary must use vanilla once");
        require(newStyle.calls == 1 && oldStyle.calls == 1,
                "growth fallback observed style more than once");
    }

    private static void verifyCapacityFallback() {
        SpriteRenderer.RingBuffer ring = new SpriteRenderer.RingBuffer(4, 6, 8);
        TestStyle style = new TestStyle(7);
        TextureDraw first = draw(TextureDraw.Type.glDraw, new Texture(51));
        ring.add(first, null, style);
        ring.vanillaAdds = 0;

        TextureDraw second = draw(TextureDraw.Type.glDraw, first.tex);
        SpriteRenderer.ringBuffer = ring;
        require(ZDOptBuildFast.tryBuild(
                new TextureDraw[] {second}, new Style[] {style}, 1),
                "capacity-boundary loop");
        require(ring.vanillaAdds == 1 && ring.renders == 1 && ring.nextCalls == 1,
                "capacity rollover must remain vanilla");
        require(ring.vertexCursor == 4 && ring.indexCursor == 6,
                "capacity rollover payload");
    }

    private static void verifyNullPaths() {
        TestStyle style = new TestStyle(8);
        TextureDraw draw = draw(TextureDraw.Type.glDraw, new Texture(61));
        SpriteRenderer.RingBuffer nullStyleRing = new SpriteRenderer.RingBuffer();
        SpriteRenderer.ringBuffer = nullStyleRing;
        require(ZDOptBuildFast.tryBuild(
                new TextureDraw[] {draw}, new Style[] {null}, 1), "null style loop");
        require(nullStyleRing.vanillaAdds == 1 && nullStyleRing.numRuns == 0,
                "null style vanilla no-op");

        SpriteRenderer.RingBuffer nullDrawRing = new SpriteRenderer.RingBuffer();
        SpriteRenderer.ringBuffer = nullDrawRing;
        boolean threw = false;
        try {
            ZDOptBuildFast.tryBuild(
                    new TextureDraw[] {null}, new Style[] {style}, 1);
        } catch (NullPointerException expected) {
            threw = true;
        }
        require(threw && nullDrawRing.vanillaAdds == 1,
                "null draw must retain vanilla failure path");
    }

    private static void verifyStateRunComposition() {
        HotPathOptimizationRuntime.configure(false, false, true, false,
                false, false, true, false);
        SpriteRenderer.RingBuffer ring = new SpriteRenderer.RingBuffer();
        TestStyle style = new TestStyle(9);
        Texture firstTexture = new Texture(71);
        Texture equivalentTexture = new Texture(71);
        TextureDraw first = draw(TextureDraw.Type.glDraw, firstTexture);
        ring.add(first, null, style);
        ring.vanillaAdds = 0;

        TextureDraw equivalent = draw(TextureDraw.Type.glDraw, equivalentTexture);
        SpriteRenderer.ringBuffer = ring;
        require(ZDOptBuildFast.tryBuild(
                new TextureDraw[] {equivalent}, new Style[] {style}, 1),
                "StateRun composition loop");
        require(ring.vanillaAdds == 0 && ring.numRuns == 1
                        && ring.vertexCursor == 8 && ring.indexCursor == 12,
                "StateRun equivalent wrapper was not composed into direct path");

        HotPathOptimizationRuntime.configure(false, false, false, false,
                false, false, true, false);
    }

    private static Texture[] texturePool() {
        Texture[] values = new Texture[9];
        for (int i = 1; i < values.length; i++) values[i] = new Texture(100 + i);
        return values;
    }

    private static TextureDraw randomDraw(Random random, Texture[] textures) {
        TextureDraw draw = new TextureDraw();
        int kind = random.nextInt(10);
        draw.type = kind < 8 ? TextureDraw.Type.glDraw
                : kind == 8 ? TextureDraw.Type.other : TextureDraw.Type.DrawModel;
        draw.flipped = random.nextBoolean();
        draw.singleCol = random.nextInt(5) == 0;
        draw.col0 = random.nextInt();
        draw.col1 = random.nextInt();
        draw.col2 = random.nextInt();
        draw.col3 = random.nextInt();
        draw.x0 = value(random); draw.x1 = value(random);
        draw.x2 = value(random); draw.x3 = value(random);
        draw.y0 = value(random); draw.y1 = value(random);
        draw.y2 = value(random); draw.y3 = value(random);
        draw.u0 = value(random); draw.u1 = value(random);
        draw.u2 = value(random); draw.u3 = value(random);
        draw.v0 = value(random); draw.v1 = value(random);
        draw.v2 = value(random); draw.v3 = value(random);
        draw.z = value(random);
        draw.chunkDepth = value(random);
        draw.tex = textures[random.nextInt(textures.length)];
        draw.tex1 = textures[random.nextInt(textures.length)];
        draw.tex2 = textures[random.nextInt(textures.length)];
        draw.useAttribArray = (byte) random.nextInt(4);
        draw.tex1U0 = value(random); draw.tex1U1 = value(random);
        draw.tex1U2 = value(random); draw.tex1U3 = value(random);
        draw.tex1V0 = value(random); draw.tex1V1 = value(random);
        draw.tex1V2 = value(random); draw.tex1V3 = value(random);
        draw.tex2U0 = value(random); draw.tex2U1 = value(random);
        draw.tex2U2 = value(random); draw.tex2U3 = value(random);
        draw.tex2V0 = value(random); draw.tex2V1 = value(random);
        draw.tex2V2 = value(random); draw.tex2V3 = value(random);
        return draw;
    }

    private static TextureDraw draw(TextureDraw.Type type, Texture texture) {
        TextureDraw draw = new TextureDraw();
        draw.type = type;
        draw.tex = texture;
        draw.col0 = 0x11223344;
        draw.col1 = 0x55667788;
        draw.col2 = 0x11223344;
        draw.col3 = 0x99aabbcc;
        draw.x1 = 1.0F;
        draw.x2 = 1.0F;
        draw.y2 = 1.0F;
        draw.y3 = 1.0F;
        draw.u1 = 1.0F;
        draw.u2 = 1.0F;
        draw.v1 = 1.0F;
        draw.v2 = 1.0F;
        return draw;
    }

    private static float value(Random random) {
        return (random.nextFloat() - 0.5F) * 2048.0F;
    }

    private static void compare(SpriteRenderer.RingBuffer direct,
                                SpriteRenderer.RingBuffer vanilla, int sequence) {
        String prefix = "sequence=" + sequence + ' ';
        require(direct.vertexCursor == vanilla.vertexCursor, prefix + "vertexCursor");
        require(direct.indexCursor == vanilla.indexCursor, prefix + "indexCursor");
        require(direct.numRuns == vanilla.numRuns, prefix + "numRuns");
        require(direct.currentStyle == vanilla.currentStyle, prefix + "currentStyle");
        require(direct.currentTexture0 == vanilla.currentTexture0, prefix + "texture0");
        require(direct.currentTexture1 == vanilla.currentTexture1, prefix + "texture1");
        require(direct.currentTexture2 == vanilla.currentTexture2, prefix + "texture2");
        require(direct.currentUseAttribArray == vanilla.currentUseAttribArray,
                prefix + "attrib");
        compareFloats(direct.currentVertices, vanilla.currentVertices, prefix);
        compareShorts(direct.currentIndices, vanilla.currentIndices, prefix);

        for (int i = 0; i < direct.numRuns; i++) {
            SpriteRenderer.RingBuffer.StateRun left = direct.stateRun[i];
            SpriteRenderer.RingBuffer.StateRun right = vanilla.stateRun[i];
            require(Float.floatToRawIntBits(left.z) == Float.floatToRawIntBits(right.z),
                    prefix + "run z " + i);
            require(Float.floatToRawIntBits(left.chunkDepth)
                            == Float.floatToRawIntBits(right.chunkDepth),
                    prefix + "run chunkDepth " + i);
            require(left.texture0 == right.texture0 && left.texture1 == right.texture1
                            && left.texture2 == right.texture2,
                    prefix + "run textures " + i);
            require(left.useAttribArray == right.useAttribArray && left.style == right.style,
                    prefix + "run state " + i);
            require(left.start == right.start && left.length == right.length
                            && left.startIndex == right.startIndex
                            && left.endIndex == right.endIndex,
                    prefix + "run ranges " + i);
            require(left.ops.size() == right.ops.size(), prefix + "run ops size " + i);
            for (int op = 0; op < left.ops.size(); op++) {
                require(left.ops.get(op) == right.ops.get(op),
                        prefix + "run op identity " + i + '/' + op);
            }
        }
    }

    private static void compareFloats(FloatBuffer left, FloatBuffer right, String prefix) {
        require(left.position() == right.position(), prefix + "float position");
        for (int i = 0; i < left.position(); i++) {
            require(Float.floatToRawIntBits(left.get(i)) == Float.floatToRawIntBits(right.get(i)),
                    prefix + "float " + i);
        }
    }

    private static void compareShorts(ShortBuffer left, ShortBuffer right, String prefix) {
        require(left.position() == right.position(), prefix + "short position");
        for (int i = 0; i < left.position(); i++) {
            require(left.get(i) == right.get(i), prefix + "short " + i);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestStyle implements Style {
        private final int id;
        int calls;

        TestStyle(int id) {
            this.id = id;
        }

        @Override public int getStyleID() {
            calls++;
            return id;
        }

        @Override public AlphaOp getAlphaOp() {
            return AlphaOp.KEEP;
        }
    }
}
