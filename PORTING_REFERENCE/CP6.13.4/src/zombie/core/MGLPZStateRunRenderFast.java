package zombie.core;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import mglpz.chunkagent.Optimizer;
import mglpz.chunkagent.Profiler;
import zombie.GameProfiler;
import zombie.core.Styles.AdditiveStyle;
import zombie.core.Styles.LightingStyle;
import zombie.core.Styles.Style;
import zombie.core.Styles.TransparentStyle;
import zombie.core.textures.Texture;
import zombie.debug.DebugOptions;

/**
 * CP6.8 common StateRun.render path for exact B42.20.3.
 *
 * This is deliberately conservative: active GameProfiler, queued ops, and assertion-enabled
 * execution fall back before any side effect. The handled path preserves vanilla state order but
 * removes the per-draw PerformanceProfileProbe object path and calls GL draw directly.
 */
public final class MGLPZStateRunRenderFast {
    private static final long MASK = 4095L;
    private static final boolean ASSERTIONS = SpriteRenderer.RingBuffer.StateRun.class.desiredAssertionStatus();
    private static final boolean CP613_EXACT_OPS = flag("mglpz.cp613.ringFix", true);
    private static long calls, hits, draws, opFallback, profilerFallback, exactOpRuns, exactOpCalls;

    private MGLPZStateRunRenderFast() {}

    // CP6.11 RingBuffer snapshot reads these on the same render thread; no extra per-draw work.
    public static long telemetryCalls() { return calls; }
    public static long telemetryHits() { return hits; }
    public static long telemetryDraws() { return draws; }
    public static long telemetryOpFallback() { return opFallback; }

    public static boolean tryRender(SpriteRenderer.RingBuffer.StateRun s) {
        final long n = ++calls;
        if (!Optimizer.rthreadStateRunRenderFastEnabled() || s == null) { publish(n); return false; }
        if (GameProfiler.isRunning()) { ++profilerFallback; publish(n); return false; }
        if (ASSERTIONS) { publish(n); return false; }
        if (s.style == null) { ++hits; publish(n); return true; }
        if (!s.ops.isEmpty()) {
            ++opFallback;
            if (MGLPZRingAvalancheDiag.runOpsIfCapturing(s)) { ++hits; publish(n); return true; }
            if (CP613_EXACT_OPS && Profiler.cp613GameplayReadyFast()) {
                final int count=s.ops.size();
                ++exactOpRuns; exactOpCalls+=count;
                for(int i=0;i<count;i++) s.ops.get(i).run();
                s.ops.clear();
                ++hits; publish(n); return true;
            }
            publish(n); return false;
        }
        final SpriteRenderer.RingBuffer rb = s.this$0;
        final Style style = s.style;

        // Exact vanilla style transition order.
        if (style != rb.lastRenderedStyle) {
            if (rb.lastRenderedStyle != null) {
                if (!SpriteRenderer.RingBuffer.ignoreStyles
                        || (rb.lastRenderedStyle != AdditiveStyle.instance
                            && rb.lastRenderedStyle != TransparentStyle.instance
                            && rb.lastRenderedStyle != LightingStyle.instance)) {
                    rb.lastRenderedStyle.resetState();
                }
            }
            if (!SpriteRenderer.RingBuffer.ignoreStyles
                    || (style != AdditiveStyle.instance
                        && style != TransparentStyle.instance
                        && style != LightingStyle.instance)) {
                style.setupState();
            }
            rb.lastRenderedStyle = style;
        }

        if (rb.lastRenderedTexture0 != null && rb.lastRenderedTexture0.getID() != Texture.lastTextureID) {
            rb.restoreBoundTextures = true;
        }
        if (rb.restoreBoundTextures) {
            Texture.lastTextureID = 0;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            if (s.texture0 == null) GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (DefaultShader.isActive) SceneShaderStore.defaultShader.setTextureActive(false);
            rb.lastRenderedTexture0 = null;
            rb.lastRenderedTexture1 = null;
            rb.lastRenderedTexture2 = null;
            rb.restoreBoundTextures = false;
        }

        if (DefaultShader.isActive) {
            SceneShaderStore.defaultShader.setZ(s.z);
            SceneShaderStore.defaultShader.setChunkDepth(s.chunkDepth);
        }

        if (s.texture0 != rb.lastRenderedTexture0) {
            if (s.texture0 != null) {
                if (rb.lastRenderedTexture0 == null) GL11.glEnable(GL11.GL_TEXTURE_2D);
                s.texture0.bind();
                if (DefaultShader.isActive) SceneShaderStore.defaultShader.setTextureActive(true);
            } else {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                Texture.lastTextureID = 0;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                if (DefaultShader.isActive) SceneShaderStore.defaultShader.setTextureActive(false);
            }
            rb.lastRenderedTexture0 = s.texture0;
        }

        if (DebugOptions.instance.checks.boundTextures.getValue()) {
            rb.debugBoundTexture(rb.lastRenderedTexture0, GL13.GL_TEXTURE0);
        }

        if (s.texture1 != rb.lastRenderedTexture1) {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            if (s.texture1 != null) GL11.glBindTexture(GL11.GL_TEXTURE_2D, s.texture1.getID());
            else GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            rb.lastRenderedTexture1 = s.texture1;
        }

        if (s.texture2 != rb.lastRenderedTexture2) {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            if (s.texture2 != null) GL11.glBindTexture(GL11.GL_TEXTURE_2D, s.texture2.getID());
            else GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            rb.lastRenderedTexture2 = s.texture2;
        }

        if (s.length == 0) { ++hits; publish(n); return true; }
        if (s.length == -1) { rb.restoreVbos = true; ++hits; publish(n); return true; }

        if (rb.restoreVbos) {
            rb.restoreVbos = false;
            rb.vbo[rb.sequence].bind();
            rb.ibo[rb.sequence].bind();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glVertexAttribPointer(0,2,GL11.GL_FLOAT,false,36,0L);
            GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,36,8L);
            GL20.glVertexAttribPointer(2,4,GL11.GL_UNSIGNED_BYTE,true,36,16L);
            GL20.glVertexAttribPointer(3,2,GL11.GL_FLOAT,false,36,20L);
            GL20.glVertexAttribPointer(4,2,GL11.GL_FLOAT,false,36,28L);
        }

        if (style.getRenderSprite()) {
            ShaderHelper.setModelViewProjection();
            GL12.glDrawRangeElements(GL11.GL_TRIANGLES, s.start, s.start + s.length,
                    s.endIndex - s.startIndex, GL11.GL_UNSIGNED_SHORT, s.startIndex * 2L);
            ++draws;
        } else {
            style.render(s.start, s.startIndex);
        }
        ++hits; publish(n); return true;
    }

    public static String cp613Summary() {
        return "ring_mode=exact_ops_no_state_dedup ring_exact_op_runs="+exactOpRuns+" ring_exact_op_calls="+exactOpCalls+" ring_state_skipped=0";
    }

    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}

    private static void publish(long n) {
        if ((n & MASK) == 0L) Optimizer.publishStateRunRenderFast(calls,hits,draws,opFallback,profilerFallback);
    }
}
