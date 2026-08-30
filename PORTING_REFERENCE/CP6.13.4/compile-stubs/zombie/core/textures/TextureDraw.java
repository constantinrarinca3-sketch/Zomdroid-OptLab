package zombie.core.textures;
public final class TextureDraw {
  public enum Type {
    glDraw, glBuffer, glStencilFunc, glAlphaFunc, glStencilOp, glEnable, glDisable,
    glColorMask, glStencilMask, glClear, glBindFramebuffer, glBlendFunc,
    glDoStartFrame, glDoStartFrameText, glDoEndFrame, glTexParameteri, StartShader,
    glLoadIdentity, glGenerateMipMaps, glBind, glViewport, DrawModel, DrawSkyBox,
    DrawWater, DrawPuddles, DrawParticles, ShaderUpdate, BindActiveTexture,
    glBlendEquation, glDoStartFrameFx, glDoEndFrameFx, glIgnoreStyles, glClearColor,
    glBlendFuncSeparate, glDepthMask, doCoreIntParam, drawTerrain, pushIsoView,
    popIsoView, FBORenderChunkEnd, FBORenderChunkStart, glDoStartFrameNoZoom,
    glDoStartFrameFlipY, releaseFBORenderChunkLock, glDepthFunc, glClearDepth,
    NewFrame, DrawQueued, RenderQueued, DrawImGui, BeginProfile, EndProfile
  }
  public interface GenericDrawer { void render(TextureDraw d); }
  public Type type = Type.glDraw;
  public boolean flipped;
  public int a,b,c,d;
  public int col0,col1,col2,col3;
  public float f1;
  public float x0,x1,x2,x3,y0,y1,y2,y3;
  public float u0,u1,u2,u3,v0,v1,v2,v3;
  public float z, chunkDepth;
  public Texture tex, tex1, tex2;
  public byte useAttribArray;
  public float tex1U0,tex1U1,tex1U2,tex1U3,tex1V0,tex1V1,tex1V2,tex1V3;
  public float tex2U0,tex2U1,tex2U2,tex2U3,tex2V0,tex2V1,tex2V2,tex2V3;
  public boolean singleCol;
  public GenericDrawer drawer;
  public void run() {}
  public int getColor(int i) {
    if (singleCol) return col0;
    if (i==0) return col0; if (i==1) return col1; if (i==2) return col2; if (i==3) return col3;
    return col0;
  }
}
