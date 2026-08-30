package zombie.core;
import zombie.core.opengl.ShaderProgram;
public class DefaultShader {
  public static boolean isActive;
  public ShaderProgram program;
  public DefaultShader(){ this(new ShaderProgram()); }
  public DefaultShader(ShaderProgram p){ this.program=p; }
  public ShaderProgram getProgram(){ return program; }
  public void onCompileSuccess(ShaderProgram p){ this.program=p; }
  public void setTextureActive(boolean v){}
  public void setZ(float v){}
  public void setChunkDepth(float v){ if(program!=null) program.setValue("chunkDepth",v); }
}
