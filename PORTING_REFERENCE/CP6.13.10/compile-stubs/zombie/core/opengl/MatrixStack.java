package zombie.core.opengl;
import org.joml.Matrix4f;
public class MatrixStack {
  public Matrix4f value = new Matrix4f();
  public boolean empty;
  public boolean isEmpty(){ return empty; }
  public Matrix4f peek(){ if(empty) throw new java.util.EmptyStackException(); return value; }
}
