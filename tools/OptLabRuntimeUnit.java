import com.zomdroid.agent.optimization.FboRuntime;
import com.zomdroid.agent.optimization.PathfindingRuntime;
import com.zomdroid.agent.optimization.RenderOptimizationRuntime;
import com.zomdroid.agent.optimization.StreamCoreRuntime;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.lwjgl.opengl.GL20;
import zombie.core.DefaultShader;
import zombie.core.opengl.ShaderProgram;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderChunk;

import java.io.File;
import java.util.Arrays;
import java.nio.file.Files;
import java.util.Stack;

/** Host unit checks for the pure/fail-open parts of the optimization runtime. */
public final class OptLabRuntimeUnit {
    public static void main(String[] args) throws Exception {
        testStableLinearSelection();
        testQueueFallback();
        testWakeSignal();
        testDirtyDedup();
        testFboBudget();
        testFboFallback();
        testPathfindingProofAndFallback();
        testChunkDepthUploadAndLookupElision();
        testChunkDepthLookupOffAndFailureFallback();
        testRingRenderEmptyClearElision();
        System.out.println("RUNTIME_UNIT PASS");
    }

    private static void testQueueFallback() {
        StreamCoreRuntime.configure(false, true, false);
        StreamCoreRuntime.disableQueueShape(0);
        Stack<Integer> values = new Stack<>();
        values.addAll(Arrays.asList(4, 1, 3, 2));
        StreamCoreRuntime.sortForStreamer(values,
                (left, right) -> ((Integer) left).compareTo((Integer) right));
        require(values.equals(Arrays.asList(1, 2, 3, 4)),
                "disabled queue patch must execute Collections.sort exactly");
    }

    private static void testStableLinearSelection() {
        StreamCoreRuntime.configure(false, true, false);
        Stack<Integer> values = new Stack<>();
        values.addAll(Arrays.asList(4, 9, 2, 9, 3));
        StreamCoreRuntime.sortForStreamer(values,
                (left, right) -> ((Integer) left).compareTo((Integer) right));
        require(values.pop() == 9, "maximum job must be selected");
        require(values.equals(Arrays.asList(4, 9, 2, 3)),
                "remaining equal-job order must stay stable");
    }

    private static void testWakeSignal() throws Exception {
        StreamCoreRuntime.configure(true, false, false);
        long started = System.nanoTime();
        Thread waiter = new Thread(() -> {
            try {
                StreamCoreRuntime.idleWait(500L);
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        waiter.start();
        Thread.sleep(30L);
        StreamCoreRuntime.signal(null);
        waiter.join(250L);
        require(!waiter.isAlive(), "signal must wake the parked streamer");
        require(System.nanoTime() - started < 350_000_000L, "wake must beat fixed sleep");
    }

    private static void testDirtyDedup() {
        FboRuntime.configure(true, false, false);
        MockNLevels state = new MockNLevels();
        state.zoomInfo[0].dirty = true;
        state.zoomInfo[0].dirtyFlags = 5L;
        state.zoomInfo[1].dirty = true;
        state.zoomInfo[1].dirtyFlags = 7L;
        require(FboRuntime.shouldSkipSetDirty(state, 1L), "covered dirty flags should dedup");
        require(!FboRuntime.shouldSkipSetDirty(state, 8L), "new dirty flags must execute");
    }

    private static void testFboBudget() {
        System.setProperty("zomdroid.optlab.fbo.budget", "2");
        System.setProperty("zomdroid.optlab.fbo.max.defer.frames", "2");
        FboRuntime.configure(false, true, false);
        IsoWorld.instance = new IsoWorld();
        IsoPlayer.players[0] = new IsoPlayer(5.0f, 5.0f);
        IsoChunk far = new IsoChunk(100, 100);
        IsoChunk near = new IsoChunk(0, 0);
        MockManager manager = new MockManager();

        IsoWorld.instance.frameNo = 10;
        require(decide(manager, reusable(), far), "budget slot 1 must pass");
        require(decide(manager, reusable(), far), "budget slot 2 must pass");
        FBORenderChunk deferred = reusable();
        require(!decide(manager, deferred, far), "cached work above budget must defer");
        require(!decide(manager, deferred, far), "same-frame decision must be stable");
        require(decide(manager, reusable(), near), "near-player cache must bypass budget");
        FBORenderChunk firstRender = reusable();
        firstRender.isInit = false;
        require(decide(manager, firstRender, far), "new FBO must never defer");

        IsoWorld.instance.frameNo = 11;
        require(decide(manager, reusable(), far), "next-frame budget slot 1 must pass");
        require(decide(manager, reusable(), far), "next-frame budget slot 2 must pass");
        require(!decide(manager, deferred, far), "second saturated frame may defer");

        IsoWorld.instance.frameNo = 12;
        require(decide(manager, reusable(), far), "fairness-frame budget slot 1 must pass");
        require(decide(manager, reusable(), far), "fairness-frame budget slot 2 must pass");
        require(decide(manager, deferred, far), "max two deferrals must force admission");
    }

    private static void testFboFallback() {
        FboRuntime.configure(true, true, true);
        FboRuntime.disableBudgetShape(0);
        require(FboRuntime.allowDirty(true, new Object(), new Object(), 0, 1.0f),
                "disabled FBO governor must preserve the original dirty=true result");
        FboRuntime.disableDirtyDedup();
        require(!FboRuntime.shouldSkipSetDirty(new Object(), 1L),
                "disabled dirty dedup must execute the original method");
    }

    private static void testPathfindingProofAndFallback() throws Exception {
        File proof = File.createTempFile("zomdroid-pathfinding-proof", ".log");
        File home = Files.createTempDirectory("zomdroid-pathfinding-home").toFile();
        System.setProperty("zomdroid.optlab.proof.path", proof.getAbsolutePath());
        System.setProperty("zomdroid.optlab.session", "pathfinding-unit");
        System.setProperty("zomdroid.native.pathfinding.requested", "1");
        System.setProperty("zomdroid.native.pathfinding.active", "1");
        System.setProperty("user.home", home.getAbsolutePath());
        com.zomdroid.agent.optimization.ProofRuntime.configureFromProperties();
        PathfindingRuntime.configureFromProperties();
        PathfindingRuntime.exercised();
        Throwable suppressed = PathfindingRuntime.fallback(new UnsatisfiedLinkError("unit"));
        require(suppressed == null, "native request failure must fail open");
        require(new File(home, PathfindingRuntime.FALLBACK_MARKER).isFile(),
                "runtime failure must leave a restart marker");
        String log = new String(Files.readAllBytes(proof.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        require(log.contains("mechanism=pathfinding_native_exercised state=exercised"),
                "successful request must produce EXERCISED proof");
        require(log.contains("mechanism=pathfinding_native_fallback state=fallback"),
                "failed request must produce FALLBACK proof");
    }

    private static void testChunkDepthUploadAndLookupElision() {
        GL20.uniform1fCalls = 0;
        ShaderProgram program = new ShaderProgram();
        program.uniform = new ShaderProgram.Uniform(7);
        DefaultShader shader = new DefaultShader();
        shader.setProgram(program);
        RenderOptimizationRuntime.configure(true, true, false);

        for (int i = 0; i < 4096; i++) {
            require(RenderOptimizationRuntime.handleChunkDepth(shader, 0.5f),
                    "ChunkDepth fastpath must handle a valid shader");
        }
        require(program.lookups == 1, "stable shader generation must use one uniform lookup");
        require(GL20.uniform1fCalls == 1, "stable value must use one GL upload");

        require(RenderOptimizationRuntime.handleChunkDepth(shader, 0.75f),
                "changed value must remain handled");
        require(program.lookups == 1, "value change must not repeat lookup");
        require(GL20.uniform1fCalls == 2, "changed value must upload exactly once");

        program.uniform = new ShaderProgram.Uniform(9);
        RenderOptimizationRuntime.afterDefaultShaderCompile();
        require(RenderOptimizationRuntime.handleChunkDepth(shader, 0.75f),
                "post-compile value must remain handled");
        require(program.lookups == 2, "successful compile must invalidate uniform lookup cache");
        require(GL20.uniform1fCalls == 3, "successful compile must force a safe re-upload");
    }

    private static void testChunkDepthLookupOffAndFailureFallback() {
        GL20.uniform1fCalls = 0;
        ShaderProgram program = new ShaderProgram();
        program.uniform = new ShaderProgram.Uniform(4);
        DefaultShader shader = new DefaultShader();
        shader.setProgram(program);
        RenderOptimizationRuntime.configure(true, false, false);
        for (int i = 0; i < 64; i++) {
            require(RenderOptimizationRuntime.handleChunkDepth(shader, 0.25f),
                    "CP6.1 upload-only route must handle valid input");
        }
        require(program.lookups == 64, "lookup OFF must preserve vanilla lookup frequency");
        require(GL20.uniform1fCalls == 1, "CP6.1 must still suppress redundant upload");
        program.uniform = new ShaderProgram.Uniform(5);
        RenderOptimizationRuntime.afterDefaultShaderCompile();
        require(RenderOptimizationRuntime.handleChunkDepth(shader, 0.25f),
                "upload-only route must survive successful recompile");
        require(program.lookups == 65 && GL20.uniform1fCalls == 2,
                "upload-only route must force safe lookup/upload after recompile");

        GL20.uniform1fCalls = 0;
        GL20.throwNext = true;
        program.lookups = 0;
        RenderOptimizationRuntime.configure(true, true, false);
        require(!RenderOptimizationRuntime.handleChunkDepth(shader, 0.5f),
                "GL failure must return control to original PZ code");
        shader.setChunkDepth(0.5f); // Exact fallback; the one-shot test failure is now cleared.
        require(GL20.uniform1fCalls == 2,
                "failed fast upload must not be treated as committed cache state");
        require(!RenderOptimizationRuntime.handleChunkDepth(shader, 0.5f),
                "guard failure must permanently fail open for this session");
    }

    private static void testRingRenderEmptyClearElision() {
        TObjectIntHashMap<Object> map = new TObjectIntHashMap<>();
        RenderOptimizationRuntime.configure(false, false, true);
        RenderOptimizationRuntime.clearModelDrawCountsIfNotEmpty(map);
        require(map.clearCalls == 0, "empty modelDrawCounts.clear must be skipped");
        map.put(new Object(), 1);
        RenderOptimizationRuntime.clearModelDrawCountsIfNotEmpty(map);
        require(map.clearCalls == 1 && map.isEmpty(),
                "non-empty modelDrawCounts must still be cleared exactly once");

        RenderOptimizationRuntime.configure(false, false, false);
        RenderOptimizationRuntime.clearModelDrawCountsIfNotEmpty(map);
        require(map.clearCalls == 2,
                "disabled ring optimization must execute the original clear call");
    }

    private static boolean decide(MockManager manager, FBORenderChunk renderChunk,
                                  IsoChunk chunk) {
        manager.renderChunk = renderChunk;
        return FboRuntime.allowDirty(true, manager, chunk, 0, 1.0f);
    }

    private static FBORenderChunk reusable() {
        FBORenderChunk value = new FBORenderChunk();
        value.isInit = true;
        value.submitted = true;
        value.tex = new Object();
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class MockNLevels {
        private final MockZoom[] zoomInfo = {new MockZoom(), new MockZoom()};
    }

    private static final class MockZoom {
        private boolean dirty;
        private long dirtyFlags;
    }

    public static final class MockManager {
        public FBORenderChunk renderChunk;
    }
}
