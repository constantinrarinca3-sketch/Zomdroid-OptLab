package zombie.core;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import zombie.core.Styles.AlphaOp;
import zombie.core.Styles.Style;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/** Compile and parity-test subset for the Build 42 buildDrawBuffer/StateRun paths. */
public final class SpriteRenderer {
    public static RingBuffer ringBuffer;

    private void buildDrawBuffer(TextureDraw[] draws, Style[] styles, int count) {
        TextureDraw previous = null;
        for (int i = 0; i < count; i++) {
            ringBuffer.add(draws[i], previous, styles[i]);
            previous = draws[i];
        }
    }

    public static final class RingBuffer {
        long bufferSizeInVertices;
        long indexBufferSize;
        FloatBuffer currentVertices;
        ShortBuffer currentIndices;
        Texture currentTexture0;
        Texture currentTexture1;
        Texture currentTexture2;
        byte currentUseAttribArray;
        Style currentStyle;
        StateRun[] stateRun;
        int vertexCursor;
        int indexCursor;
        int numRuns;
        StateRun currentRun;

        public int vanillaAdds;
        public int renders;
        public int nextCalls;
        public int growCalls;

        public RingBuffer() {
            this(1024, 1536, 64);
        }

        public RingBuffer(int vertexCapacity, int indexCapacity, int runCapacity) {
            bufferSizeInVertices = vertexCapacity;
            indexBufferSize = indexCapacity;
            currentVertices = FloatBuffer.allocate(Math.max(0, vertexCapacity * 9));
            currentIndices = ShortBuffer.allocate(Math.max(0, indexCapacity));
            stateRun = new StateRun[Math.max(1, runCapacity)];
            fillRuns(stateRun, 0);
        }

        void add(TextureDraw draw, TextureDraw previous, Style style) {
            vanillaAdds++;
            if (style == null) return;
            if ((long) vertexCursor + 4L > bufferSizeInVertices
                    || (long) indexCursor + 6L > indexBufferSize) {
                render();
                next();
            }
            if (!prepareCurrentRun(draw, previous, style)) return;

            FloatBuffer vertices = currentVertices;
            AlphaOp alpha = style.getAlphaOp();
            Texture texture0 = draw.tex;
            Texture texture1 = draw.tex1;
            Texture texture2 = draw.tex2;
            putVertex(vertices, draw.x0, draw.y0,
                    texture0 == null ? 0.0F : (draw.flipped ? draw.u1 : draw.u0),
                    texture0 == null ? 0.0F : draw.v0, draw.getColor(0),
                    texture1 == null ? 0.0F : draw.tex1U0,
                    texture1 == null ? 0.0F : draw.tex1V0,
                    texture2 == null ? 0.0F : draw.tex2U0,
                    texture2 == null ? 0.0F : draw.tex2V0, alpha);
            putVertex(vertices, draw.x1, draw.y1,
                    texture0 == null ? 0.0F : (draw.flipped ? draw.u0 : draw.u1),
                    texture0 == null ? 0.0F : draw.v1, draw.getColor(1),
                    texture1 == null ? 0.0F : draw.tex1U1,
                    texture1 == null ? 0.0F : draw.tex1V1,
                    texture2 == null ? 0.0F : draw.tex2U1,
                    texture2 == null ? 0.0F : draw.tex2V1, alpha);
            putVertex(vertices, draw.x2, draw.y2,
                    texture0 == null ? 0.0F : (draw.flipped ? draw.u3 : draw.u2),
                    texture0 == null ? 0.0F : draw.v2, draw.getColor(2),
                    texture1 == null ? 0.0F : draw.tex1U2,
                    texture1 == null ? 0.0F : draw.tex1V2,
                    texture2 == null ? 0.0F : draw.tex2U2,
                    texture2 == null ? 0.0F : draw.tex2V2, alpha);
            putVertex(vertices, draw.x3, draw.y3,
                    texture0 == null ? 0.0F : (draw.flipped ? draw.u2 : draw.u3),
                    texture0 == null ? 0.0F : draw.v3, draw.getColor(3),
                    texture1 == null ? 0.0F : draw.tex1U3,
                    texture1 == null ? 0.0F : draw.tex1V3,
                    texture2 == null ? 0.0F : draw.tex2U3,
                    texture2 == null ? 0.0F : draw.tex2V3, alpha);

            int vertex = vertexCursor;
            if (draw.getColor(0) == draw.getColor(2)) {
                currentIndices.put((short) vertex).put((short) (vertex + 1))
                        .put((short) (vertex + 2));
                currentIndices.put((short) vertex).put((short) (vertex + 2))
                        .put((short) (vertex + 3));
            } else {
                currentIndices.put((short) (vertex + 1)).put((short) (vertex + 2))
                        .put((short) (vertex + 3));
                currentIndices.put((short) (vertex + 1)).put((short) (vertex + 3))
                        .put((short) vertex);
            }
            indexCursor += 6;
            vertexCursor += 4;
            currentRun.endIndex += 6;
            currentRun.length += 4;
        }

        private boolean prepareCurrentRun(TextureDraw draw, TextureDraw previous, Style style) {
            Texture texture0 = draw.tex;
            Texture texture1 = draw.tex1;
            Texture texture2 = draw.tex2;
            byte attrib = draw.useAttribArray;
            if (isStateChanged(draw, previous, style,
                    texture0, texture1, texture2, attrib)) {
                currentRun = stateRun[numRuns];
                currentRun.start = vertexCursor;
                currentRun.length = 0;
                currentRun.style = style;
                currentRun.texture0 = texture0;
                currentRun.z = draw.z;
                currentRun.chunkDepth = draw.chunkDepth;
                currentRun.texture1 = texture1;
                currentRun.texture2 = texture2;
                currentRun.useAttribArray = attrib;
                currentRun.indices = currentIndices;
                currentRun.startIndex = indexCursor;
                currentRun.endIndex = indexCursor;
                numRuns++;
                if (numRuns == stateRun.length) growStateRuns();
                currentStyle = style;
                currentTexture0 = texture0;
                currentTexture1 = texture1;
                currentTexture2 = texture2;
                currentUseAttribArray = attrib;
            }
            if (draw.type != TextureDraw.Type.glDraw) {
                currentRun.ops.add(draw);
                return false;
            }
            return true;
        }

        private boolean isStateChanged(TextureDraw draw, TextureDraw previous, Style style,
                                       Texture texture0, Texture texture1, Texture texture2,
                                       byte attrib) {
            if (currentRun == null) return true;
            if (draw.type == TextureDraw.Type.DrawModel) return true;
            if (attrib != currentUseAttribArray) return true;
            if (texture0 != currentTexture0 || texture1 != currentTexture1
                    || texture2 != currentTexture2) return true;
            if (previous != null) {
                if (previous.type == TextureDraw.Type.DrawModel) return true;
                if (draw.type == TextureDraw.Type.glDraw
                        && previous.type != TextureDraw.Type.glDraw) return true;
                if (draw.type != TextureDraw.Type.glDraw
                        && previous.type == TextureDraw.Type.glDraw) return true;
            }
            if (style != currentStyle) {
                if (currentStyle == null) return true;
                if (style.getStyleID() != currentStyle.getStyleID()) return true;
            }
            return false;
        }

        private void next() {
            nextCalls++;
            currentVertices.clear();
            currentIndices.clear();
            vertexCursor = 0;
            indexCursor = 0;
            numRuns = 0;
            currentRun = null;
        }

        void render() {
            renders++;
        }

        void growStateRuns() {
            growCalls++;
            int oldLength = stateRun.length;
            stateRun = Arrays.copyOf(stateRun, Math.max(2, oldLength * 2));
            fillRuns(stateRun, oldLength);
        }

        private static void fillRuns(StateRun[] runs, int start) {
            for (int i = start; i < runs.length; i++) runs[i] = new StateRun();
        }

        private static void putVertex(FloatBuffer vertices, float x, float y, float u, float v,
                                      int color, float u1, float v1, float u2, float v2,
                                      AlphaOp alpha) {
            vertices.put(x).put(y).put(u).put(v);
            alpha.op(color, 255, vertices);
            vertices.put(u1).put(v1).put(u2).put(v2);
        }

        static final class StateRun {
            float z;
            float chunkDepth;
            Texture texture0;
            Texture texture1;
            Texture texture2;
            byte useAttribArray;
            Style style;
            int start;
            int length;
            ShortBuffer indices;
            int startIndex;
            int endIndex;
            final ArrayList<TextureDraw> ops = new ArrayList<>();
        }
    }
}
