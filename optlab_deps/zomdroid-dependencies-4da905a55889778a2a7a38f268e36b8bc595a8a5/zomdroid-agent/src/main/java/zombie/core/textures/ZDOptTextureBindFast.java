package zombie.core.textures;

import com.zomdroid.agent.optimization.HotPathOptimizationRuntime;
import zombie.debug.DebugOptions;

/** Exact CP6.6 Texture.bind(int) no-op gate with conservative asset/debug guards. */
public final class ZDOptTextureBindFast {
    private ZDOptTextureBindFast() {}

    public static boolean tryAlreadyBound(Object textureObject, int target) {
        if (!HotPathOptimizationRuntime.isTextureBindEnabled()
                || target != 3553 || textureObject == null) return false;
        Texture texture = (Texture) textureObject;
        try {
            if (texture.bindAlways || texture.isDestroyed()
                    || !texture.isValid() || !texture.isReady()) return false;
            DebugOptions options = DebugOptions.instance;
            if (options != null && options.checks != null
                    && options.checks.boundTextures != null
                    && options.checks.boundTextures.getValue()) return false;
            int id = texture.getID();
            if (id <= 0 || id != Texture.lastTextureID) return false;
            HotPathOptimizationRuntime.noteTextureBindSkip();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
