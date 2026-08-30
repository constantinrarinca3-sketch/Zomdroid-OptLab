package zombie.core;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import zombie.core.Styles.AlphaOp;
import zombie.core.Styles.Style;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.VBO.GLVertexBufferObject;
import zombie.core.skinnedmodel.model.Model;
public class SpriteRenderer {
  public static RingBuffer ringBuffer;
  private void buildDrawBuffer(TextureDraw[] draws, Style[] styles, int count) {
    TextureDraw prev=null;
    for(int i=0;i<count;i++) {
      ringBuffer.add(draws[i],prev,styles[i]);
      prev=draws[i];
    }
  }
  public void callBuildDrawBuffer(TextureDraw[] draws, Style[] styles, int count) {
    buildDrawBuffer(draws,styles,count);
  }
  public static final class RingBuffer {
    GLVertexBufferObject[] vbo={new GLVertexBufferObject()};
    GLVertexBufferObject[] ibo={new GLVertexBufferObject()};
    int sequence=0;
    public boolean restoreVbos; public boolean restoreBoundTextures; public static boolean ignoreStyles;
    long bufferSizeInVertices=200000, indexBufferSize=300000;
    FloatBuffer currentVertices=FloatBuffer.allocate(200000*9);
    ShortBuffer currentIndices=ShortBuffer.allocate(300000);
    Texture currentTexture0,currentTexture1,currentTexture2; Texture lastRenderedTexture0,lastRenderedTexture1,lastRenderedTexture2;
    byte currentUseAttribArray=-1;
    Style currentStyle; Style lastRenderedStyle;
    StateRun[] stateRun;
    int vertexCursor,indexCursor,numRuns;
    StateRun currentRun;
    public RingBuffer() {
      stateRun=new StateRun[8]; for(int i=0;i<stateRun.length;i++)stateRun[i]=new StateRun(this);
      SpriteRenderer.ringBuffer=this;
    }
    void growStateRuns(){ StateRun[] n=new StateRun[Math.max(stateRun.length+1,(int)(stateRun.length*1.5f))]; System.arraycopy(stateRun,0,n,0,stateRun.length); for(int i=stateRun.length;i<n.length;i++)n[i]=new StateRun(this); stateRun=n; }
    void add(TextureDraw d, TextureDraw prev, Style style){ vanillaAdd(d,prev,style); }
    void render(){ vanillaRender(); }
    private void drawElements(int start,int length,int startIndex,int endIndex){}
    public void debugBoundTexture(Texture t,int u){}
    public void checkShaderChangedTexture1(){}
    void callDraw(int start,int length,int startIndex,int endIndex){ drawElements(start,length,startIndex,endIndex); }
    void vanillaRender(){ vbo[sequence].unmap(); ibo[sequence].unmap(); restoreVbos=true; for(int i=0;i<numRuns;i++)stateRun[i].render(); Model.modelDrawCounts.clear(); }
    void vanillaAdd(TextureDraw d, TextureDraw prev, Style style) {
      if(style==null)return;
      if((long)vertexCursor+4L>bufferSizeInVertices || (long)indexCursor+6L>indexBufferSize) throw new IllegalStateException("synthetic rollover");
      if(!prepareCurrentRun(d,prev,style))return;
      FloatBuffer b=currentVertices; AlphaOp a=style.getAlphaOp(); int c;
      b.put(d.x0).put(d.y0); if(d.tex==null)b.put(0).put(0);else b.put(d.flipped?d.u1:d.u0).put(d.v0); c=d.getColor(0);a.op(c,255,b); if(d.tex1==null)b.put(0).put(0);else b.put(d.tex1U0).put(d.tex1V0); if(d.tex2==null)b.put(0).put(0);else b.put(d.tex2U0).put(d.tex2V0);
      b.put(d.x1).put(d.y1); if(d.tex==null)b.put(0).put(0);else b.put(d.flipped?d.u0:d.u1).put(d.v1); c=d.getColor(1);a.op(c,255,b); if(d.tex1==null)b.put(0).put(0);else b.put(d.tex1U1).put(d.tex1V1); if(d.tex2==null)b.put(0).put(0);else b.put(d.tex2U1).put(d.tex2V1);
      b.put(d.x2).put(d.y2); if(d.tex==null)b.put(0).put(0);else b.put(d.flipped?d.u3:d.u2).put(d.v2); c=d.getColor(2);a.op(c,255,b); if(d.tex1==null)b.put(0).put(0);else b.put(d.tex1U2).put(d.tex1V2); if(d.tex2==null)b.put(0).put(0);else b.put(d.tex2U2).put(d.tex2V2);
      b.put(d.x3).put(d.y3); if(d.tex==null)b.put(0).put(0);else b.put(d.flipped?d.u2:d.u3).put(d.v3); c=d.getColor(3);a.op(c,255,b); if(d.tex1==null)b.put(0).put(0);else b.put(d.tex1U3).put(d.tex1V3); if(d.tex2==null)b.put(0).put(0);else b.put(d.tex2U3).put(d.tex2V3);
      int c0=d.getColor(0), c2=d.getColor(2), v=vertexCursor;
      if(c0==c2){currentIndices.put((short)v).put((short)(v+1)).put((short)(v+2)).put((short)v).put((short)(v+2)).put((short)(v+3));}
      else {currentIndices.put((short)(v+1)).put((short)(v+2)).put((short)(v+3)).put((short)(v+1)).put((short)(v+3)).put((short)v);}
      indexCursor+=6;vertexCursor+=4;currentRun.endIndex+=6;currentRun.length+=4;
    }
    private boolean prepareCurrentRun(TextureDraw d,TextureDraw prev,Style style){
      Texture t0=d.tex,t1=d.tex1,t2=d.tex2;byte at=d.useAttribArray;
      if(isStateChanged(d,prev,style,t0,t1,t2,at)){
        currentRun=stateRun[numRuns]; currentRun.start=vertexCursor;currentRun.length=0;currentRun.style=style;currentRun.texture0=t0;currentRun.z=d.z;currentRun.chunkDepth=d.chunkDepth;currentRun.texture1=t1;currentRun.texture2=t2;currentRun.useAttribArray=at;currentRun.indices=currentIndices;currentRun.startIndex=indexCursor;currentRun.endIndex=indexCursor;numRuns++;if(numRuns==stateRun.length)growStateRuns();currentStyle=style;currentTexture0=t0;currentTexture1=t1;currentTexture2=t2;currentUseAttribArray=at;
      }
      if(d.type!=TextureDraw.Type.glDraw){currentRun.ops.add(d);return false;}return true;
    }
    private boolean isStateChanged(TextureDraw d,TextureDraw prev,Style style,Texture t0,Texture t1,Texture t2,byte at){
      if(currentRun==null)return true;if(d.type==TextureDraw.Type.DrawModel)return true;if(at!=currentUseAttribArray)return true;if(t0!=currentTexture0||t1!=currentTexture1||t2!=currentTexture2)return true;
      if(prev!=null){if(prev.type==TextureDraw.Type.DrawModel)return true;if(d.type==TextureDraw.Type.glDraw&&prev.type!=TextureDraw.Type.glDraw)return true;if(d.type!=TextureDraw.Type.glDraw&&prev.type==TextureDraw.Type.glDraw)return true;}
      if(style!=currentStyle){if(currentStyle==null)return true;if(style.getStyleID()!=currentStyle.getStyleID())return true;}return false;
    }
    static final class StateRun {
      float z,chunkDepth; Texture texture0,texture1,texture2; byte useAttribArray; Style style;
      int start,length; ShortBuffer indices; int startIndex,endIndex; final ArrayList<TextureDraw> ops=new ArrayList<TextureDraw>();
      int renders;
      final RingBuffer this$0; StateRun(RingBuffer b){this$0=b;}
      void render(){renders++;}
    }
  }
}
