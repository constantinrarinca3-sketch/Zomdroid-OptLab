package zombie.core.Styles;

import java.nio.FloatBuffer;

public enum AlphaOp {
    KEEP;

    public final void op(int color, int alpha, FloatBuffer output) {
        output.put(Float.intBitsToFloat(color));
    }
}
