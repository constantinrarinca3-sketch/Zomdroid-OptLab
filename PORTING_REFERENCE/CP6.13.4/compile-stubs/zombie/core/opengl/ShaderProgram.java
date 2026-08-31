package zombie.core.opengl;
import org.lwjgl.opengl.GL20;
import org.joml.Matrix4f;
public class ShaderProgram {
  public static class Uniform { public final int loc; public Uniform(int loc){this.loc=loc;} }
  public Uniform u;
  public int lookups;
  public int uploads;
  public int shaderID=1;
  public boolean compiled=true;
  public final Matrix4f modelView = new Matrix4f();
  public final Matrix4f projection = new Matrix4f();
  public ShaderProgram(){ this.u=new Uniform(3); }
  public Uniform getUniform(String name, int type){ lookups++; return u; }
  public void setValue(String name,float v){ Uniform x=getUniform(name,5126); if(x!=null)GL20.glUniform1f(x.loc,v); }
  public boolean isCompiled(){ return compiled; }
  public int getShaderID(){ return shaderID; }
  void setTransformMatrix(int loc, Matrix4f m){ uploads++; }
}
