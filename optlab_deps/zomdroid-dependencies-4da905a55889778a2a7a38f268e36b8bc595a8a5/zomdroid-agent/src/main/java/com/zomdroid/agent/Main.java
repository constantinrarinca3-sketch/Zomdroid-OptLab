package com.zomdroid.agent;

import com.zomdroid.agent.decorators.ShaderUnit;
import com.zomdroid.agent.optimization.FboRuntime;
import com.zomdroid.agent.optimization.ModPathRuntime;
import com.zomdroid.agent.optimization.PacingRuntime;
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
import net.bytebuddy.pool.TypePool;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

public final class Main {
    private static final String PZ_JAR_SHA256 =
            "e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8";
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
        ModPathRuntime.configureFromProperties();
        System.out.println("[ZD-OPT-LAB-AGENT] version=5 schema=3 loaded");

        String renderer = System.getProperty("zomdroid.renderer", "");
        if ("GL4ES".equals(renderer)) installLegacyShaderPatch(instrumentation);

        boolean pacingRequested = flag("zomdroid.optlab.pacing");
        boolean wakeRequested = flag("zomdroid.optlab.stream.wake");
        boolean queueRequested = flag("zomdroid.optlab.stream.queue.fast");
        boolean lookaheadRequested = flag("zomdroid.optlab.stream.velocity.eta");
        boolean dirtyDedupRequested = flag("zomdroid.optlab.fbo.dirty.dedup");
        boolean fboBudgetRequested = flag("zomdroid.optlab.fbo.frame.budget");
        boolean coordinatorRequested = flag("zomdroid.optlab.stream.fbo.coordinator");
        boolean modPathRequested = flag("zomdroid.modpath.fix");
        boolean anyRequested = pacingRequested || wakeRequested || queueRequested
                || lookaheadRequested || dirtyDedupRequested || fboBudgetRequested
                || coordinatorRequested || modPathRequested;

        if (!anyRequested) {
            ProofRuntime.state("PACK", "ALL_OFF", "no_transformers_installed");
            return;
        }

        boolean jarGate = flag("zomdroid.optlab.jar.gate")
                && PZ_JAR_SHA256.equals(
                        System.getProperty("zomdroid.optlab.jar.sha256", "").trim());
        if (!jarGate) {
            blockRequested("PACING", pacingRequested, "jar_hash");
            blockRequested("STREAM_WAKE", wakeRequested, "jar_hash");
            blockRequested("STREAM_QUEUE_FAST", queueRequested, "jar_hash");
            blockRequested("STREAM_VELOCITY_ETA", lookaheadRequested, "jar_hash");
            blockRequested("FBO_DIRTY_DEDUP", dirtyDedupRequested, "jar_hash");
            blockRequested("FBO_FRAME_BUDGET", fboBudgetRequested, "jar_hash");
            blockRequested("STREAM_FBO_COORDINATOR", coordinatorRequested, "jar_hash");
            blockRequested("MOD_PATH_RESOLVER", modPathRequested, "jar_hash");
            ModPathRuntime.disable();
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
        String indieFileLoaderHash = resourceSha256("zombie/core/IndieFileLoader.class");

        boolean pacing = pacingRequested
                && MAIN_THREAD_SHA256.equals(mainThreadHash)
                && GAME_WINDOW_SHA256.equals(gameWindowHash);
        boolean worldClassOk = WORLD_STREAMER_SHA256.equals(worldHash);
        boolean wake = wakeRequested && worldClassOk;
        boolean queueFast = queueRequested && worldClassOk;
        boolean lookahead = lookaheadRequested && WORLD_COMPARATOR_SHA256.equals(comparatorHash);
        boolean dirtyDedup = dirtyDedupRequested && FBO_NLEVELS_SHA256.equals(fboNLevelsHash);
        boolean fboBudget = fboBudgetRequested && FBO_MANAGER_SHA256.equals(fboManagerHash);
        boolean coordinator = coordinatorRequested && fboBudget && worldClassOk;
        boolean modPath = modPathRequested && ModPathRuntime.isReady()
                && !"MISSING".equals(indieFileLoaderHash)
                && !"ERROR".equals(indieFileLoaderHash);

        classGate("PACING", pacingRequested, pacing,
                "main=" + mainThreadHash + ",window=" + gameWindowHash);
        classGate("STREAM_WAKE", wakeRequested, wake, "world=" + worldHash);
        classGate("STREAM_QUEUE_FAST", queueRequested, queueFast, "world=" + worldHash);
        classGate("STREAM_VELOCITY_ETA", lookaheadRequested, lookahead,
                "comparator=" + comparatorHash);
        classGate("FBO_DIRTY_DEDUP", dirtyDedupRequested, dirtyDedup,
                "nlevels=" + fboNLevelsHash);
        classGate("FBO_FRAME_BUDGET", fboBudgetRequested, fboBudget,
                "manager=" + fboManagerHash);
        if (!coordinatorRequested) {
            ProofRuntime.state("STREAM_FBO_COORDINATOR", "OFF", "not_requested");
        } else if (!fboBudgetRequested) {
            ProofRuntime.state("STREAM_FBO_COORDINATOR", "BLOCKED_DEPENDENCY",
                    "requires_fbo_frame_budget");
        } else {
            classGate("STREAM_FBO_COORDINATOR", true, coordinator,
                    "world=" + worldHash + ",manager=" + fboManagerHash);
        }
        if (!modPathRequested) {
            ProofRuntime.state("MOD_PATH_RESOLVER", "OFF", "not_requested");
        } else if (!ModPathRuntime.isReady()) {
            ProofRuntime.state("MOD_PATH_RESOLVER", "BLOCKED_CONFIG", "missing_mods_root");
        } else {
            classGate("MOD_PATH_RESOLVER", true, modPath,
                    "jar_gate_pass,indie=" + indieFileLoaderHash);
        }
        if (!modPath) ModPathRuntime.disable();

        if (!(pacing || wake || queueFast || lookahead || dirtyDedup || fboBudget
                || coordinator || modPath)) return;

        try {
            if (pacing) PacingRuntime.configureFromProperties();
            StreamCoreRuntime.configure(wake, queueFast, lookahead);
            FboRuntime.configure(dirtyDedup, fboBudget, coordinator);

            AgentBuilder builder = new AgentBuilder.Default().disableClassFormatChanges();
            if (pacing) {
                builder = builder
                        .type(named("zombie.MainThread"))
                        .transform((target, type, loader, module, domain) -> target
                                .visit(new AsmVisitorWrapper.ForDeclaredMethods()
                                        .method(named("mainLoop"), new YieldReplacement()))
                                .visit(Advice.to(QueueInvokeAdvice.class)
                                        .on(named("queueInvokeOnMainThread"))))
                        .type(named("zombie.GameWindow"))
                        .transform((target, type, loader, module, domain) -> target
                                .visit(Advice.to(FrameStepAdvice.class).on(named("frameStep"))));
            }
            if (wake || queueFast || coordinator) {
                final boolean installSignal = wake || coordinator;
                builder = builder.type(named("zombie.iso.WorldStreamer"))
                        .transform((target, type, loader, module, domain) -> {
                            if (wake || queueFast) {
                                target = target.visit(new AsmVisitorWrapper.ForDeclaredMethods()
                                        .method(named("threadLoop"),
                                                new WorldStreamerLoopVisitor(wake, queueFast)));
                            }
                            if (installSignal) {
                                target = target.visit(Advice.to(StreamSignalAdvice.class).on(
                                        named("addJob").or(named("addJobInstant"))
                                                .or(named("addJobConvert"))
                                                .or(named("addJobWipe"))
                                                .or(named("receiveChunkPart"))
                                                .or(named("receiveNotRequired"))
                                                .or(named("requestLargeAreaZip"))
                                                .or(named("stop")).or(named("quit"))));
                            }
                            return target;
                        });
            }
            if (lookahead) {
                builder = builder.type(named("zombie.iso.WorldStreamer$ChunkComparator"))
                        .transform((target, type, loader, module, domain) -> target
                                .visit(Advice.to(StreamLookaheadAdvice.class).on(named("init"))));
            }
            if (fboBudget) {
                builder = builder
                        .type(named("zombie.iso.fboRenderChunk.FBORenderChunkManager"))
                        .transform((target, type, loader, module, domain) -> target
                                .visit(new AsmVisitorWrapper.ForDeclaredMethods().method(
                                        named("beginRenderChunkLevel"), new FboDirtyGateVisitor())));
            }
            if (dirtyDedup) {
                builder = builder
                        .type(named("zombie.iso.fboRenderChunk.FBORenderLevels$NLevels"))
                        .transform((target, type, loader, module, domain) -> target
                                // Skip only a redundant dirty-bit OR. NLevels.invalidate() also
                                // clears live cached-square lists, so skipping that outer method
                                // would be observably unsafe even when the dirty bits match.
                                .visit(Advice.to(FboSetDirtyAdvice.class)
                                        .on(named("setDirty"))));
            }
            if (modPath) {
                builder = builder
                        .type(named("zombie.scripting.ScriptManager"))
                        .transform((target, type, loader, module, domain) -> target
                                .visit(Advice.to(ModPathArgumentAdvice.class).on(
                                        named("LoadFile")
                                                .and(takesArgument(0, String.class)))))
                        .type(named("zombie.core.IndieFileLoader"))
                        .transform((target, type, loader, module, domain) -> target
                                .visit(Advice.to(ModPathArgumentAdvice.class).on(
                                        named("getStreamReader")
                                                .and(takesArgument(0, String.class))))
                                .visit(new AsmVisitorWrapper.ForDeclaredMethods().method(
                                        named("getStreamReader"),
                                        new ModPathStreamConstructorVisitor())));
            }
            builder.installOn(instrumentation);
            ProofRuntime.state("PACK", "ARMED", "transformers_installed");
        } catch (Throwable error) {
            PacingRuntime.disable();
            StreamCoreRuntime.configure(false, false, false);
            FboRuntime.configure(false, false, false);
            ModPathRuntime.disable();
            ProofRuntime.state("PACK", "BLOCKED_INSTALL", error.getClass().getSimpleName());
            System.err.println("[ZD-OPT-LAB-AGENT] install failed: " + error);
            error.printStackTrace(System.err);
        }
    }

    private static boolean flag(String property) {
        return "1".equals(System.getProperty(property, "0"));
    }

    private static void classGate(String mechanism, boolean requested, boolean allowed,
                                  String detail) {
        if (!requested) ProofRuntime.state(mechanism, "OFF", "not_requested");
        else if (allowed) ProofRuntime.state(mechanism, "ARMED", "class_gate_pass");
        else ProofRuntime.state(mechanism, "BLOCKED_CLASS_HASH", detail);
    }

    private static void blockRequested(String mechanism, boolean requested, String reason) {
        if (requested) ProofRuntime.state(mechanism, "BLOCKED", reason);
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

    public static final class ModPathArgumentAdvice {
        @Advice.OnMethodEnter
        public static void enter(
                @Advice.Argument(value = 0, readOnly = false) String path) {
            path = ModPathRuntime.normalize(path);
        }
    }

    /**
     * Last-line protection at the actual Java file-open boundary. This covers the case where
     * getStreamReader receives a valid path but constructs the duplicated path inside its body.
     */
    private static final class ModPathStreamConstructorVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool, int writerFlags, int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                      String descriptor, boolean isInterface) {
                    if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)
                            && isPathOpeningType(owner)) {
                        if ("(Ljava/lang/String;)V".equals(descriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/zomdroid/agent/optimization/ModPathRuntime",
                                    "normalize", "(Ljava/lang/String;)Ljava/lang/String;", false);
                        } else if ("(Ljava/io/File;)V".equals(descriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/zomdroid/agent/optimization/ModPathRuntime",
                                    "normalizeFile", "(Ljava/io/File;)Ljava/io/File;", false);
                        }
                    }
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
            };
        }

        private static boolean isPathOpeningType(String owner) {
            return "java/io/FileInputStream".equals(owner)
                    || "java/io/FileReader".equals(owner)
                    || "java/io/File".equals(owner);
        }
    }

    private static final class YieldReplacement
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
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
                        ProofRuntime.state("PACING", "BLOCKED_SHAPE",
                                "yield_replacements=" + replacements);
                    }
                    super.visitEnd();
                }
            };
        }
    }

    private static final class WorldStreamerLoopVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        private final boolean wake;
        private final boolean queueFast;
        WorldStreamerLoopVisitor(boolean wake, boolean queueFast) {
            this.wake = wake;
            this.queueFast = queueFast;
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
                    }
                    if (queueFast && sortReplacements != 2) {
                        StreamCoreRuntime.disableQueueShape(sortReplacements);
                    }
                    super.visitEnd();
                }
            };
        }
    }

    private static final class FboDirtyGateVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
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
                    if (replacements != 1) FboRuntime.disableBudgetShape(replacements);
                    super.visitEnd();
                }
            };
        }
    }
}
