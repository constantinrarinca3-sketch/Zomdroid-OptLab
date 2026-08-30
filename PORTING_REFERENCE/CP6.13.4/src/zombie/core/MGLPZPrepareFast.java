package zombie.core;

import zombie.core.Styles.Style;
import zombie.core.textures.TextureDraw;
import mglpz.chunkagent.Optimizer;

/** CP6.7: strict identical-state fastpath for RingBuffer.prepareCurrentRun(glDraw). */
public final class MGLPZPrepareFast {
    private static long calls;
    private static long hits;
    private static final long MASK = 65535L;
    private MGLPZPrepareFast() {}

    public static boolean canReuse(SpriteRenderer.RingBuffer rb, TextureDraw d, TextureDraw prev, Style style) {
        final long n = ++calls;
        if (!Optimizer.rthreadPrepareSameFastEnabled() || rb == null || d == null || style == null) {
            publish(n); return false;
        }
        if (d.type != TextureDraw.Type.glDraw || rb.currentRun == null) { publish(n); return false; }
        if (d.useAttribArray != rb.currentUseAttribArray
                || d.tex != rb.currentTexture0 || d.tex1 != rb.currentTexture1 || d.tex2 != rb.currentTexture2
                || style != rb.currentStyle) { publish(n); return false; }
        if (prev != null && prev.type != TextureDraw.Type.glDraw) { publish(n); return false; }
        ++hits;
        publish(n);
        return true;
    }

    private static void publish(long n) {
        if ((n & MASK) == 0L) Optimizer.publishPrepareSameFast(calls, hits);
    }
}
