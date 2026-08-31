package mglpz.chunkagent;

/** CP6.13.3 production gameplay-stutter cleanup configuration + low-frequency summary. */
public final class CP613FixPack {
    private CP613FixPack(){}
    private static boolean on(String k,boolean d){String v=System.getProperty(k);if(v==null)return d;return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));}
    public static void bootLog(){
        // Production fix pack: stale CP6.12 launch profiles must not silently re-enable the deep diagnostic.
        // It can still be requested explicitly through the CP6.13 escape hatch.
        boolean keepRingDiag=on("mglpz.cp613.keepRingDiag",false);
        if(!keepRingDiag)System.setProperty("mglpz.cp612.ringDiag","0");
        Profiler.note("MGLPZ_CP6_13_FIX_CONFIG"
                +" ring="+on("mglpz.cp613.ringFix",true)+" ring_mode=exact_ops_no_state_dedup"
                +" fbo="+on("mglpz.cp613.fboFix",true)+" fbo_mode=vanilla_no_skip"
                +" physics="+on("mglpz.cp613.physicsFix",true)+" physics_mode=bounded_then_vanilla_drain"
                +" moving="+on("mglpz.cp613.movingFix",true)
                +" ragdoll="+on("mglpz.cp613.ragdollFix",true)
                +" lighting="+on("mglpz.cp613.lightingFix",true)
                +" allocation="+on("mglpz.cp613.allocationFix",true)
                +" build_direct="+on("mglpz.cp6134.buildDirect",true)+" fbo_inner="+on("mglpz.cp6134.fboInnerLoop",true)+" moving_lean="+on("mglpz.cp6134.movingLean",true)
                +" loading_prestage=false ring_diag="+keepRingDiag);
        try{Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){public void run(){summary();}},"MGLPZ-CP613-summary"));}catch(Throwable ignored){}
    }
    public static void summary(){
        StringBuilder b=new StringBuilder("MGLPZ_CP6_13_FIX_SUMMARY ");
        try{b.append(zombie.core.MGLPZStateRunRenderFast.cp613Summary());}catch(Throwable t){b.append("ring_summary=NA");}
        b.append(" | ");
        try{b.append(zombie.core.MGLPZBuildFast.cp6134Summary());}catch(Throwable t){b.append("build_summary=NA");}
        b.append(" | ");
        try{b.append(zombie.iso.fboRenderChunk.MGLPZCP613FBOFix.summary());}catch(Throwable t){b.append("fbo_summary=NA");}
        b.append(" | ");
        try{b.append(zombie.iso.fboRenderChunk.MGLPZCP6134FBOFast.summary());}catch(Throwable t){b.append("fbo_inner_summary=NA");}
        b.append(" | ");
        try{b.append(zombie.core.physics.MGLPZCP613PhysicsFix.summary());}catch(Throwable t){b.append("physics_summary=NA");}
        b.append(" | ");
        try{b.append(zombie.MGLPZCP613MovingFix.summary());}catch(Throwable t){b.append("moving_summary=NA");}
        b.append(" | ");
        try{b.append(zombie.core.physics.MGLPZCP613RagdollFix.summary());}catch(Throwable t){b.append("ragdoll_summary=NA");}
        b.append(" | ");
        try{b.append(zombie.iso.MGLPZCP613LightingFix.summary());}catch(Throwable t){b.append("lighting_summary=NA");}
        b.append(" | ");
        try{b.append(MGLPZCP613AllocationFix.summary());}catch(Throwable t){b.append("allocation_summary=NA");}
        Profiler.note(b.toString());
    }
}
