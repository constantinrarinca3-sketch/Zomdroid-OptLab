package mglpz.chunkagent;
/** Production telemetry/allocation-pressure gate. CP6.11/12 broad diagnostics remain available by
 * rollback, but are no-ops by default on gameplay hot paths in CP6.13. */
public final class MGLPZCP613AllocationFix {
    private static final boolean ENABLED = flag("mglpz.cp613.allocationFix", true);
    private MGLPZCP613AllocationFix(){}
    private static boolean flag(String k, boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    public static boolean enabled(){return ENABLED;}
    public static String summary(){return "allocation_fix="+enabled()+" production_profiler_lean="+enabled();}
}
