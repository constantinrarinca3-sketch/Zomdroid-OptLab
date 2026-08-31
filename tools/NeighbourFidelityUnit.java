import com.zomdroid.agent.optimization.ChunkOptimizationRuntime;
import zombie.iso.IsoGridSquare;

/** Executable CP4.1 identity-pair/version contract for the integrated runtime. */
public final class NeighbourFidelityUnit {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ChunkOptimizationRuntime.configure(false, true, false, false, false,
                false, false, false, false);
        Object getter = new Object();
        IsoGridSquare a = new IsoGridSquare();
        IsoGridSquare b = new IsoGridSquare();
        IsoGridSquare c = new IsoGridSquare();
        IsoGridSquare d = new IsoGridSquare();

        ChunkOptimizationRuntime.workerEnter();
        record(a, b, getter);
        require(ChunkOptimizationRuntime.skipRecalc3(a, false, b, getter),
                "recorded A->B must hit");
        record(c, d, getter);
        require(ChunkOptimizationRuntime.skipRecalc3(c, false, d, getter),
                "recorded C->D must hit");

        ChunkOptimizationRuntime.onRecalcPropertiesCompleted(a);
        require(!ChunkOptimizationRuntime.skipRecalc3(a, false, b, getter),
                "A version bump must invalidate A->B");
        // The vanilla RecalcProperties hook fires between skipRecalc3 and afterRecalc3.
        // Its in-flight pair must survive so it can be recorded at the new version.
        ChunkOptimizationRuntime.onRecalcPropertiesCompleted(a);
        ChunkOptimizationRuntime.afterRecalc3(a, false, b, getter);
        require(ChunkOptimizationRuntime.skipRecalc3(a, false, b, getter),
                "in-flight A->B must survive version bump and be recorded");
        require(ChunkOptimizationRuntime.skipRecalc3(c, false, d, getter),
                "A version bump must not invalidate unrelated C->D");

        Object getter2 = new Object();
        require(!ChunkOptimizationRuntime.skipRecalc3(c, false, d, getter2),
                "getter identity change must invalidate pair coverage");
        ChunkOptimizationRuntime.workerExit();

        // Main-thread feature has a separate scope/gate but must honor the same CP4.1 contract.
        ChunkOptimizationRuntime.configure(false, false, true, false, false,
                false, false, false, false);
        IsoGridSquare m = new IsoGridSquare();
        IsoGridSquare n = new IsoGridSquare();
        ChunkOptimizationRuntime.mainEnter();
        record(m, n, getter);
        require(ChunkOptimizationRuntime.skipRecalc3(m, false, n, getter),
                "main-scope recorded M->N must hit");
        ChunkOptimizationRuntime.onRecalcPropertiesCompleted(m);
        require(!ChunkOptimizationRuntime.skipRecalc3(m, false, n, getter),
                "main-scope M version bump must invalidate M->N");
        ChunkOptimizationRuntime.mainExit();

        System.out.println("NEIGHBOUR_FIDELITY_UNIT PASS cp41_versioned_pairs=1"
                + " unrelated_preserved=1 pending_preserved=1 worker=1 main=1");
    }

    private static void record(IsoGridSquare first, IsoGridSquare second, Object getter) {
        require(!ChunkOptimizationRuntime.skipRecalc3(first, false, second, getter),
                "first pair call must execute vanilla");
        ChunkOptimizationRuntime.afterRecalc3(first, false, second, getter);
    }
}
