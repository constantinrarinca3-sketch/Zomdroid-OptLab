package zombie.core;

import java.util.ArrayList;
import mglpz.chunkagent.Profiler;
import zombie.core.Styles.Style;
import zombie.core.textures.TextureDraw;

/**
 * CP6.12 bounded RingBuffer/StateRun avalanche diagnostic for exact B42.20.3.
 *
 * A slow RingBuffer render arms only the next few renders. During those renders we inspect the
 * already-built StateRuns and time only non-glDraw TextureDraw.run() operations. Outside an armed
 * window the hot path is a pair of cheap thread-local/int checks and vanilla CP6.11 behavior.
 */
public final class MGLPZRingAvalancheDiag {
    private static final long NS_PER_MS = 1000000L;
    private static final ThreadLocal<Ctx> CTX = new ThreadLocal<Ctx>() {
        @Override protected Ctx initialValue() { return new Ctx(); }
    };

    private MGLPZRingAvalancheDiag() {}

    public static void bootLog() {
        Profiler.note("MGLPZ_CP6_12_RING_DIAG_CONFIG enabled=" + enabled()
                + " trigger_ms=" + (triggerNs() / NS_PER_MS)
                + " window_renders=" + windowRenders()
                + " mode=bounded_next-renders rollback=-Dmglpz.cp612.ringDiag=0");
    }

    private static boolean enabled() {
        return !"0".equals(System.getProperty("mglpz.cp612.ringDiag", "0"));
    }

    private static long triggerNs() {
        long ms = 50L;
        String v = System.getProperty("mglpz.cp612.ringDiagTriggerMs");
        if (v != null) try { ms = Long.parseLong(v); } catch (Throwable ignored) {}
        if (ms < 20L) ms = 20L; else if (ms > 1000L) ms = 1000L;
        return ms * NS_PER_MS;
    }

    private static int windowRenders() {
        int n = 4;
        String v = System.getProperty("mglpz.cp612.ringDiagWindow");
        if (v != null) try { n = Integer.parseInt(v); } catch (Throwable ignored) {}
        if (n < 1) n = 1; else if (n > 16) n = 16;
        return n;
    }

    /** Called before RingBuffer unmap/render while StateRun.ops are still intact. */
    public static boolean beginRender(SpriteRenderer.RingBuffer rb) {
        if (!enabled() || !Profiler.gameplayProfilingActive() || rb == null) return false;
        Ctx c = CTX.get();
        c.capture = c.remaining > 0;
        if (!c.capture) return false;
        --c.remaining;
        c.reset(rb);
        return true;
    }

    /**
     * Executes the exact vanilla operation branch for StateRun.render, but only while CP6.12 deep
     * capture is armed. No exception is swallowed and ops.clear() occurs only after all ops ran,
     * matching vanilla exception/partial-run semantics.
     */
    public static boolean runOpsIfCapturing(SpriteRenderer.RingBuffer.StateRun run) {
        Ctx c = CTX.get();
        if (!c.capture || run == null || run.ops == null || run.ops.isEmpty()) return false;
        final ArrayList<TextureDraw> ops = run.ops;
        final int n = ops.size();
        for (int i = 0; i < n; ++i) {
            TextureDraw op = ops.get(i);
            final int ord = op.type == null ? -1 : op.type.ordinal();
            long t0 = System.nanoTime();
            op.run();
            long dt = System.nanoTime() - t0;
            c.opCalls++;
            c.opNs += dt;
            if (ord >= 0 && ord < c.typeCounts.length) {
                c.typeCounts[ord]++;
                c.typeNs[ord] += dt;
            } else {
                c.unknownType++;
                c.unknownNs += dt;
            }
        }
        ops.clear();
        return true;
    }

    /** Called after the RingBuffer render. The first slow render arms a bounded future window. */
    public static void endRender(long durationNs, SpriteRenderer.RingBuffer rb) {
        if (!enabled() || !Profiler.gameplayProfilingActive()) return;
        Ctx c = CTX.get();
        final boolean captured = c.capture;
        c.capture = false;

        if (captured) {
            logDetail(c, durationNs, rb);
        } else if (durationNs >= triggerNs()) {
            c.remaining = windowRenders();
            Profiler.note("MGLPZ_CP6_12_RING_DEEP_ARM trigger_ms=" + ms(durationNs)
                    + " next_renders=" + c.remaining + " thread=" + Thread.currentThread().getName());
        }
    }

    private static void logDetail(Ctx c, long durationNs, SpriteRenderer.RingBuffer rb) {
        StringBuilder b = new StringBuilder(640);
        b.append("MGLPZ_CP6_12_RING_DETAIL wall_ms=").append(ms(durationNs))
         .append(" sequence=").append(rb == null ? -1 : rb.sequence)
         .append(" runs=").append(c.runs)
         .append(" draw_runs=").append(c.drawRuns)
         .append(" op_runs=").append(c.opRuns)
         .append(" op_calls=").append(c.opCalls)
         .append(" op_ms=").append(ms(c.opNs))
         .append(" max_ops_per_run=").append(c.maxOpsPerRun)
         .append(" single_op_runs=").append(c.singleOpRuns)
         .append(" consecutive_op_runs=").append(c.consecutiveOpRuns)
         .append(" style_changes=").append(c.styleChanges)
         .append(" tex0_changes=").append(c.tex0Changes)
         .append(" tex1_changes=").append(c.tex1Changes)
         .append(" tex2_changes=").append(c.tex2Changes)
         .append(" attrib_changes=").append(c.attribChanges)
         .append(" draw_vertices=").append(c.drawVertices)
         .append(" top_ops=");
        appendTopTypes(b, c);
        if (c.unknownType != 0) {
            b.append(";UNKNOWN:").append(c.unknownType).append('@').append(ms(c.unknownNs));
        }
        b.append(" remaining_window=").append(c.remaining)
         .append(" thread=").append(Thread.currentThread().getName());
        Profiler.note(b.toString());
    }

    private static void appendTopTypes(StringBuilder b, Ctx c) {
        TextureDraw.Type[] vals = TextureDraw.Type.values();
        boolean[] used = c.topUsed;
        for (int i=0;i<used.length;i++) used[i]=false;
        int emitted = 0;
        while (emitted < 8) {
            int best=-1; long bestCount=0L;
            int lim=Math.min(vals.length,c.typeCounts.length);
            for (int i=0;i<lim;i++) {
                if (!used[i] && c.typeCounts[i] > bestCount) { best=i; bestCount=c.typeCounts[i]; }
            }
            if (best < 0 || bestCount == 0L) break;
            if (emitted++ != 0) b.append(';');
            used[best]=true;
            b.append(vals[best].name()).append(':').append(bestCount).append('@').append(ms(c.typeNs[best]));
        }
        if (emitted == 0) b.append("none");
    }

    private static String ms(long ns) {
        return String.format(java.util.Locale.ROOT, "%.3f", ns / 1000000.0);
    }

    private static final class Ctx {
        int remaining;
        boolean capture;
        int runs, drawRuns, opRuns, maxOpsPerRun, singleOpRuns, consecutiveOpRuns;
        int styleChanges, tex0Changes, tex1Changes, tex2Changes, attribChanges;
        long drawVertices, opCalls, opNs, unknownType, unknownNs;
        long[] typeCounts = new long[TextureDraw.Type.values().length];
        long[] typeNs = new long[TextureDraw.Type.values().length];
        boolean[] topUsed = new boolean[TextureDraw.Type.values().length];

        void ensureTypes() {
            int n=TextureDraw.Type.values().length;
            if (typeCounts.length == n) return;
            typeCounts=new long[n]; typeNs=new long[n]; topUsed=new boolean[n];
        }

        void reset(SpriteRenderer.RingBuffer rb) {
            ensureTypes();
            for(int i=0;i<typeCounts.length;i++){typeCounts[i]=0L;typeNs[i]=0L;}
            runs=drawRuns=opRuns=maxOpsPerRun=singleOpRuns=consecutiveOpRuns=0;
            styleChanges=tex0Changes=tex1Changes=tex2Changes=attribChanges=0;
            drawVertices=opCalls=opNs=unknownType=unknownNs=0L;
            if (rb.stateRun == null) return;
            runs = rb.numRuns < 0 ? 0 : Math.min(rb.numRuns, rb.stateRun.length);
            SpriteRenderer.RingBuffer.StateRun prev=null;
            boolean prevOp=false;
            for(int i=0;i<runs;i++) {
                SpriteRenderer.RingBuffer.StateRun r=rb.stateRun[i];
                if(r==null) continue;
                int ops=r.ops==null?0:r.ops.size();
                boolean isOp=ops>0;
                if(isOp){opRuns++; if(ops==1)singleOpRuns++; if(ops>maxOpsPerRun)maxOpsPerRun=ops; if(prevOp)consecutiveOpRuns++;}
                else {drawRuns++; if(r.length>0)drawVertices+=r.length;}
                if(prev!=null){
                    if(r.style!=prev.style)styleChanges++;
                    if(r.texture0!=prev.texture0)tex0Changes++;
                    if(r.texture1!=prev.texture1)tex1Changes++;
                    if(r.texture2!=prev.texture2)tex2Changes++;
                    if(r.useAttribArray!=prev.useAttribArray)attribChanges++;
                }
                prev=r; prevOp=isOp;
            }
        }
    }
}
