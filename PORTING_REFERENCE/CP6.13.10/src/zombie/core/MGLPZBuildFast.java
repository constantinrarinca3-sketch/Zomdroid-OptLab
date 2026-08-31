package zombie.core;

import zombie.core.Styles.Style;
import zombie.core.textures.TextureDraw;
import mglpz.chunkagent.Optimizer;

/** CP6.13.4 direct RingBuffer builder.
 *
 * CP6.13.3 still fell back to RingBuffer.add() for almost every state/texture transition.  On the
 * measured workload that is the common case, so the huge vanilla add() packing body remained in
 * the hottest buildDrawBuffer path.  This helper mirrors prepareCurrentRun + add directly for the
 * already-known B42.20.3 layout.  It never reorders commands and never suppresses state changes.
 * Capacity rollover and StateRun-array growth deliberately fall back to vanilla.
 */
public final class MGLPZBuildFast {
    private static final boolean DIRECT=flag("mglpz.cp6134.buildDirect",true);
    private static long calls,handled,directDraws,directOps,newRuns,vanillaFallback,capacityFallback,growFallback;
    private static final long MASK=4095L;
    private MGLPZBuildFast(){}

    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}

    public static boolean tryBuild(TextureDraw[] draws,Style[] styles,int count){
        final long n=++calls;
        if(!Optimizer.rthreadBuildLoopFastEnabled()) {publish(n);return false;}
        final SpriteRenderer.RingBuffer rb=SpriteRenderer.ringBuffer;
        if(rb==null){publish(n);return false;}
        TextureDraw prev=null;
        for(int i=0;i<count;i++){
            final TextureDraw d=draws[i];final Style style=styles[i];
            if(!tryDirect(rb,d,prev,style)) {++vanillaFallback;rb.add(d,prev,style);}
            prev=d;
        }
        ++handled;publish(n);return true;
    }

    private static boolean tryDirect(SpriteRenderer.RingBuffer rb,TextureDraw d,TextureDraw prev,Style style){
        if(!DIRECT||d==null||style==null)return false;
        // Vanilla performs this capacity test for every entry, even operation-only entries.
        if((long)rb.vertexCursor+4L>rb.bufferSizeInVertices||(long)rb.indexCursor+6L>rb.indexBufferSize){++capacityFallback;return false;}

        final boolean changed=MGLPZStateRunFast.stateChanged(rb,d,prev,style,d.tex,d.tex1,d.tex2,d.useAttribArray);
        if(changed){
            // Vanilla grows immediately after consuming the last slot.  We cannot call its private
            // growStateRuns() from the helper, therefore give exactly that boundary back to vanilla.
            if(rb.numRuns+1>=rb.stateRun.length){++growFallback;return false;}
            final SpriteRenderer.RingBuffer.StateRun r=rb.stateRun[rb.numRuns];
            rb.currentRun=r;
            r.start=rb.vertexCursor;r.length=0;r.style=style;r.texture0=d.tex;r.z=d.z;r.chunkDepth=d.chunkDepth;
            r.texture1=d.tex1;r.texture2=d.tex2;r.useAttribArray=d.useAttribArray;r.indices=rb.currentIndices;
            r.startIndex=rb.indexCursor;r.endIndex=rb.indexCursor;
            ++rb.numRuns;
            rb.currentStyle=style;rb.currentTexture0=d.tex;rb.currentTexture1=d.tex1;rb.currentTexture2=d.tex2;rb.currentUseAttribArray=d.useAttribArray;
            ++newRuns;
        }
        if(d.type!=TextureDraw.Type.glDraw){rb.currentRun.ops.add(d);++directOps;return true;}
        // Same scalar pack used by the proven CP6.7 path, now also for a freshly-created run.
        MGLPZSameDrawFast.packPrepared(rb,d,style);++directDraws;return true;
    }

    public static String cp6134Summary(){return "build_direct="+DIRECT+" build_calls="+calls+" build_handled="+handled+" direct_draws="+directDraws+" direct_ops="+directOps+" direct_new_runs="+newRuns+" build_vanilla_fallback="+vanillaFallback+" build_capacity_fallback="+capacityFallback+" build_grow_fallback="+growFallback;}
    private static void publish(long n){if((n&MASK)==0L)Optimizer.publishBuildLoopFast(calls,handled,0,0);}
}
