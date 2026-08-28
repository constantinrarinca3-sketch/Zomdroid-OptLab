package com.zomdroid.agent;

import com.zomdroid.agent.decorators.ShaderUnit;
import com.zomdroid.agent.optimization.FeatureCompatibility;
import com.zomdroid.agent.optimization.FboRuntime;
import com.zomdroid.agent.optimization.PacingRuntime;
import com.zomdroid.agent.optimization.PathfindingRuntime;
import com.zomdroid.agent.optimization.PopManRuntime;
import com.zomdroid.agent.optimization.ProofRuntime;
import com.zomdroid.agent.optimization.StreamCoreRuntime;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class Main {
    private static final String MAIN_THREAD_SHA256 =
            "c7ee1d1d3026185ad49cd80edbf9ddb6f59c0cd7faf2ced4ebbe50f2dada9c0f";
    private static final String GAME_WINDOW_SHA256 =
            "34c9927f595ecd524e5ed5524ede1b4789c9be462ea1a04a0f5d1b57dd1d5c95";
    private static final String WORLD_STREAMER_SHA256 =
            "c921fec2b95951e372176ce2a00cb461104c74521fcabcc9657d6061e1d8090d";
    private static final String WORLD_COMPARATOR_SHA256 =
            "76d8dabe08d1224be5a9d24542cfe2bb86dab1c7ecc0eb4aafe030d82b3eeeb7";
    private static final String FBO_MANAGER_SHA256 =
            "f94aa259d6a371f4fd22f3e86e48aac47b4c823b0a13ebcccdbb9fa69671c7aa";
    private static final String FBO_NLEVELS_SHA256 =
            "0f78747d927cd4f86ee3be38bd4afdf6220b7666c9b2af2c59d57ff75636056b";

    private Main() {}

    public static void premain(String args, Instrumentation instrumentation) {
        ProofRuntime.configureFromProperties();
        FeatureCompatibility.configureFromProperties();
        PathfindingRuntime.configureFromProperties();
        PopManRuntime.configureFromProperties();
        System.out.println("[ZD-OPT-LAB-AGENT] version=8 schema=7 loaded");

        String renderer = System.getProperty("zomdroid.renderer", "");
        if ("GL4ES".equals(renderer)) installLegacyShaderPatch(instrumentation);

        boolean pacingRequested = flag("zomdroid.optlab.pacing");
        boolean wakeRequested = flag("zomdroid.optlab.stream.wake");
        boolean queueRequested = flag("zomdroid.optlab.stream.queue.fast");
        boolean lookaheadRequested = flag("zomdroid.optlab.stream.velocity.eta");
        boolean dirtyDedupRequested = flag("zomdroid.optlab.fbo.dirty.dedup");
        boolean fboBudgetRequested = flag("zomdroid.optlab.fbo.frame.budget");
        boolean coordinatorRequested = flag("zomdroid.optlab.stream.fbo.coordinator");
        boolean pathfindingProofRequested = PathfindingRuntime.isProofEnabled();
        boolean popManProofRequested = PopManRuntime.isProofEnabled();
        boolean anyRequested = pacingRequested || wakeRequested || queueRequested
                || lookaheadRequested || dirtyDedupRequested || fboBudgetRequested
                || coordinatorRequested || pathfindingProofRequested || popManProofRequested;

        if (!anyRequested) {
            ProofRuntime.state("PACK", "ALL_OFF", "no_transformers_installed");
            return;
        }

        String mainThreadHash = resourceSha256("zombie/MainThread.class");
        String gameWindowHash = resourceSha256("zombie/GameWindow.class");
        String worldHash = resourceSha256("zombie/iso/WorldStreamer.class");
        String comparatorHash = resourceSha256("zombie/iso/WorldStreamer$ChunkComparator.class");
        String fboManagerHash = resourceSha256(
                "zombie/iso/fboRenderChunk/FBORenderChunkManager.class");
        String fboNLevelsHash = resourceSha256(
                "zombie/iso/fboRenderChunk/FBORenderLevels$NLevels.class");

        FeatureCompatibility.Requirement mainThread = requirement(
                "main_thread", MAIN_THREAD_SHA256, mainThreadHash);
        FeatureCompatibility.Requirement gameWindow = requirement(
                "game_window", GAME_WINDOW_SHA256, gameWindowHash);
        FeatureCompatibility.Requirement worldStreamer = requirement(
                "world_streamer", WORLD_STREAMER_SHA256, worldHash);
        FeatureCompatibility.Requirement comparator = requirement(
                "chunk_comparator", WORLD_COMPARATOR_SHA256, comparatorHash);
        FeatureCompatibility.Requirement fboManager = requirement(
                "fbo_manager", FBO_MANAGER_SHA256, fboManagerHash);
        FeatureCompatibility.Requirement fboNLevels = requirement(
                "fbo_nlevels", FBO_NLEVELS_SHA256, fboNLevelsHash);

        final FeatureCompatibility.Decision pacing = FeatureCompatibility.evaluate(
                "PACING", pacingRequested, 2, mainThread, gameWindow);
        final FeatureCompatibility.Decision wake = FeatureCompatibility.evaluate(
                "STREAM_WAKE", wakeRequested, 2, worldStreamer);
        final FeatureCompatibility.Decision queueFast = FeatureCompatibility.evaluate(
                "STREAM_QUEUE_FAST", queueRequested, 1, worldStreamer);
        final FeatureCompatibility.Decision lookahead = FeatureCompatibility.evaluate(
                "STREAM_VELOCITY_ETA", lookaheadRequested, 1, comparator);
        final FeatureCompatibility.Decision dirtyDedup = FeatureCompatibility.evaluate(
                "FBO_DIRTY_DEDUP", dirtyDedupRequested, 1, fboNLevels);
        final FeatureCompatibility.Decision fboBudget = FeatureCompatibility.evaluate(
                "FBO_FRAME_BUDGET", fboBudgetRequested, 1, fboManager);
        final FeatureCompatibility.Decision coordinator = fboBudgetRequested
                ? FeatureCompatibility.evaluate("STREAM_FBO_COORDINATOR",
                        coordinatorRequested, 2, worldStreamer, fboManager)
                : FeatureCompatibility.unsupported("STREAM_FBO_COORDINATOR",
                        coordinatorRequested, "requires_fbo_frame_budget");

        if (!(pacing.isCandidate() || wake.isCandidate() || queueFast.isCandidate()
                || lookahead.isCandidate() || dirtyDedup.isCandidate()
                || fboBudget.isCandidate() || coordinator.isCandidate()
                || pathfindingProofRequested || popManProofRequested)) return;

        try {
            if (pacing.isCandidate()) PacingRuntime.configureFromProperties();
            StreamCoreRuntime.configure(wake.isCandidate(), queueFast.isCandidate(),
                    lookahead.isCandidate());
            FboRuntime.configure(dirtyDedup.isCandidate(), fboBudget.isCandidate(),
                    coordinator.isCandidate());

            AgentBuilder builder = new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(new TransformFailureListener());
            if (pacing.isCandidate()) {
                builder = builder
                        .type(named("zombie.MainThread"))
                        .transform((target, type, loader, module, domain) -> {
                            int mainLoops = methodCount(type, "mainLoop", "()V");
                            int queueMethods = methodCount(type, "queueInvokeOnMainThread", null);
                            if (mainLoops != 1 || queueMethods < 1) {
                                PacingRuntime.disable();
                                pacing.fallback("main_thread_shape mainLoop=" + mainLoops
                                        + " queueInvoke=" + queueMethods);
                                return target;
                            }
                            return target
                                    .visit(new AsmVisitorWrapper.ForDeclaredMethods().method(
                                            named("mainLoop").and(takesArguments(0))
                                                    .and(returns(void.class)),
                                            new YieldReplacement(pacing)))
                                    .visit(Advice.to(QueueInvokeAdvice.class)
                                            .on(named("queueInvokeOnMainThread")));
                        })
                        .type(named("zombie.GameWindow"))
                        .transform((target, type, loader, module, domain) -> {
                            int frameSteps = methodCount(type, "frameStep", null);
                            if (frameSteps != 1) {
                                PacingRuntime.disable();
                                pacing.fallback("game_window_shape frameStep=" + frameSteps);
                                return target;
                            }
                            pacing.passPart("game_window_frame_step");
                            return target.visit(Advice.to(FrameStepAdvice.class)
                                    .on(named("frameStep")));
                        });
            }
            if (wake.isCandidate() || queueFast.isCandidate() || coordinator.isCandidate()) {
                builder = builder.type(named("zombie.iso.WorldStreamer"))
                        .transform((target, type, loader, module, domain) -> {
                            int loops = methodCount(type, "threadLoop", null);
                            int signals = signalMethodCount(type);
                            boolean applyWake = wake.isCandidate() && loops == 1 && signals > 0;
                            boolean applyQueue = queueFast.isCandidate() && loops == 1;
                            boolean applyCoordinator = coordinator.isCandidate() && signals > 0;
                            if (wake.isCandidate() && !applyWake) {
                                StreamCoreRuntime.disableWakeShape(-1);
                                wake.fallback("world_streamer_shape threadLoop=" + loops
                                        + " signals=" + signals);
                            }
                            if (queueFast.isCandidate() && !applyQueue) {
                                StreamCoreRuntime.disableQueueShape(-1);
                                queueFast.fallback("world_streamer_shape threadLoop=" + loops);
                            }
                            if (coordinator.isCandidate() && !applyCoordinator) {
                                FboRuntime.disableCoordinator();
                                coordinator.fallback("world_streamer_shape signals=" + signals);
                            }
                            if (applyWake || applyQueue) {
                                target = target.visit(new AsmVisitorWrapper.ForDeclaredMethods()
                                        .method(named("threadLoop"),
                                                new WorldStreamerLoopVisitor(applyWake, applyQueue,
                                                        wake, queueFast)));
                            }
                            if (applyWake || applyCoordinator) {
                                target = target.visit(Advice.to(StreamSignalAdvice.class).on(
                                        streamSignalMatcher()));
                                if (applyWake) wake.passPart("world_streamer_signal");
                                if (applyCoordinator) {
                                    coordinator.passPart("world_streamer_signal");
                                }
                            }
                            return target;
                        });
            }
            if (lookahead.isCandidate()) {
                builder = builder.type(named("zombie.iso.WorldStreamer$ChunkComparator"))
                        .transform((target, type, loader, module, domain) -> {
                            int initMethods = methodCount(type, "init", null);
                            if (initMethods != 1) {
                                StreamCoreRuntime.disableLookahead();
                                lookahead.fallback("chunk_comparator_shape init=" + initMethods);
                                return target;
                            }
                            lookahead.passPart("chunk_comparator_init");
                            return target.visit(Advice.to(StreamLookaheadAdvice.class)
                                    .on(named("init")));
                        });
            }
            if (fboBudget.isCandidate() || coordinator.isCandidate()) {
                builder = builder
                        .type(named("zombie.iso.fboRenderChunk.FBORenderChunkManager"))
                        .transform((target, type, loader, module, domain) -> {
                            int beginMethods = methodCount(type, "beginRenderChunkLevel", null);
                            if (beginMethods != 1) {
                                FboRuntime.disableBudgetShape(-1);
                                fboBudget.fallback("fbo_manager_shape beginRenderChunkLevel="
                                        + beginMethods);
                                coordinator.fallback("fbo_manager_shape beginRenderChunkLevel="
                                        + beginMethods);
                                return target;
                            }
                            return target.visit(new AsmVisitorWrapper.ForDeclaredMethods().method(
                                    named("beginRenderChunkLevel"),
                                    new FboDirtyGateVisitor(fboBudget, coordinator)));
                        });
            }
            if (dirtyDedup.isCandidate()) {
                builder = builder
                        .type(named("zombie.iso.fboRenderChunk.FBORenderLevels$NLevels"))
                        .transform((target, type, loader, module, domain) -> {
                            int setDirty = methodCount(type, "setDirty", "(J)V");
                            if (setDirty != 1) {
                                FboRuntime.disableDirtyDedup();
                                dirtyDedup.fallback("fbo_nlevels_shape setDirty_long=" + setDirty);
                                return target;
                            }
                            dirtyDedup.passPart("fbo_nlevels_set_dirty");
                            return target
                                // Skip only a redundant dirty-bit OR. NLevels.invalidate() also
                                // clears live cached-square lists, so skipping that outer method
                                // would be observably unsafe even when the dirty bits match.
                                .visit(Advice.to(FboSetDirtyAdvice.class)
                                        .on(named("setDirty").and(takesArguments(long.class))
                                                .and(returns(void.class))));
                        });
            }
            if (pathfindingProofRequested) {
                builder = builder
                        .type(named("zombie.pathfind.nativeCode.PathfindNative"))
                        .transform((target, type, loader, module, domain) -> {
                            int wrappers = methodCount(type, "findPath",
                                    "(Lzombie/pathfind/nativeCode/PathFindRequest;"
                                            + "Ljava/nio/ByteBuffer;Z)I");
                            if (wrappers != 1) {
                                ProofRuntime.state("PATHFINDING_NATIVE_FALLBACK", "FALLBACK",
                                        "pathfind_wrapper_shape=" + wrappers);
                                return target;
                            }
                            return target.visit(Advice.to(PathfindingRequestAdvice.class).on(
                                    named("findPath").and(takesArguments(3))
                                            .and(returns(int.class))));
                        })
                        .type(named("zombie.pathfind.nativeCode.PathfindNativeThread"))
                        .transform((target, type, loader, module, domain) -> {
                            int loops = methodCount(type, "runInner", "()V");
                            if (loops != 1) {
                                ProofRuntime.state("PATHFINDING_NATIVE_FALLBACK", "FALLBACK",
                                        "pathfind_thread_shape=" + loops);
                                return target;
                            }
                            return target.visit(Advice.to(PathfindingThreadAdvice.class).on(
                                    named("runInner").and(takesArguments(0))
                                            .and(returns(void.class))));
                        });
            }
            if (popManProofRequested) {
                builder = builder
                        .type(named("zombie.popman.ZombiePopulationManager"))
                        .transform((target, type, loader, module, domain) -> {
                            int initMethods = methodCount(type, "init", "()V");
                            int saveMethods = methodCount(type, "writeCellSnapshot",
                                    "(Lzombie/popman/ZombiePopulationManager$PendingCellSave;)V");
                            if (initMethods != 1 || saveMethods != 1) {
                                ProofRuntime.state("POPMAN_NATIVE_FALLBACK", "FALLBACK",
                                        "popman_shape init=" + initMethods
                                                + " writeCellSnapshot=" + saveMethods);
                                return target;
                            }
                            return target
                                    .visit(Advice.to(PopManInitAdvice.class).on(
                                            named("init").and(isStatic())
                                                    .and(takesArguments(0))
                                                    .and(returns(void.class))))
                                    .visit(Advice.to(PopManSaveCellAdvice.class).on(
                                            named("writeCellSnapshot").and(takesArguments(1))
                                                    .and(returns(void.class))));
                        });
            }
            builder.installOn(instrumentation);
            ProofRuntime.state("PACK", "ARMED", "transformers_installed");
        } catch (Throwable error) {
            PacingRuntime.disable();
            StreamCoreRuntime.configure(false, false, false);
            FboRuntime.configure(false, false, false);
            ProofRuntime.state("PACK", "BLOCKED_INSTALL", error.getClass().getSimpleName());
            System.err.println("[ZD-OPT-LAB-AGENT] install failed: " + error);
            error.printStackTrace(System.err);
        }
    }

    private static boolean flag(String property) {
        return "1".equals(System.getProperty(property, "0"));
    }

    private static FeatureCompatibility.Requirement requirement(
            String label, String expectedSha256, String actualSha256) {
        return new FeatureCompatibility.Requirement(label, expectedSha256, actualSha256);
    }

    private static int methodCount(TypeDescription type, String name, String descriptor) {
        int count = 0;
        for (MethodDescription.InDefinedShape method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())
                    && (descriptor == null || descriptor.equals(method.getDescriptor()))) {
                count++;
            }
        }
        return count;
    }

    private static int signalMethodCount(TypeDescription type) {
        int count = 0;
        for (MethodDescription.InDefinedShape method : type.getDeclaredMethods()) {
            String name = method.getName();
            if ("addJob".equals(name) || "addJobInstant".equals(name)
                    || "addJobConvert".equals(name) || "addJobWipe".equals(name)
                    || "receiveChunkPart".equals(name) || "receiveNotRequired".equals(name)
                    || "requestLargeAreaZip".equals(name) || "stop".equals(name)
                    || "quit".equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static ElementMatcher.Junction<MethodDescription> streamSignalMatcher() {
        return named("addJob").or(named("addJobInstant"))
                .or(named("addJobConvert")).or(named("addJobWipe"))
                .or(named("receiveChunkPart")).or(named("receiveNotRequired"))
                .or(named("requestLargeAreaZip")).or(named("stop")).or(named("quit"));
    }

    private static void installLegacyShaderPatch(Instrumentation instrumentation) {
        ClassLoader classLoader = Main.class.getClassLoader();
        TypePool typePool = TypePool.Default.of(classLoader);
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(classLoader);
        try {
            new ByteBuddy().with(TypeValidation.DISABLED)
                    .rebase(typePool.describe("zombie.core.opengl.ShaderUnit").resolve(), locator)
                    .visit(Advice.to(ShaderUnit.loadShaderFile.class).on(named("loadShaderFile")))
                    .visit(Advice.to(ShaderUnit.preProcessShaderFile.class)
                            .on(named("preProcessShaderFile")))
                    .visit(Advice.to(ShaderUnit.processIncludeLine.class)
                            .on(named("processIncludeLine")))
                    .make().load(classLoader, ClassReloadingStrategy.of(instrumentation));
        } catch (Throwable error) {
            System.err.println("[ZD-OPT-LAB-AGENT] legacy ShaderUnit patch failed: " + error);
            error.printStackTrace(System.err);
        }
    }

    private static String resourceSha256(String resource) {
        try (InputStream input = Main.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) return "MISSING";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Throwable error) {
            return "ERROR";
        }
    }

    public static final class FrameStepAdvice {
        @Advice.OnMethodEnter public static void enter() { PacingRuntime.onFrameStep(); }
    }

    public static final class QueueInvokeAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit() { PacingRuntime.onMainThreadWorkQueued(); }
    }

    public static final class StreamSignalAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object worldStreamer) {
            StreamCoreRuntime.signal(worldStreamer);
        }
    }

    public static final class StreamLookaheadAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object comparator) {
            StreamCoreRuntime.adjustLookahead(comparator);
        }
    }

    public static final class FboSetDirtyAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object nLevels,
                                    @Advice.Argument(0) long flags) {
            return FboRuntime.shouldSkipSetDirty(nLevels, flags);
        }
    }

    public static final class PathfindingRequestAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Return(readOnly = false) int result,
                                @Advice.Thrown(readOnly = false) Throwable error) {
            if (error == null) {
                PathfindingRuntime.exercised();
                return;
            }
            result = 0;
            error = PathfindingRuntime.fallback(error);
        }
    }

    public static final class PathfindingThreadAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable error) {
            PathfindingRuntime.threadFailure(error);
        }
    }

    public static final class PopManInitAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown(readOnly = false) Throwable error) {
            error = PopManRuntime.loadSaveCellBridge(error);
        }
    }

    public static final class PopManSaveCellAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown(readOnly = false) Throwable error) {
            error = PopManRuntime.saveCellCompleted(error);
        }
    }

    private static final class YieldReplacement
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        private final FeatureCompatibility.Decision pacing;

        YieldReplacement(FeatureCompatibility.Decision pacing) {
            this.pacing = pacing;
        }

        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool, int writerFlags, int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                private int replacements;
                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                      String descriptor, boolean isInterface) {
                    if (opcode == Opcodes.INVOKESTATIC && "java/lang/Thread".equals(owner)
                            && "yield".equals(name) && "()V".equals(descriptor)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/PacingRuntime",
                                "yieldOrPark", "()V", false);
                        replacements++;
                    } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
                @Override public void visitEnd() {
                    if (replacements != 1) {
                        PacingRuntime.disable();
                        pacing.fallback("main_loop_yield_replacements=" + replacements);
                    } else pacing.passPart("main_thread_main_loop");
                    super.visitEnd();
                }
            };
        }
    }

    private static final class WorldStreamerLoopVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        private final boolean wake;
        private final boolean queueFast;
        private final FeatureCompatibility.Decision wakeDecision;
        private final FeatureCompatibility.Decision queueDecision;

        WorldStreamerLoopVisitor(boolean wake, boolean queueFast,
                                 FeatureCompatibility.Decision wakeDecision,
                                 FeatureCompatibility.Decision queueDecision) {
            this.wake = wake;
            this.queueFast = queueFast;
            this.wakeDecision = wakeDecision;
            this.queueDecision = queueDecision;
        }
        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool, int writerFlags, int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                private int sleepReplacements;
                private int sortReplacements;
                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                      String descriptor, boolean isInterface) {
                    if (wake && opcode == Opcodes.INVOKESTATIC
                            && "java/lang/Thread".equals(owner) && "sleep".equals(name)
                            && "(J)V".equals(descriptor)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/StreamCoreRuntime",
                                "idleWait", "(J)V", false);
                        sleepReplacements++;
                    } else if (queueFast && opcode == Opcodes.INVOKESTATIC
                            && "java/util/Collections".equals(owner) && "sort".equals(name)
                            && "(Ljava/util/List;Ljava/util/Comparator;)V".equals(descriptor)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/StreamCoreRuntime",
                                "sortForStreamer",
                                "(Ljava/util/List;Ljava/util/Comparator;)V", false);
                        sortReplacements++;
                    } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
                @Override public void visitEnd() {
                    if (wake && sleepReplacements != 4) {
                        StreamCoreRuntime.disableWakeShape(sleepReplacements);
                        wakeDecision.fallback("sleep_replacements=" + sleepReplacements);
                    } else if (wake) wakeDecision.passPart("world_streamer_idle_wait");
                    if (queueFast && sortReplacements != 2) {
                        StreamCoreRuntime.disableQueueShape(sortReplacements);
                        queueDecision.fallback("sort_replacements=" + sortReplacements);
                    } else if (queueFast) queueDecision.passPart("world_streamer_queue_select");
                    super.visitEnd();
                }
            };
        }
    }

    private static final class FboDirtyGateVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        private final FeatureCompatibility.Decision budget;
        private final FeatureCompatibility.Decision coordinator;

        FboDirtyGateVisitor(FeatureCompatibility.Decision budget,
                            FeatureCompatibility.Decision coordinator) {
            this.budget = budget;
            this.coordinator = coordinator;
        }

        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool, int writerFlags, int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                private int replacements;
                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                      String descriptor, boolean isInterface) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    if (opcode == Opcodes.INVOKEVIRTUAL
                            && "zombie/iso/fboRenderChunk/FBORenderLevels".equals(owner)
                            && "isDirty".equals(name) && "(IF)Z".equals(descriptor)) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitVarInsn(Opcodes.ILOAD, 2);
                        super.visitVarInsn(Opcodes.FLOAD, 3);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/FboRuntime", "allowDirty",
                                "(ZLjava/lang/Object;Ljava/lang/Object;IF)Z", false);
                        replacements++;
                    }
                }
                @Override public void visitEnd() {
                    if (replacements != 1) {
                        FboRuntime.disableBudgetShape(replacements);
                        budget.fallback("dirty_gate_replacements=" + replacements);
                        coordinator.fallback("dirty_gate_replacements=" + replacements);
                    } else {
                        budget.passPart("fbo_manager_dirty_gate");
                        coordinator.passPart("fbo_manager_dirty_gate");
                    }
                    super.visitEnd();
                }
            };
        }
    }

    private static final class TransformFailureListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            String reason = "transform_error=" + throwable.getClass().getSimpleName();
            if ("zombie.MainThread".equals(typeName) || "zombie.GameWindow".equals(typeName)) {
                PacingRuntime.disable();
                FeatureCompatibility.fallback("PACING", reason);
            } else if ("zombie.iso.WorldStreamer".equals(typeName)) {
                StreamCoreRuntime.disableWake();
                StreamCoreRuntime.disableQueueFast();
                FboRuntime.disableCoordinator();
                FeatureCompatibility.fallback("STREAM_WAKE", reason);
                FeatureCompatibility.fallback("STREAM_QUEUE_FAST", reason);
                FeatureCompatibility.fallback("STREAM_FBO_COORDINATOR", reason);
            } else if ("zombie.iso.WorldStreamer$ChunkComparator".equals(typeName)) {
                StreamCoreRuntime.disableLookahead();
                FeatureCompatibility.fallback("STREAM_VELOCITY_ETA", reason);
            } else if ("zombie.iso.fboRenderChunk.FBORenderChunkManager".equals(typeName)) {
                FboRuntime.disableFrameBudget();
                FboRuntime.disableCoordinator();
                FeatureCompatibility.fallback("FBO_FRAME_BUDGET", reason);
                FeatureCompatibility.fallback("STREAM_FBO_COORDINATOR", reason);
            } else if ("zombie.iso.fboRenderChunk.FBORenderLevels$NLevels".equals(typeName)) {
                FboRuntime.disableDirtyDedup();
                FeatureCompatibility.fallback("FBO_DIRTY_DEDUP", reason);
            }
            System.err.println("[ZD-OPT-LAB-AGENT] " + typeName + " " + reason);
        }
    }
}
