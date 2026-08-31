package zombie.core;

import zombie.core.Styles.Style;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import mglpz.chunkagent.Optimizer;

/**
 * CP6.4 bounded StateRun optimization for exact B42.20.3.
 *
 * Vanilla breaks a StateRun when Texture wrapper object identity changes. PZ atlas/subtexture
 * wrappers can be distinct Java objects while binding the same live GL texture. When both wrappers
 * are ready/valid/not-destroyed, do not force bindAlways, and expose the same positive GL id,
 * treating them as state-equivalent preserves the actual GL texture binding and vertex UV order.
 *
 * Any uncertainty returns "changed", which is exactly the conservative vanilla result for
 * different Texture object identities. No reordering is performed and op/DrawModel/style/attrib
 * boundaries retain vanilla order.
 */
public final class MGLPZStateRunFast {
    private static final long SR_MASK = 8191L;
    private static final long DRAW_MASK = 4095L;

    private static long calls, changed, same;
    private static long initial, drawModel, attrib;
    private static long tex0Break, tex1Break, tex2Break;
    private static long prevModel, opTransition, styleBreak;
    private static long diff0, diff1, diff2;
    private static long equiv0, equiv1, equiv2;
    private static long runsSaved, toggleFallback;

    private static long drawCalls, drawSamples, drawVerticesSample, drawIndicesSample;
    private static long drawV4, drawV16, drawV64, drawV256, drawVLarge;

    private MGLPZStateRunFast() {}

    public static boolean stateChanged(SpriteRenderer.RingBuffer rb, TextureDraw d, TextureDraw prev,
                                       Style style, Texture t0, Texture t1, Texture t2, byte at) {
        if (Optimizer.rthreadLeanTelemetryEnabled()) return stateChangedLean(rb,d,prev,style,t0,t1,t2,at);
        ++calls;

        if (!Optimizer.rthreadStateRunTextureFastEnabled()) {
            ++toggleFallback;
            publishStateMaybe();
            return vanillaStateChanged(rb,d,prev,style,t0,t1,t2,at);
        }

        if (rb.currentRun == null) {
            ++initial; ++changed; publishStateMaybe(); return true;
        }
        if (d.type == TextureDraw.Type.DrawModel) {
            ++drawModel; ++changed; publishStateMaybe(); return true;
        }
        if (at != rb.currentUseAttribArray) {
            ++attrib; ++changed; publishStateMaybe(); return true;
        }

        boolean wrapperDiff = false;
        if (t0 != rb.currentTexture0) {
            ++diff0; wrapperDiff = true;
            if (sameGpuTexture(t0, rb.currentTexture0)) ++equiv0;
            else { ++tex0Break; ++changed; publishStateMaybe(); return true; }
        }
        if (t1 != rb.currentTexture1) {
            ++diff1; wrapperDiff = true;
            if (sameGpuTexture(t1, rb.currentTexture1)) ++equiv1;
            else { ++tex1Break; ++changed; publishStateMaybe(); return true; }
        }
        if (t2 != rb.currentTexture2) {
            ++diff2; wrapperDiff = true;
            if (sameGpuTexture(t2, rb.currentTexture2)) ++equiv2;
            else { ++tex2Break; ++changed; publishStateMaybe(); return true; }
        }

        if (prev != null) {
            if (prev.type == TextureDraw.Type.DrawModel) {
                ++prevModel; ++changed; publishStateMaybe(); return true;
            }
            if (d.type == TextureDraw.Type.glDraw && prev.type != TextureDraw.Type.glDraw) {
                ++opTransition; ++changed; publishStateMaybe(); return true;
            }
            if (d.type != TextureDraw.Type.glDraw && prev.type == TextureDraw.Type.glDraw) {
                ++opTransition; ++changed; publishStateMaybe(); return true;
            }
        }

        if (style != rb.currentStyle) {
            if (rb.currentStyle == null || style == null || style.getStyleID() != rb.currentStyle.getStyleID()) {
                ++styleBreak; ++changed; publishStateMaybe(); return true;
            }
        }

        if (wrapperDiff) ++runsSaved;
        ++same;
        publishStateMaybe();
        return false;
    }

    private static long leanCalls, leanRunsSaved;
    private static final long LEAN_PUBLISH_MASK = 65535L;

    private static boolean stateChangedLean(SpriteRenderer.RingBuffer rb, TextureDraw d, TextureDraw prev,
                                            Style style, Texture t0, Texture t1, Texture t2, byte at) {
        final long n = ++leanCalls;
        if ((n & LEAN_PUBLISH_MASK) == 0L) Optimizer.publishStateRunLean(leanCalls, leanRunsSaved);
        if (!Optimizer.rthreadStateRunTextureFastEnabled()) return vanillaStateChanged(rb,d,prev,style,t0,t1,t2,at);
        if (rb.currentRun == null) return true;
        if (d.type == TextureDraw.Type.DrawModel) return true;
        if (at != rb.currentUseAttribArray) return true;
        boolean wrapperDiff = false;
        if (t0 != rb.currentTexture0) { wrapperDiff = true; if (!sameGpuTexture(t0, rb.currentTexture0)) return true; }
        if (t1 != rb.currentTexture1) { wrapperDiff = true; if (!sameGpuTexture(t1, rb.currentTexture1)) return true; }
        if (t2 != rb.currentTexture2) { wrapperDiff = true; if (!sameGpuTexture(t2, rb.currentTexture2)) return true; }
        if (prev != null) {
            if (prev.type == TextureDraw.Type.DrawModel) return true;
            if (d.type == TextureDraw.Type.glDraw && prev.type != TextureDraw.Type.glDraw) return true;
            if (d.type != TextureDraw.Type.glDraw && prev.type == TextureDraw.Type.glDraw) return true;
        }
        if (style != rb.currentStyle) {
            if (rb.currentStyle == null || style == null || style.getStyleID() != rb.currentStyle.getStyleID()) return true;
        }
        if (wrapperDiff) ++leanRunsSaved;
        return false;
    }

    private static boolean sameGpuTexture(Texture a, Texture b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        try {
            // Cheap reject first: most true state changes are different GL ids.
            int ai = a.getID();
            if (ai <= 0 || ai != b.getID()) return false;
            // Same live GL id is still insufficient if either wrapper requests special bind
            // semantics or is not in a renderable asset state.
            if (a.bindAlways || b.bindAlways) return false;
            if (a.isDestroyed() || b.isDestroyed()) return false;
            if (!a.isValid() || !b.isValid()) return false;
            if (!a.isReady() || !b.isReady()) return false;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean vanillaStateChanged(SpriteRenderer.RingBuffer rb, TextureDraw d, TextureDraw prev,
                                               Style style, Texture t0, Texture t1, Texture t2, byte at) {
        if (rb.currentRun == null) return true;
        if (d.type == TextureDraw.Type.DrawModel) return true;
        if (at != rb.currentUseAttribArray) return true;
        if (t0 != rb.currentTexture0 || t1 != rb.currentTexture1 || t2 != rb.currentTexture2) return true;
        if (prev != null) {
            if (prev.type == TextureDraw.Type.DrawModel) return true;
            if (d.type == TextureDraw.Type.glDraw && prev.type != TextureDraw.Type.glDraw) return true;
            if (d.type != TextureDraw.Type.glDraw && prev.type == TextureDraw.Type.glDraw) return true;
        }
        if (style != rb.currentStyle) {
            if (rb.currentStyle == null) return true;
            if (style.getStyleID() != rb.currentStyle.getStyleID()) return true;
        }
        return false;
    }

    public static void noteDraw(int length, int startIndex, int endIndex) {
        long n = ++drawCalls;
        // Exact draw-call count, but sample size distribution only 1/64 to keep the census
        // substantially cheaper than the GL submission it observes.
        if ((n & 63L) != 0L) return;
        ++drawSamples;
        if (length > 0) drawVerticesSample += length;
        int indices = endIndex - startIndex;
        if (indices > 0) drawIndicesSample += indices;
        if (length <= 4) ++drawV4;
        else if (length <= 16) ++drawV16;
        else if (length <= 64) ++drawV64;
        else if (length <= 256) ++drawV256;
        else ++drawVLarge;
        if ((n & DRAW_MASK) == 0L) {
            Optimizer.publishDrawCensus(drawCalls,drawSamples,drawVerticesSample,drawIndicesSample,
                    drawV4,drawV16,drawV64,drawV256,drawVLarge);
        }
    }

    private static void publishStateMaybe() {
        if ((calls & SR_MASK) != 0L) return;
        Optimizer.publishStateRunFast(calls,changed,same,initial,drawModel,attrib,
                tex0Break,tex1Break,tex2Break,prevModel,opTransition,styleBreak,
                diff0,diff1,diff2,equiv0,equiv1,equiv2,runsSaved,toggleFallback);
    }
}
