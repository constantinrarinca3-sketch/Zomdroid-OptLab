package zombie.core;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import zombie.core.Styles.AlphaOp;
import zombie.core.Styles.Style;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import mglpz.chunkagent.Optimizer;
import mglpz.chunkagent.Profiler;
import zombie.core.skinnedmodel.model.Model;

/**
 * CP6.2 common glDraw packing fastpath for SpriteRenderer.RingBuffer.add().
 *
 * It handles only the normal glDraw shape after strict preflight. Any operation draw,
 * insufficient logical/physical buffer room, null state table, or disabled toggle returns false
 * before mutating RingBuffer so the renamed vanilla method can run exactly.
 *
 * The vertex layout and state-run transition are copied from Build 42.20.3 bytecode. The main
 * optimization is replacing 36 scalar FloatBuffer.put(float) calls and six scalar
 * ShortBuffer.put(short) calls per sprite with two bulk puts. AlphaOp.op(int,255,buffer) is final
 * in Build 42.20.3 and writes Float.intBitsToFloat(color); style.getAlphaOp() is still called once
 * per handled sprite to preserve the vanilla style access.
 */
public final class MGLPZRingFast {
    private static final int PUBLISH_MASK = 4095;
    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        @Override protected Scratch initialValue() { return new Scratch(); }
    };

    private static long calls;
    private static long handled;
    private static long fallbackToggle;
    private static long fallbackNonDraw;
    private static long fallbackCapacity;
    private static long fallbackPreflight;
    private static long stateChanges;
    private static long sameState;
    private static long publishCalls;
    private static long renderCalls;
    private static long renderHandled;
    private static long modelClearSkipped;
    private static long modelClearExecuted;

    private MGLPZRingFast() {}

    public static boolean tryAdd(SpriteRenderer.RingBuffer rb, TextureDraw d, TextureDraw prev, Style style) {
        ++calls;
        if (!Optimizer.rthreadRingPackFastEnabled()) {
            ++fallbackToggle;
            publishMaybe();
            return false;
        }
        if (rb == null || d == null || style == null) {
            ++fallbackPreflight;
            publishMaybe();
            return false;
        }
        if (d.type != TextureDraw.Type.glDraw) {
            ++fallbackNonDraw;
            publishMaybe();
            return false;
        }

        // Vanilla rolls buffers when logical capacity is exceeded. We deliberately let vanilla
        // own that rare path because RingBuffer.next() is private and rollover includes VBO work.
        if ((long)rb.vertexCursor + 4L > rb.bufferSizeInVertices
                || (long)rb.indexCursor + 6L > rb.indexBufferSize) {
            ++fallbackCapacity;
            publishMaybe();
            return false;
        }
        final FloatBuffer fv = rb.currentVertices;
        final ShortBuffer si = rb.currentIndices;
        if (fv == null || si == null || fv.remaining() < 36 || si.remaining() < 6
                || rb.stateRun == null || rb.numRuns < 0 || rb.numRuns >= rb.stateRun.length) {
            ++fallbackPreflight;
            publishMaybe();
            return false;
        }

        // Vanilla calls getAlphaOp once after prepareCurrentRun and then invokes the final int
        // overload four times. A null AlphaOp is abnormal; fail closed before mutation so vanilla
        // retains its exact exception/partial-write behavior.
        final AlphaOp alpha = style.getAlphaOp();
        if (alpha == null) {
            ++fallbackPreflight;
            publishMaybe();
            return false;
        }

        final Texture t0 = d.tex;
        final Texture t1 = d.tex1;
        final Texture t2 = d.tex2;
        final byte attrib = d.useAttribArray;
        final boolean changed = stateChanged(rb, d, prev, style, t0, t1, t2, attrib);

        if (changed) {
            SpriteRenderer.RingBuffer.StateRun run = rb.stateRun[rb.numRuns];
            if (run == null) {
                ++fallbackPreflight;
                publishMaybe();
                return false;
            }
            rb.currentRun = run;
            run.start = rb.vertexCursor;
            run.length = 0;
            run.style = style;
            run.texture0 = t0;
            run.z = d.z;
            run.chunkDepth = d.chunkDepth;
            run.texture1 = t1;
            run.texture2 = t2;
            run.useAttribArray = attrib;
            run.indices = si;
            run.startIndex = rb.indexCursor;
            run.endIndex = rb.indexCursor;
            ++rb.numRuns;
            if (rb.numRuns == rb.stateRun.length) rb.growStateRuns();
            rb.currentStyle = style;
            rb.currentTexture0 = t0;
            rb.currentTexture1 = t1;
            rb.currentTexture2 = t2;
            rb.currentUseAttribArray = attrib;
            ++stateChanges;
        } else {
            if (rb.currentRun == null) {
                ++fallbackPreflight;
                publishMaybe();
                return false;
            }
            ++sameState;
        }

        final Scratch s = SCRATCH.get();
        final float[] v = s.v;
        int p = 0;

        final int c0 = d.col0;
        final int c1 = d.singleCol ? c0 : d.col1;
        final int c2 = d.singleCol ? c0 : d.col2;
        final int c3 = d.singleCol ? c0 : d.col3;

        p = vertex(v,p,d.x0,d.y0,t0 == null ? 0f : (d.flipped ? d.u1 : d.u0),t0 == null ? 0f : d.v0,c0,
                t1 == null ? 0f : d.tex1U0,t1 == null ? 0f : d.tex1V0,
                t2 == null ? 0f : d.tex2U0,t2 == null ? 0f : d.tex2V0);
        p = vertex(v,p,d.x1,d.y1,t0 == null ? 0f : (d.flipped ? d.u0 : d.u1),t0 == null ? 0f : d.v1,c1,
                t1 == null ? 0f : d.tex1U1,t1 == null ? 0f : d.tex1V1,
                t2 == null ? 0f : d.tex2U1,t2 == null ? 0f : d.tex2V1);
        p = vertex(v,p,d.x2,d.y2,t0 == null ? 0f : (d.flipped ? d.u3 : d.u2),t0 == null ? 0f : d.v2,c2,
                t1 == null ? 0f : d.tex1U2,t1 == null ? 0f : d.tex1V2,
                t2 == null ? 0f : d.tex2U2,t2 == null ? 0f : d.tex2V2);
        vertex(v,p,d.x3,d.y3,t0 == null ? 0f : (d.flipped ? d.u2 : d.u3),t0 == null ? 0f : d.v3,c3,
                t1 == null ? 0f : d.tex1U3,t1 == null ? 0f : d.tex1V3,
                t2 == null ? 0f : d.tex2U3,t2 == null ? 0f : d.tex2V3);

        final int base = rb.vertexCursor;
        final short[] idx = s.i;
        if (c0 == c2) {
            idx[0]=(short)base; idx[1]=(short)(base+1); idx[2]=(short)(base+2);
            idx[3]=(short)base; idx[4]=(short)(base+2); idx[5]=(short)(base+3);
        } else {
            idx[0]=(short)(base+1); idx[1]=(short)(base+2); idx[2]=(short)(base+3);
            idx[3]=(short)(base+1); idx[4]=(short)(base+3); idx[5]=(short)base;
        }

        // From here all bounds/state prerequisites were preflighted. Do not catch exceptions:
        // if the JVM buffer itself fails, vanilla would fail too and a fallback after mutation
        // would duplicate state.
        fv.put(v);
        si.put(idx);
        rb.indexCursor += 6;
        rb.vertexCursor += 4;
        rb.currentRun.endIndex += 6;
        rb.currentRun.length += 4;
        ++handled;
        publishMaybe();
        return true;
    }

    /** CP6.2 render-tail fastpath: vanilla render plus a proven-safe empty-map clear elision. */
    public static boolean tryRender(SpriteRenderer.RingBuffer rb) {
        if (Optimizer.rthreadLeanTelemetryEnabled()) {
            if (!Optimizer.rthreadRingRenderFastEnabled()) return false;
            if (rb == null || rb.vbo == null || rb.ibo == null || rb.stateRun == null
                    || rb.sequence < 0 || rb.sequence >= rb.vbo.length || rb.sequence >= rb.ibo.length
                    || rb.vbo[rb.sequence] == null || rb.ibo[rb.sequence] == null || Model.modelDrawCounts == null) {
                return false;
            }
            final long g11Start = "1".equals(System.getProperty("mglpz.cp613.telemetry", "0")) ? Profiler.gameplayProbeStartNs() : 0L;
            final long g11Calls0 = g11Start == 0L ? 0L : MGLPZStateRunRenderFast.telemetryCalls();
            final long g11Hits0 = g11Start == 0L ? 0L : MGLPZStateRunRenderFast.telemetryHits();
            final long g11Draws0 = g11Start == 0L ? 0L : MGLPZStateRunRenderFast.telemetryDraws();
            final long g11Ops0 = g11Start == 0L ? 0L : MGLPZStateRunRenderFast.telemetryOpFallback();
            MGLPZRingOpCensus.beginRender();
            final boolean g12Capture = g11Start != 0L && MGLPZRingAvalancheDiag.beginRender(rb);
            rb.vbo[rb.sequence].unmap();
            rb.ibo[rb.sequence].unmap();
            rb.restoreVbos = true;
            SpriteRenderer.RingBuffer.StateRun[] runs = rb.stateRun;
            final int count = rb.numRuns;
            for (int i=0; i<count; ++i) renderRunDirect(runs[i]);
            if (!Model.modelDrawCounts.isEmpty()) Model.modelDrawCounts.clear();
            if (g11Start != 0L) Profiler.gameplayRingSpike(g11Start, count,
                    MGLPZStateRunRenderFast.telemetryCalls() - g11Calls0,
                    MGLPZStateRunRenderFast.telemetryHits() - g11Hits0,
                    MGLPZStateRunRenderFast.telemetryDraws() - g11Draws0,
                    MGLPZStateRunRenderFast.telemetryOpFallback() - g11Ops0, rb.sequence);
            if (g11Start != 0L) MGLPZRingAvalancheDiag.endRender(System.nanoTime() - g11Start, rb);
            return true;
        }

        ++renderCalls;
        if (!Optimizer.rthreadRingRenderFastEnabled()) {
            publishRenderMaybe();
            return false;
        }
        if (rb == null || rb.vbo == null || rb.ibo == null || rb.stateRun == null
                || rb.sequence < 0 || rb.sequence >= rb.vbo.length || rb.sequence >= rb.ibo.length
                || rb.vbo[rb.sequence] == null || rb.ibo[rb.sequence] == null || Model.modelDrawCounts == null) {
            publishRenderMaybe();
            return false;
        }
        final long g11Start = "1".equals(System.getProperty("mglpz.cp613.telemetry", "0")) ? Profiler.gameplayProbeStartNs() : 0L;
        final long g11Calls0 = g11Start == 0L ? 0L : MGLPZStateRunRenderFast.telemetryCalls();
        final long g11Hits0 = g11Start == 0L ? 0L : MGLPZStateRunRenderFast.telemetryHits();
        final long g11Draws0 = g11Start == 0L ? 0L : MGLPZStateRunRenderFast.telemetryDraws();
        final long g11Ops0 = g11Start == 0L ? 0L : MGLPZStateRunRenderFast.telemetryOpFallback();
        MGLPZRingOpCensus.beginRender();
        final boolean g12Capture = g11Start != 0L && MGLPZRingAvalancheDiag.beginRender(rb);
        rb.vbo[rb.sequence].unmap();
        rb.ibo[rb.sequence].unmap();
        rb.restoreVbos = true;
        final int g11Runs = rb.numRuns;
        for (int i=0; i<rb.numRuns; ++i) renderRunDirect(rb.stateRun[i]);
        if (g11Start != 0L) Profiler.gameplayRingSpike(g11Start, g11Runs,
                MGLPZStateRunRenderFast.telemetryCalls() - g11Calls0,
                MGLPZStateRunRenderFast.telemetryHits() - g11Hits0,
                MGLPZStateRunRenderFast.telemetryDraws() - g11Draws0,
                MGLPZStateRunRenderFast.telemetryOpFallback() - g11Ops0, rb.sequence);
        if (g11Start != 0L) MGLPZRingAvalancheDiag.endRender(System.nanoTime() - g11Start, rb);
        if (Model.modelDrawCounts.isEmpty()) {
            ++modelClearSkipped;
        } else {
            Model.modelDrawCounts.clear();
            ++modelClearExecuted;
        }
        ++renderHandled;
        publishRenderMaybe();
        return true;
    }

    /** CP6.13 production path: call the proven StateRun fast helper directly from RingBuffer.render.
     * This removes one instrumented StateRun.render wrapper dispatch for every run (typically 6-8k
     * runs in the measured avalanche). A false result is guaranteed pre-side-effect, so vanilla
     * StateRun.render remains the exact fallback. */
    private static void renderRunDirect(SpriteRenderer.RingBuffer.StateRun run) {
        MGLPZRingOpCensus.beginStateRun(run);
        if (run != null && MGLPZStateRunRenderFast.tryRender(run)) return;
        run.render();
    }

    private static void publishRenderMaybe() {
        if ((renderCalls & 255L) != 0L) return;
        Optimizer.publishRingRenderFast(renderCalls, renderHandled, modelClearSkipped, modelClearExecuted);
    }

    private static int vertex(float[] a, int p, float x, float y, float u, float vv, int color,
                              float u1, float v1, float u2, float v2) {
        a[p++]=x; a[p++]=y; a[p++]=u; a[p++]=vv; a[p++]=Float.intBitsToFloat(color);
        a[p++]=u1; a[p++]=v1; a[p++]=u2; a[p++]=v2;
        return p;
    }

    private static boolean stateChanged(SpriteRenderer.RingBuffer rb, TextureDraw d, TextureDraw prev,
                                        Style style, Texture t0, Texture t1, Texture t2, byte attrib) {
        if (rb.currentRun == null) return true;
        if (attrib != rb.currentUseAttribArray) return true;
        if (t0 != rb.currentTexture0 || t1 != rb.currentTexture1 || t2 != rb.currentTexture2) return true;
        if (prev != null) {
            if (prev.type == TextureDraw.Type.DrawModel) return true;
            // d is guaranteed glDraw here; vanilla starts a new run when transitioning from any
            // non-glDraw operation back to glDraw.
            if (prev.type != TextureDraw.Type.glDraw) return true;
        }
        if (style != rb.currentStyle) {
            if (rb.currentStyle == null) return true;
            if (style.getStyleID() != rb.currentStyle.getStyleID()) return true;
        }
        return false;
    }

    private static void publishMaybe() {
        if ((calls & PUBLISH_MASK) != 0L) return;
        Optimizer.publishRingFast(calls,handled,fallbackToggle,fallbackNonDraw,fallbackCapacity,
                fallbackPreflight,stateChanges,sameState);
        ++publishCalls;
    }

    private static final class Scratch {
        final float[] v = new float[36];
        final short[] i = new short[6];
    }
}
