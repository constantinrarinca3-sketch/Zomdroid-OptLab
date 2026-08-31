package zombie.core;

import com.zomdroid.agent.optimization.HotPathOptimizationRuntime;
import zombie.core.Styles.Style;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * Bounded CP6.4 StateRun equivalence. It preserves every vanilla boundary and only treats two
 * healthy wrappers as equivalent when they expose the same positive live GL texture id.
 */
public final class ZDOptStateRunFast {
    private ZDOptStateRunFast() {}

    public static boolean stateChanged(Object ring, Object drawObject, Object previousObject,
                                       Object styleObject, Object texture0Object,
                                       Object texture1Object, Object texture2Object, byte attrib) {
        SpriteRenderer.RingBuffer buffer = (SpriteRenderer.RingBuffer) ring;
        TextureDraw draw = (TextureDraw) drawObject;
        TextureDraw previous = (TextureDraw) previousObject;
        Style style = (Style) styleObject;
        Texture texture0 = (Texture) texture0Object;
        Texture texture1 = (Texture) texture1Object;
        Texture texture2 = (Texture) texture2Object;

        if (buffer.currentRun == null) return true;
        if (draw.type == TextureDraw.Type.DrawModel) return true;
        if (attrib != buffer.currentUseAttribArray) return true;

        boolean wrapperDifference = false;
        if (texture0 != buffer.currentTexture0) {
            wrapperDifference = true;
            if (!sameGpuTexture(texture0, buffer.currentTexture0)) return true;
        }
        if (texture1 != buffer.currentTexture1) {
            wrapperDifference = true;
            if (!sameGpuTexture(texture1, buffer.currentTexture1)) return true;
        }
        if (texture2 != buffer.currentTexture2) {
            wrapperDifference = true;
            if (!sameGpuTexture(texture2, buffer.currentTexture2)) return true;
        }

        if (previous != null) {
            if (previous.type == TextureDraw.Type.DrawModel) return true;
            if (draw.type == TextureDraw.Type.glDraw
                    && previous.type != TextureDraw.Type.glDraw) return true;
            if (draw.type != TextureDraw.Type.glDraw
                    && previous.type == TextureDraw.Type.glDraw) return true;
        }

        if (style != buffer.currentStyle) {
            if (buffer.currentStyle == null || style == null
                    || style.getStyleID() != buffer.currentStyle.getStyleID()) return true;
        }

        if (wrapperDifference) HotPathOptimizationRuntime.noteStateRunSaved();
        return false;
    }

    private static boolean sameGpuTexture(Texture first, Texture second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        try {
            int firstId = first.getID();
            if (firstId <= 0 || firstId != second.getID()) return false;
            if (first.bindAlways || second.bindAlways) return false;
            if (first.isDestroyed() || second.isDestroyed()) return false;
            if (!first.isValid() || !second.isValid()) return false;
            return first.isReady() && second.isReady();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
