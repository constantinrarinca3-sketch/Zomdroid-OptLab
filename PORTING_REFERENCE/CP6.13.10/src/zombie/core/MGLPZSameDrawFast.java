package zombie.core;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import zombie.core.Styles.AlphaOp;
import zombie.core.Styles.Style;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import mglpz.chunkagent.Optimizer;

/** CP6.7 scalar draw pack, exposed in CP6.13.4 for already-prepared changed-state runs. */
public final class MGLPZSameDrawFast {
    private static long calls,hits,capacityFallback;
    private static final long MASK=65535L;
    private MGLPZSameDrawFast(){}

    public static boolean tryAdd(SpriteRenderer.RingBuffer rb,TextureDraw d,TextureDraw prev,Style style){
        long n=++calls;
        if(!Optimizer.rthreadSameDrawPackFastEnabled()||rb==null||d==null||style==null||d.type!=TextureDraw.Type.glDraw){publish(n);return false;}
        if((long)rb.vertexCursor+4L>rb.bufferSizeInVertices||(long)rb.indexCursor+6L>rb.indexBufferSize){++capacityFallback;publish(n);return false;}
        if(!MGLPZPrepareFast.canReuse(rb,d,prev,style)){publish(n);return false;}
        packPrepared(rb,d,style);++hits;publish(n);return true;
    }

    /** Exact scalar payload for a glDraw after currentRun has already been prepared. */
    public static void packPrepared(SpriteRenderer.RingBuffer rb,TextureDraw d,Style style){
        final FloatBuffer b=rb.currentVertices;final ShortBuffer si=rb.currentIndices;
        // Vanilla's add() also relies on the buffer-capacity invariant.  Keep failure semantics
        // rather than silently falling back after a StateRun has already been committed.
        final AlphaOp alpha=style.getAlphaOp();
        final Texture t0=d.tex,t1=d.tex1,t2=d.tex2;
        final int c0=d.col0,c1=d.singleCol?c0:d.col1,c2=d.singleCol?c0:d.col2,c3=d.singleCol?c0:d.col3;
        putVertex(b,d.x0,d.y0,t0==null?0f:(d.flipped?d.u1:d.u0),t0==null?0f:d.v0,c0,t1==null?0f:d.tex1U0,t1==null?0f:d.tex1V0,t2==null?0f:d.tex2U0,t2==null?0f:d.tex2V0,alpha);
        putVertex(b,d.x1,d.y1,t0==null?0f:(d.flipped?d.u0:d.u1),t0==null?0f:d.v1,c1,t1==null?0f:d.tex1U1,t1==null?0f:d.tex1V1,t2==null?0f:d.tex2U1,t2==null?0f:d.tex2V1,alpha);
        putVertex(b,d.x2,d.y2,t0==null?0f:(d.flipped?d.u3:d.u2),t0==null?0f:d.v2,c2,t1==null?0f:d.tex1U2,t1==null?0f:d.tex1V2,t2==null?0f:d.tex2U2,t2==null?0f:d.tex2V2,alpha);
        putVertex(b,d.x3,d.y3,t0==null?0f:(d.flipped?d.u2:d.u3),t0==null?0f:d.v3,c3,t1==null?0f:d.tex1U3,t1==null?0f:d.tex1V3,t2==null?0f:d.tex2U3,t2==null?0f:d.tex2V3,alpha);
        final int v=rb.vertexCursor;
        if(c0==c2)si.put((short)v).put((short)(v+1)).put((short)(v+2)).put((short)v).put((short)(v+2)).put((short)(v+3));
        else si.put((short)(v+1)).put((short)(v+2)).put((short)(v+3)).put((short)(v+1)).put((short)(v+3)).put((short)v);
        rb.indexCursor+=6;rb.vertexCursor+=4;rb.currentRun.endIndex+=6;rb.currentRun.length+=4;
    }
    private static void putVertex(FloatBuffer b,float x,float y,float u,float v,int color,float u1,float v1,float u2,float v2,AlphaOp alpha){
        b.put(x).put(y).put(u).put(v);alpha.op(color,255,b);b.put(u1).put(v1).put(u2).put(v2);
    }
    private static void publish(long n){if((n&MASK)==0L)Optimizer.publishSameDrawPackFast(calls,hits,capacityFallback);}
}
