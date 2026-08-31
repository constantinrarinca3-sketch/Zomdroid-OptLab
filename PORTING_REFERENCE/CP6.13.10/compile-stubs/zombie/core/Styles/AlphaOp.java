package zombie.core.Styles;
import java.nio.FloatBuffer;
public class AlphaOp {
  public void op(int color, int alpha, FloatBuffer out) { out.put(Float.intBitsToFloat(color)); }
}
