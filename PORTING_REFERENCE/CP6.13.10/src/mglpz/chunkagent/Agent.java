package mglpz.chunkagent;

import java.lang.instrument.Instrumentation;

public final class Agent {
    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        start(args, inst, "premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args, inst, "agentmain");
    }

    private static void start(String args, Instrumentation inst, String mode) {
        Profiler.configure(args);
        ChunkTransformer transformer = new ChunkTransformer();
        // CP4.1 adds wrapper methods at initial class definition. JVM retransformation cannot
        // legally add methods, so premain is required once any target class has loaded.
        inst.addTransformer(transformer, false);
        Profiler.note("MGLPZ_CHUNK_AGENT_START cp=CP6.13.4-HOTPATH-CPU-ALLOC baseline=CP4.1 mode=" + mode + " transform_mode=initial-load");
        CP613FixPack.bootLog();

        if ("agentmain".equals(mode)) {
            boolean tooLate = false;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (ChunkTransformer.isTargetClassName(c.getName())) {
                    tooLate = true;
                    Profiler.note("MGLPZ_CHUNK_AGENT_ATTACH_TOO_LATE class=" + c.getName()
                            + " reason=target_already_loaded use=-javaagent_at_startup");
                }
            }
            if (!tooLate) Profiler.note("MGLPZ_CHUNK_AGENT_ATTACH_READY targets_not_loaded_yet=true");
        }
    }
}
