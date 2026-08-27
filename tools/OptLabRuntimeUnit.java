import com.zomdroid.agent.optimization.FboRuntime;
import com.zomdroid.agent.optimization.ModPathRuntime;
import com.zomdroid.agent.optimization.StreamCoreRuntime;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderChunk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Stack;

/** Host unit checks for the pure/fail-open parts of the optimization runtime. */
public final class OptLabRuntimeUnit {
    public static void main(String[] args) throws Exception {
        testStableLinearSelection();
        testWakeSignal();
        testDirtyDedup();
        testFboBudget();
        testModPathResolver();
        System.out.println("RUNTIME_UNIT PASS");
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

    private static void testModPathResolver() throws Exception {
        Path temp = Files.createTempDirectory("zomdroid-modpath-unit-");
        try {
            Path modsRoot = temp.resolve("Project Zomboid 40.20.3/Zomboid/mods");
            Path real = modsRoot.resolve(
                    "AutoTsarTrailers/42.17/media/scripts/vehicles/templates/"
                            + "template_Earthing.txt");
            Files.createDirectories(real.getParent());
            Files.write(real, "unit-ok".getBytes(StandardCharsets.UTF_8));

            // A stale shadow entry must not win over the one authoritative live mod.
            Path shadow = modsRoot.resolve(
                    "data/user/0/com.zomdroid.mglpz2/files/instances/"
                            + "project zomboid 40.20.3/zomboid/mods/autotsartrailers/42.17/"
                            + "media/scripts/vehicles/templates/template_earthing.txt");
            Files.createDirectories(shadow.getParent());
            Files.write(shadow, "stale-shadow".getBytes(StandardCharsets.UTF_8));

            System.setProperty("zomdroid.modpath.fix", "1");
            System.setProperty("zomdroid.modpath.root", modsRoot.toString());
            ModPathRuntime.configureFromProperties();

            String repaired = ModPathRuntime.normalize(shadow.toString());
            require(new File(repaired).getCanonicalFile().equals(real.toFile().getCanonicalFile()),
                    "duplicated lowercase Android path must resolve to the real cased mod file");
            require(ModPathRuntime.normalize(real.toString()).equals(real.toString()),
                    "existing real mod path must remain unchanged");
            String lowercasedAbsolute = modsRoot.toString().toLowerCase(java.util.Locale.ROOT)
                    + "/autotsartrailers/42.17/media/scripts/vehicles/templates/"
                    + "template_earthing.txt";
            require(new File(ModPathRuntime.normalize(lowercasedAbsolute)).getCanonicalFile()
                            .equals(real.toFile().getCanonicalFile()),
                    "lowercased absolute path must restore casing from the live filesystem");
            require(ModPathRuntime.normalize("media/scripts/relative.txt")
                            .equals("media/scripts/relative.txt"),
                    "relative paths must remain unchanged");
            String traversal = modsRoot.resolve("../outside.txt").toString();
            require(ModPathRuntime.normalize(traversal).equals(traversal),
                    "paths that escape the mods root must remain unchanged");

            Path customRoot = temp.resolve("Instanta Mea B42 Experimental/Zomboid/mods");
            Path customReal = customRoot.resolve(
                    "73Winnebago/42/media/scripts/vehicles/73Winnebago.txt");
            Files.createDirectories(customReal.getParent());
            Files.write(customReal, "custom-instance-ok".getBytes(StandardCharsets.UTF_8));
            String customBroken = customRoot + "/data/user/0/com.zomdroid.mglpz2/files/"
                    + "instances/orice alt nume/zomboid/mods/73winnebago/42/media/scripts/"
                    + "vehicles/73winnebago.txt";
            System.setProperty("zomdroid.modpath.root", customRoot.toString());
            ModPathRuntime.configureFromProperties();
            require(new File(ModPathRuntime.normalize(customBroken)).getCanonicalFile()
                            .equals(customReal.toFile().getCanonicalFile()),
                    "resolver must not depend on a fixed game-instance name");
        } finally {
            ModPathRuntime.disable();
            deleteTree(temp.toFile());
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) file.deleteOnExit();
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
