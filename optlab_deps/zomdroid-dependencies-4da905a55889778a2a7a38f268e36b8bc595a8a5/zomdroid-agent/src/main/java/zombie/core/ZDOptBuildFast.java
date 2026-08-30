package zombie.core;

import com.zomdroid.agent.optimization.HotPathOptimizationRuntime;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import zombie.core.Styles.AlphaOp;
import zombie.core.Styles.Style;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * CP6.13.4 direct implementation of the exact Build 42 {@code buildDrawBuffer} loop.
 *
 * <p>The previous CP6.6 helper only hoisted {@link SpriteRenderer#ringBuffer}; every command still
 * entered the large vanilla {@code RingBuffer.add} method. This version mirrors the normal scalar
 * add path in the same runtime package, while retaining {@code RingBuffer.add} for every uncertain
 * case. It deliberately does not include the rejected bulk RingPack experiment.</p>
 *
 * <p>All exits that request vanilla fallback happen before {@code stateChanged} is evaluated and
 * before any buffer or StateRun mutation. Once the commit phase starts, exceptions are allowed to
 * propagate exactly as they do in vanilla; falling back after a partial write would duplicate a
 * command.</p>
 */
public final class ZDOptBuildFast {
    private static final int VERTEX_FLOATS = 36;
    private static final int INDEX_SHORTS = 6;

    private ZDOptBuildFast() {}

    public static boolean tryBuild(Object drawArray, Object styleArray, int count) {
        if (!HotPathOptimizationRuntime.isBuildLoopEnabled()) return false;

        TextureDraw[] draws = (TextureDraw[]) drawArray;
        Style[] styles = (Style[]) styleArray;
        SpriteRenderer.RingBuffer buffer = SpriteRenderer.ringBuffer;
        if (buffer == null) return false;

        TextureDraw previous = null;
        for (int i = 0; i < count; i++) {
            TextureDraw draw = draws[i];
            Style style = styles[i];
            if (!tryAddDirect(buffer, draw, previous, style)) {
                buffer.add(draw, previous, style);
            }
            previous = draw;
        }

        HotPathOptimizationRuntime.noteBuildLoopHandled();
        return true;
    }

    /** Returns false only during the read-only preflight phase. */
    private static boolean tryAddDirect(SpriteRenderer.RingBuffer buffer, TextureDraw draw,
                                        TextureDraw previous, Style style) {
        // Vanilla treats a null style as a no-op and owns the abnormal null-draw failure path.
        if (draw == null || style == null) return false;

        // Rollover renders and remaps VBOs through private RingBuffer methods. Vanilla must own it.
        if ((long) buffer.vertexCursor + 4L > buffer.bufferSizeInVertices
                || (long) buffer.indexCursor + INDEX_SHORTS > buffer.indexBufferSize) {
            return false;
        }

        SpriteRenderer.RingBuffer.StateRun[] runs = buffer.stateRun;
        int runIndex = buffer.numRuns;
        // Be conservative even when the current command might reuse a run. This guarantees that
        // a possible growStateRuns boundary is rejected before getStyleID() can be observed twice.
        if (runs == null || runIndex < 0 || runIndex >= runs.length - 1) return false;
        SpriteRenderer.RingBuffer.StateRun candidate = runs[runIndex];
        if (candidate == null) return false;

        boolean glDraw = draw.type == TextureDraw.Type.glDraw;
        FloatBuffer vertices = buffer.currentVertices;
        ShortBuffer indices = buffer.currentIndices;
        if (glDraw) {
            if (vertices == null || indices == null
                    || vertices.remaining() < VERTEX_FLOATS
                    || indices.remaining() < INDEX_SHORTS) return false;
        } else {
            // prepareCurrentRun appends the operation either to the current or the candidate run.
            if (candidate.ops == null
                    || (buffer.currentRun != null && buffer.currentRun.ops == null)) return false;
        }

        // No fallback is legal after this point: state/style inspection may be observable and the
        // following block can mutate StateRun state before vertex packing begins.
        Texture texture0 = draw.tex;
        Texture texture1 = draw.tex1;
        Texture texture2 = draw.tex2;
        byte attrib = draw.useAttribArray;
        boolean changed = stateChanged(buffer, draw, previous, style,
                texture0, texture1, texture2, attrib);

        SpriteRenderer.RingBuffer.StateRun run = buffer.currentRun;
        if (changed) {
            run = candidate;
            buffer.currentRun = run;
            run.start = buffer.vertexCursor;
            run.length = 0;
            run.style = style;
            run.texture0 = texture0;
            run.z = draw.z;
            run.chunkDepth = draw.chunkDepth;
            run.texture1 = texture1;
            run.texture2 = texture2;
            run.useAttribArray = attrib;
            run.indices = indices;
            run.startIndex = buffer.indexCursor;
            run.endIndex = buffer.indexCursor;
            buffer.numRuns = runIndex + 1;
            buffer.currentStyle = style;
            buffer.currentTexture0 = texture0;
            buffer.currentTexture1 = texture1;
            buffer.currentTexture2 = texture2;
            buffer.currentUseAttribArray = attrib;
        }

        if (!glDraw) {
            run.ops.add(draw);
            return true;
        }

        packScalar(buffer, run, draw, style, texture0, texture1, texture2,
                vertices, indices);
        return true;
    }

    private static boolean stateChanged(SpriteRenderer.RingBuffer buffer, TextureDraw draw,
                                        TextureDraw previous, Style style, Texture texture0,
                                        Texture texture1, Texture texture2, byte attrib) {
        if (HotPathOptimizationRuntime.isStateRunTextureEnabled()) {
            return ZDOptStateRunFast.stateChanged(buffer, draw, previous, style,
                    texture0, texture1, texture2, attrib);
        }

        if (buffer.currentRun == null) return true;
        if (draw.type == TextureDraw.Type.DrawModel) return true;
        if (attrib != buffer.currentUseAttribArray) return true;
        if (texture0 != buffer.currentTexture0
                || texture1 != buffer.currentTexture1
                || texture2 != buffer.currentTexture2) return true;
        if (previous != null) {
            if (previous.type == TextureDraw.Type.DrawModel) return true;
            if (draw.type == TextureDraw.Type.glDraw
                    && previous.type != TextureDraw.Type.glDraw) return true;
            if (draw.type != TextureDraw.Type.glDraw
                    && previous.type == TextureDraw.Type.glDraw) return true;
        }
        if (style != buffer.currentStyle) {
            if (buffer.currentStyle == null) return true;
            if (style.getStyleID() != buffer.currentStyle.getStyleID()) return true;
        }
        return false;
    }

    /** Exact scalar payload; no bulk arrays, allocation, reordering or command suppression. */
    private static void packScalar(SpriteRenderer.RingBuffer buffer,
                                   SpriteRenderer.RingBuffer.StateRun run,
                                   TextureDraw draw, Style style,
                                   Texture texture0, Texture texture1, Texture texture2,
                                   FloatBuffer vertices, ShortBuffer indices) {
        AlphaOp alpha = style.getAlphaOp();

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

        int vertex = buffer.vertexCursor;
        if (draw.getColor(0) == draw.getColor(2)) {
            indices.put((short) vertex).put((short) (vertex + 1)).put((short) (vertex + 2));
            indices.put((short) vertex).put((short) (vertex + 2)).put((short) (vertex + 3));
        } else {
            indices.put((short) (vertex + 1)).put((short) (vertex + 2))
                    .put((short) (vertex + 3));
            indices.put((short) (vertex + 1)).put((short) (vertex + 3)).put((short) vertex);
        }

        buffer.indexCursor += INDEX_SHORTS;
        buffer.vertexCursor += 4;
        run.endIndex += INDEX_SHORTS;
        run.length += 4;
    }

    private static void putVertex(FloatBuffer vertices, float x, float y, float u, float v,
                                  int color, float u1, float v1, float u2, float v2,
                                  AlphaOp alpha) {
        vertices.put(x).put(y).put(u).put(v);
        alpha.op(color, 255, vertices);
        vertices.put(u1).put(v1).put(u2).put(v2);
    }
}
