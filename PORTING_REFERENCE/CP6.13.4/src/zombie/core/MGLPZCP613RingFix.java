package zombie.core;

import java.util.ArrayList;
import zombie.core.textures.TextureDraw;

/** CP6.13 production ring-state de-duplication.
 * Suppresses only exact repeat fixed-function setters while the local shadow state is known.
 * Any draw/unknown operation invalidates the shadow state, so the optimization never assumes state
 * across a command that may mutate GL behind our back.
 */
public final class MGLPZCP613RingFix {
    private static final ThreadLocal<State> TLS=new ThreadLocal<State>(){
        @Override protected State initialValue(){return new State();}
    };
    private static final boolean ENABLED=flag("mglpz.cp613.ringFix",true);
    private static long opRuns,opCalls,skipped,invalidations;
    private MGLPZCP613RingFix(){}
    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    public static boolean enabled(){return ENABLED;}
    public static void beginRender(){if(enabled()&&mglpz.chunkagent.Profiler.cp613GameplayReadyFast())TLS.get().reset();}
    public static void invalidateForDraw(){if(enabled()&&mglpz.chunkagent.Profiler.cp613GameplayReadyFast()){TLS.get().reset();++invalidations;}}

    /** Exact vanilla successful op-loop ordering; null still throws at op.run(). */
    public static boolean runOps(SpriteRenderer.RingBuffer.StateRun run){
        if(!enabled()||!mglpz.chunkagent.Profiler.cp613GameplayReadyFast()||run==null||run.ops==null||run.ops.isEmpty())return false;
        final ArrayList<TextureDraw> a=run.ops; final State s=TLS.get(); final int n=a.size(); ++opRuns;
        for(int i=0;i<n;i++){
            TextureDraw op=a.get(i); ++opCalls;
            if(op!=null && redundant(s,op)){++skipped;continue;}
            op.run(); // preserve vanilla NPE/exception behavior
            if(op!=null)remember(s,op);
        }
        a.clear();
        return true;
    }
    private static boolean redundant(State s,TextureDraw o){
        if(o.type==TextureDraw.Type.glDepthMask)return s.depthMaskKnown&&s.depthMask==o.a;
        if(o.type==TextureDraw.Type.glDepthFunc)return s.depthFuncKnown&&s.depthFunc==o.a;
        if(o.type==TextureDraw.Type.glBlendFuncSeparate)return s.blendSepKnown&&s.a==o.a&&s.b==o.b&&s.c==o.c&&s.d==o.d;
        if(o.type==TextureDraw.Type.glBlendFunc)return s.blendKnown&&s.ba==o.a&&s.bb==o.b;
        if(o.type==TextureDraw.Type.glBlendEquation)return s.blendEqKnown&&s.blendEq==o.a;
        if(o.type==TextureDraw.Type.glStencilMask)return s.stencilMaskKnown&&s.stencilMask==o.a;
        if(o.type==TextureDraw.Type.glStencilFunc)return s.stencilFuncKnown&&s.sfa==o.a&&s.sfb==o.b&&s.sfc==o.c;
        if(o.type==TextureDraw.Type.glStencilOp)return s.stencilOpKnown&&s.soa==o.a&&s.sob==o.b&&s.soc==o.c;
        if(o.type==TextureDraw.Type.glColorMask)return s.colorMaskKnown&&s.cma==o.a&&s.cmb==o.b&&s.cmc==o.c&&s.cmdBits==Float.floatToIntBits(o.x0);
        if(o.type==TextureDraw.Type.glAlphaFunc)return s.alphaKnown&&s.alphaA==o.a&&s.alphaFBits==Float.floatToIntBits(o.f1);
        return false;
    }
    private static void remember(State s,TextureDraw o){
        if(o.type==TextureDraw.Type.glDepthMask){s.depthMaskKnown=true;s.depthMask=o.a;return;}
        if(o.type==TextureDraw.Type.glDepthFunc){s.depthFuncKnown=true;s.depthFunc=o.a;return;}
        if(o.type==TextureDraw.Type.glBlendFuncSeparate){s.blendSepKnown=true;s.a=o.a;s.b=o.b;s.c=o.c;s.d=o.d;s.blendKnown=false;return;}
        if(o.type==TextureDraw.Type.glBlendFunc){s.blendKnown=true;s.ba=o.a;s.bb=o.b;s.blendSepKnown=false;return;}
        if(o.type==TextureDraw.Type.glBlendEquation){s.blendEqKnown=true;s.blendEq=o.a;return;}
        if(o.type==TextureDraw.Type.glStencilMask){s.stencilMaskKnown=true;s.stencilMask=o.a;return;}
        if(o.type==TextureDraw.Type.glStencilFunc){s.stencilFuncKnown=true;s.sfa=o.a;s.sfb=o.b;s.sfc=o.c;return;}
        if(o.type==TextureDraw.Type.glStencilOp){s.stencilOpKnown=true;s.soa=o.a;s.sob=o.b;s.soc=o.c;return;}
        if(o.type==TextureDraw.Type.glColorMask){s.colorMaskKnown=true;s.cma=o.a;s.cmb=o.b;s.cmc=o.c;s.cmdBits=Float.floatToIntBits(o.x0);return;}
        if(o.type==TextureDraw.Type.glAlphaFunc){s.alphaKnown=true;s.alphaA=o.a;s.alphaFBits=Float.floatToIntBits(o.f1);return;}
        // StartShader/ShaderUpdate/DrawModel/FBO/frame/profile/generic operations may have hidden side effects.
        s.reset();++invalidations;
    }
    public static String summary(){return "ring_op_runs="+opRuns+" ring_op_calls="+opCalls+" ring_state_skipped="+skipped+" ring_invalidations="+invalidations;}
    private static final class State{
        boolean depthMaskKnown,depthFuncKnown,blendSepKnown,blendKnown,blendEqKnown,stencilMaskKnown,stencilFuncKnown,stencilOpKnown,colorMaskKnown,alphaKnown;
        int depthMask,depthFunc,a,b,c,d,ba,bb,blendEq,stencilMask,sfa,sfb,sfc,soa,sob,soc,cma,cmb,cmc,cmdBits,alphaA,alphaFBits;
        void reset(){depthMaskKnown=depthFuncKnown=blendSepKnown=blendKnown=blendEqKnown=false;stencilMaskKnown=stencilFuncKnown=stencilOpKnown=colorMaskKnown=alphaKnown=false;}
    }
}
