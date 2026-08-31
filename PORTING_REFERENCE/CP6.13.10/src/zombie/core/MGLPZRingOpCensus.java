package zombie.core;

import mglpz.chunkagent.Profiler;
import zombie.core.textures.TextureDraw;

/**
 * CP6.13.8 + CP6.13.9 observe-only RingBuffer operation census.
 *
 * CP6.13.8 keeps the original ultra-conservative bounded-state census: unknown/side-effect ops,
 * StartShader, and draw boundaries invalidate the baseline shadow.
 *
 * CP6.13.9 adds a second, independent diagnostic shadow. It changes NO execution. The second shadow:
 *   - keeps only glDepthMask/glDepthFunc state across StartShader;
 *   - measures whether the first subsequent depth setter repeats the pre-shader value;
 *   - tracks repeated StartShader program ids when no unknown op could have changed the program;
 *   - still invalidates on draw boundaries and every non-StartShader op outside the bounded safe
 *     fixed-function setter family.
 *
 * A "same_shader_bind" is only a candidate to avoid a redundant program bind. It is NOT evidence
 * that the whole StartShader operation can be skipped: shader callbacks/uniform setters must still run.
 * All hot-path counters are primitive/allocation-free. Strings are built only for an existing ring
 * spike or the final checkpoint summary.
 */
public final class MGLPZRingOpCensus {
    public static final boolean ENABLED = flag("mglpz.cp6138.opCensus", true);
    public static final boolean CP6139_ENABLED = flag("mglpz.cp6139.shaderDepthCensus", true);

    private static final TextureDraw.Type[] TYPES = TextureDraw.Type.values();
    private static final long[] GLOBAL_TYPES = new long[TYPES.length];
    private static final long[] GLOBAL_CANDIDATES = new long[TYPES.length];
    private static final long[] RENDER_TYPES = new long[TYPES.length];
    private static final long[] RENDER_CANDIDATES = new long[TYPES.length];

    private static boolean activeRender;
    private static long globalOps, globalOpRuns, globalDrawRuns, globalCandidateSkips, globalInvalidations;
    private static long renderOps, renderOpRuns, renderDrawRuns, renderCandidateSkips, renderInvalidations;
    private static int renderMaxOpsPerRun;

    // CP6.13.8 conservative shadow state. Diagnostic only: no GL call is skipped.
    private static boolean depthMaskKnown,depthFuncKnown,blendSepKnown,blendKnown,blendEqKnown;
    private static boolean stencilMaskKnown,stencilFuncKnown,stencilOpKnown,colorMaskKnown,alphaKnown;
    private static int depthMask,depthFunc,ba,bb,blendEq,stencilMask,sfa,sfb,sfc,soa,sob,soc;
    private static int cma,cmb,cmc,cmdBits,alphaA,alphaFBits,sa,sb,sc,sd;

    // CP6.13.9 independent shader/depth diagnostic shadow.
    private static boolean xDepthMaskKnown,xDepthFuncKnown,xDepthMaskCrossArmed,xDepthFuncCrossArmed;
    private static int xDepthMask,xDepthFunc;
    private static boolean shaderKnown;
    private static int shaderId;
    private static boolean prevObservedWasStartShader;
    private static int prevObservedShaderId;

    private static long globalShaderCalls,globalShaderCompareOps,globalSameShaderBinds,globalImmediateSameShader;
    private static long globalDepthMaskCrossOps,globalDepthMaskCrossCandidates;
    private static long globalDepthFuncCrossOps,globalDepthFuncCrossCandidates;
    private static long renderShaderCalls,renderShaderCompareOps,renderSameShaderBinds,renderImmediateSameShader;
    private static long renderDepthMaskCrossOps,renderDepthMaskCrossCandidates;
    private static long renderDepthFuncCrossOps,renderDepthFuncCrossCandidates;

    private MGLPZRingOpCensus() {}

    /** One gate per RingBuffer render, after the normal gameplay warmup. */
    public static void beginRender() {
        activeRender = ENABLED && Profiler.cp613GameplayReadyFast();
        if (!activeRender) return;
        clear(RENDER_TYPES); clear(RENDER_CANDIDATES);
        renderOps=renderOpRuns=renderDrawRuns=renderCandidateSkips=renderInvalidations=0L;
        renderMaxOpsPerRun=0;
        renderShaderCalls=renderShaderCompareOps=renderSameShaderBinds=renderImmediateSameShader=0L;
        renderDepthMaskCrossOps=renderDepthMaskCrossCandidates=0L;
        renderDepthFuncCrossOps=renderDepthFuncCrossCandidates=0L;
        resetShadow();
        resetExtended();
    }

    /** Called once per StateRun so draw boundaries conservatively invalidate both diagnostic shadows. */
    public static void beginStateRun(SpriteRenderer.RingBuffer.StateRun run) {
        if (!activeRender || run == null) return;
        int n = run.ops == null ? 0 : run.ops.size();
        if (n > 0) {
            ++renderOpRuns; ++globalOpRuns;
            if (n > renderMaxOpsPerRun) renderMaxOpsPerRun=n;
        } else {
            ++renderDrawRuns; ++globalDrawRuns;
            resetShadow();
            resetExtended();
        }
    }

    /** Observe the exact operation before it executes. Never changes execution or ordering. */
    public static void observe(TextureDraw op) {
        if (!activeRender) return;
        ++renderOps; ++globalOps;
        if (op == null || op.type == null) {
            invalidate();
            resetExtended();
            return;
        }
        final int ord=op.type.ordinal();
        if (ord >= 0 && ord < RENDER_TYPES.length) {
            ++RENDER_TYPES[ord]; ++GLOBAL_TYPES[ord];
        }
        if (redundant(op)) {
            ++renderCandidateSkips; ++globalCandidateSkips;
            if (ord >= 0 && ord < RENDER_CANDIDATES.length) {
                ++RENDER_CANDIDATES[ord]; ++GLOBAL_CANDIDATES[ord];
            }
        }
        if (CP6139_ENABLED) observeCp6139(op);
        rememberOrInvalidate(op);
    }

    /** Appended only when the existing CP6.13 telemetry has already classified a slow ring. */
    public static String spikeSummary() {
        if (!activeRender) return "op_census=off";
        StringBuilder b=new StringBuilder(560);
        b.append("op_census_calls=").append(renderOps)
         .append(" op_runs=").append(renderOpRuns)
         .append(" draw_runs=").append(renderDrawRuns)
         .append(" max_ops_per_run=").append(renderMaxOpsPerRun)
         .append(" candidate_skips=").append(renderCandidateSkips)
         .append(" candidate_pct=").append(pct(renderCandidateSkips,renderOps))
         .append(" invalidations=").append(renderInvalidations)
         .append(" top_ops=");
        appendTop(b,RENDER_TYPES,8);
        b.append(" top_candidates=");
        appendTop(b,RENDER_CANDIDATES,6);
        appendCp6139(b,false);
        return b.toString();
    }

    public static String summary() {
        if (!ENABLED) return "cp6138_op_census=false cp6139_shader_depth_census="+CP6139_ENABLED;
        StringBuilder b=new StringBuilder(700);
        b.append("cp6138_op_census=true")
         .append(" op_calls=").append(globalOps)
         .append(" op_runs=").append(globalOpRuns)
         .append(" draw_runs=").append(globalDrawRuns)
         .append(" candidate_skips=").append(globalCandidateSkips)
         .append(" candidate_pct=").append(pct(globalCandidateSkips,globalOps))
         .append(" invalidations=").append(globalInvalidations)
         .append(" top_ops=");
        appendTop(b,GLOBAL_TYPES,12);
        b.append(" top_candidates=");
        appendTop(b,GLOBAL_CANDIDATES,10);
        appendCp6139(b,true);
        return b.toString();
    }

    private static void appendCp6139(StringBuilder b, boolean global) {
        b.append(" cp6139_shader_depth_census=").append(CP6139_ENABLED);
        if (!CP6139_ENABLED) return;
        final long shaderCalls = global ? globalShaderCalls : renderShaderCalls;
        final long shaderOps = global ? globalShaderCompareOps : renderShaderCompareOps;
        final long sameShader = global ? globalSameShaderBinds : renderSameShaderBinds;
        final long immediateSame = global ? globalImmediateSameShader : renderImmediateSameShader;
        final long dmOps = global ? globalDepthMaskCrossOps : renderDepthMaskCrossOps;
        final long dmCandidates = global ? globalDepthMaskCrossCandidates : renderDepthMaskCrossCandidates;
        final long dfOps = global ? globalDepthFuncCrossOps : renderDepthFuncCrossOps;
        final long dfCandidates = global ? globalDepthFuncCrossCandidates : renderDepthFuncCrossCandidates;
        final long depthOps = dmOps + dfOps;
        final long depthCandidates = dmCandidates + dfCandidates;
        b.append(" shader_calls=").append(shaderCalls)
         .append(" shader_compare_ops=").append(shaderOps)
         .append(" same_shader_bind_candidates=").append(sameShader)
         .append(" same_shader_pct=").append(pct(sameShader,shaderOps))
         .append(" immediate_same_shader=").append(immediateSame)
         .append(" depth_cross_shader_ops=").append(depthOps)
         .append(" depth_cross_shader_candidates=").append(depthCandidates)
         .append(" depth_cross_shader_pct=").append(pct(depthCandidates,depthOps))
         .append(" depthmask_cross_ops=").append(dmOps)
         .append(" depthmask_cross_candidates=").append(dmCandidates)
         .append(" depthmask_cross_pct=").append(pct(dmCandidates,dmOps))
         .append(" depthfunc_cross_ops=").append(dfOps)
         .append(" depthfunc_cross_candidates=").append(dfCandidates)
         .append(" depthfunc_cross_pct=").append(pct(dfCandidates,dfOps));
    }

    /** CP6.13.9 diagnostic model; never suppresses or reorders an operation. */
    private static void observeCp6139(TextureDraw o) {
        final TextureDraw.Type t=o.type;
        if (t==TextureDraw.Type.StartShader) {
            ++renderShaderCalls; ++globalShaderCalls;
            if (shaderKnown) {
                ++renderShaderCompareOps; ++globalShaderCompareOps;
                if (shaderId==o.a) { ++renderSameShaderBinds; ++globalSameShaderBinds; }
            }
            if (prevObservedWasStartShader && prevObservedShaderId==o.a) {
                ++renderImmediateSameShader; ++globalImmediateSameShader;
            }
            shaderKnown=true; shaderId=o.a;
            if (xDepthMaskKnown) xDepthMaskCrossArmed=true;
            if (xDepthFuncKnown) xDepthFuncCrossArmed=true;
            prevObservedWasStartShader=true; prevObservedShaderId=o.a;
            return;
        }

        prevObservedWasStartShader=false;

        if (t==TextureDraw.Type.glDepthMask) {
            if (xDepthMaskKnown && xDepthMaskCrossArmed) {
                ++renderDepthMaskCrossOps; ++globalDepthMaskCrossOps;
                if (xDepthMask==o.a) { ++renderDepthMaskCrossCandidates; ++globalDepthMaskCrossCandidates; }
            }
            xDepthMaskKnown=true; xDepthMask=o.a; xDepthMaskCrossArmed=false;
            return;
        }
        if (t==TextureDraw.Type.glDepthFunc) {
            if (xDepthFuncKnown && xDepthFuncCrossArmed) {
                ++renderDepthFuncCrossOps; ++globalDepthFuncCrossOps;
                if (xDepthFunc==o.a) { ++renderDepthFuncCrossCandidates; ++globalDepthFuncCrossCandidates; }
            }
            xDepthFuncKnown=true; xDepthFunc=o.a; xDepthFuncCrossArmed=false;
            return;
        }

        // These bounded fixed-function setters cannot change shader program or depth mask/func.
        if (t==TextureDraw.Type.glBlendFuncSeparate || t==TextureDraw.Type.glBlendFunc
                || t==TextureDraw.Type.glBlendEquation || t==TextureDraw.Type.glStencilMask
                || t==TextureDraw.Type.glStencilFunc || t==TextureDraw.Type.glStencilOp
                || t==TextureDraw.Type.glColorMask || t==TextureDraw.Type.glAlphaFunc) {
            return;
        }

        // Any other operation may have side effects outside this diagnostic model.
        resetExtended();
    }

    private static boolean redundant(TextureDraw o) {
        if(o.type==TextureDraw.Type.glDepthMask)return depthMaskKnown&&depthMask==o.a;
        if(o.type==TextureDraw.Type.glDepthFunc)return depthFuncKnown&&depthFunc==o.a;
        if(o.type==TextureDraw.Type.glBlendFuncSeparate)return blendSepKnown&&sa==o.a&&sb==o.b&&sc==o.c&&sd==o.d;
        if(o.type==TextureDraw.Type.glBlendFunc)return blendKnown&&ba==o.a&&bb==o.b;
        if(o.type==TextureDraw.Type.glBlendEquation)return blendEqKnown&&blendEq==o.a;
        if(o.type==TextureDraw.Type.glStencilMask)return stencilMaskKnown&&stencilMask==o.a;
        if(o.type==TextureDraw.Type.glStencilFunc)return stencilFuncKnown&&sfa==o.a&&sfb==o.b&&sfc==o.c;
        if(o.type==TextureDraw.Type.glStencilOp)return stencilOpKnown&&soa==o.a&&sob==o.b&&soc==o.c;
        if(o.type==TextureDraw.Type.glColorMask)return colorMaskKnown&&cma==o.a&&cmb==o.b&&cmc==o.c&&cmdBits==Float.floatToIntBits(o.x0);
        if(o.type==TextureDraw.Type.glAlphaFunc)return alphaKnown&&alphaA==o.a&&alphaFBits==Float.floatToIntBits(o.f1);
        return false;
    }

    private static void rememberOrInvalidate(TextureDraw o) {
        if(o.type==TextureDraw.Type.glDepthMask){depthMaskKnown=true;depthMask=o.a;return;}
        if(o.type==TextureDraw.Type.glDepthFunc){depthFuncKnown=true;depthFunc=o.a;return;}
        if(o.type==TextureDraw.Type.glBlendFuncSeparate){blendSepKnown=true;sa=o.a;sb=o.b;sc=o.c;sd=o.d;blendKnown=false;return;}
        if(o.type==TextureDraw.Type.glBlendFunc){blendKnown=true;ba=o.a;bb=o.b;blendSepKnown=false;return;}
        if(o.type==TextureDraw.Type.glBlendEquation){blendEqKnown=true;blendEq=o.a;return;}
        if(o.type==TextureDraw.Type.glStencilMask){stencilMaskKnown=true;stencilMask=o.a;return;}
        if(o.type==TextureDraw.Type.glStencilFunc){stencilFuncKnown=true;sfa=o.a;sfb=o.b;sfc=o.c;return;}
        if(o.type==TextureDraw.Type.glStencilOp){stencilOpKnown=true;soa=o.a;sob=o.b;soc=o.c;return;}
        if(o.type==TextureDraw.Type.glColorMask){colorMaskKnown=true;cma=o.a;cmb=o.b;cmc=o.c;cmdBits=Float.floatToIntBits(o.x0);return;}
        if(o.type==TextureDraw.Type.glAlphaFunc){alphaKnown=true;alphaA=o.a;alphaFBits=Float.floatToIntBits(o.f1);return;}
        invalidate();
    }

    private static void invalidate(){++renderInvalidations;++globalInvalidations;resetShadow();}
    private static void resetShadow(){depthMaskKnown=depthFuncKnown=blendSepKnown=blendKnown=blendEqKnown=false;stencilMaskKnown=stencilFuncKnown=stencilOpKnown=colorMaskKnown=alphaKnown=false;}
    private static void resetExtended(){xDepthMaskKnown=xDepthFuncKnown=xDepthMaskCrossArmed=xDepthFuncCrossArmed=false;shaderKnown=false;prevObservedWasStartShader=false;}
    private static void clear(long[] a){for(int i=0;i<a.length;i++)a[i]=0L;}

    private static void appendTop(StringBuilder b,long[] counts,int limit){
        boolean first=true;
        boolean[] used=new boolean[counts.length]; // summary/spike only, never the hot op loop
        for(int k=0;k<limit;k++){
            int best=-1; long n=0L;
            for(int i=0;i<counts.length;i++)if(!used[i]&&counts[i]>n){best=i;n=counts[i];}
            if(best<0||n==0L)break;
            used[best]=true;
            if(!first)b.append(';'); first=false;
            b.append(TYPES[best].name()).append(':').append(n);
        }
        if(first)b.append("none");
    }

    private static String pct(long a,long b){if(b<=0L)return "0.00";return String.format(java.util.Locale.ROOT,"%.2f",100.0*a/b);}
    private static boolean flag(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
}
