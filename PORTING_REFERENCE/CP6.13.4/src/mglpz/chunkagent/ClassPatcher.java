package mglpz.chunkagent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal class-file wrapper patcher. It does not rewrite existing Code attributes.
 * Selected instance methods are renamed, and straight-line wrapper methods are appended.
 * CP3B preserves CP2C support and additionally wraps selected static helpers for deep attribution.
 * This avoids recalculating StackMapTable/branch offsets in Project Zomboid methods.
 */
final class ClassPatcher {
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_NATIVE = 0x0100;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_SYNTHETIC = 0x1000;

    static final class Target {
        final String name, desc, stage;
        /** 0 = this, >0 = local slot, -1 = null */
        final int chunkLocal;
        Target(String name, String desc, String stage, int chunkLocal) {
            this.name=name; this.desc=desc; this.stage=stage; this.chunkLocal=chunkLocal;
        }
    }
    static final class Result {
        final byte[] bytes; final int wrapped;
        Result(byte[] b, int n) { bytes=b; wrapped=n; }
    }

    static Result patch(byte[] input, String className, List<Target> targets) throws IOException {
        Reader r = new Reader(input);
        if (r.magic != 0xCAFEBABE) throw new IOException("bad class magic");
        List<MethodInfo> wrappers = new ArrayList<MethodInfo>();
        int wrapped = 0;

        for (MethodInfo m : r.methods) {
            String name = r.cp.utf8(m.nameIndex);
            String desc = r.cp.utf8(m.descIndex);
            Target hit = find(targets, name, desc);
            if (hit == null) continue;
            if ((m.access & (ACC_NATIVE | ACC_ABSTRACT)) != 0) continue;
            if (name.startsWith("__mglpz$orig$")) continue;

            int originalNameIndex = m.nameIndex;
            // CP6.4.1: namespace renamed originals so this cumulative performance agent can
            // compose with CP5.1 V5 and any other javaagent that also wraps the same PZ method.
            // The old shared __mglpz$orig$ prefix caused a real device startup failure when
            // both agents independently produced __mglpz$orig$render$2()V.
            String renamed = uniqueRenamedName(r, className, name, desc, wrapped, hit.stage);
            int renamedIndex = r.cp.addUtf8(renamed);
            m.nameIndex = renamedIndex;
            m.access |= ACC_SYNTHETIC;

            MethodInfo wrapper = makeWrapper(r, m, originalNameIndex, renamed, desc, hit);
            wrappers.add(wrapper);
            wrapped++;
        }
        if (wrapped == 0) return new Result(input, 0);
        r.methods.addAll(wrappers);
        return new Result(r.write(), wrapped);
    }


    private static String uniqueRenamedName(Reader r, String className, String name, String desc, int ordinal, String stage) {
        // Keep CP4.1/CP6.3 output byte-identical wherever possible. Only RingBuffer needs a
        // private namespace because CP5.1 V5 also wraps RingBuffer.render in the same JVM.
        String prefix;
        if (stage != null && stage.startsWith("CP613.")) {
            prefix = "__mglpz613$orig$";
        } else if (stage != null && (stage.startsWith("G11.") || stage.startsWith("G11D."))) {
            prefix = "__mglpz611$orig$";
        } else if (stage != null && stage.startsWith("OPT.zone.")) {
            prefix = "__mglpz610$orig$";
        } else if (stage != null && stage.startsWith("Z69.")) {
            // CP6.9 root-cause probes may share classes/method names with the separate CP5.1 profiler.
            // Give only the new Z69 wrappers a private namespace; older cumulative wrappers keep
            // their exact previous names so CP4.1/CP6.8 controls remain byte-identical.
            prefix = "__mglpz69$orig$";
        } else if ("zombie/core/SpriteRenderer$RingBuffer$StateRun".equals(className)
                || "zombie/core/ShaderHelper".equals(className)) {
            prefix = "__mglpz68$orig$";
        } else if ("zombie/core/SpriteRenderer$RingBuffer".equals(className)) {
            prefix = "__mglpz641$orig$";
        } else if ("zombie/core/SpriteRenderer".equals(className)
                || "zombie/core/textures/Texture".equals(className)
                || "zombie/GameProfiler".equals(className)
                || "zombie/util/Type".equals(className)) {
            // CP6.6 shares these classes with other profilers/agents in the same JVM.
            // Use a private namespace so wrapper composition cannot create duplicate signatures.
            prefix = "__mglpz67$orig$";
        } else {
            prefix = "__mglpz$orig$";
        }
        String base = prefix + name + "$" + ordinal;
        String candidate = base;
        int collision = 0;
        while (hasMethodSignature(r, candidate, desc)) {
            candidate = base + "$u" + (++collision);
        }
        return candidate;
    }

    private static boolean hasMethodSignature(Reader r, String name, String desc) {
        for (MethodInfo m : r.methods) {
            if (name.equals(r.cp.utf8(m.nameIndex)) && desc.equals(r.cp.utf8(m.descIndex))) {
                return true;
            }
        }
        return false;
    }

    private static Target find(List<Target> ts, String n, String d) {
        for (Target t : ts) if (t.name.equals(n) && t.desc.equals(d)) return t;
        return null;
    }

    private static MethodInfo makeWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                          String renamed, String desc, Target t) throws IOException {
        if ("OPT.zone.loadingEnterReset".equals(t.stage)) {
            return makeZoneLoadingEnterWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.zone.loadingUpdateStage".equals(t.stage)) {
            return makeZoneLoadingUpdateWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.zone.metaLoadKick".equals(t.stage)) {
            return makeZoneMetaLoadKickWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.chunkDepth".equals(t.stage)) {
            return makeChunkDepthWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.defaultShaderCompile".equals(t.stage)) {
            return makeDefaultShaderCompileWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.ringPack".equals(t.stage)) {
            return makeRingPackWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.ringRender".equals(t.stage)) {
            return makeRingRenderWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.shaderProgramLookup".equals(t.stage)) {
            return makeShaderProgramLookupWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.shaderRegistryMutation".equals(t.stage)) {
            return makeShaderRegistryMutationWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.mvp".equals(t.stage)) {
            return makeMvpWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.shaderHelperMvp".equals(t.stage)) {
            return makeShaderHelperMvpWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.stateRunRender".equals(t.stage)) {
            return makeStateRunRenderWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.stateRunTexture".equals(t.stage)) {
            return makeStateRunTextureWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.prepareSame".equals(t.stage)) {
            return makePrepareSameWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.drawCensus".equals(t.stage)) {
            return makeDrawCensusWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.drawProbe".equals(t.stage)) {
            return makeDrawProbeWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.buildLoop".equals(t.stage)) {
            return makeBuildLoopWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.textureBind".equals(t.stage)) {
            return makeTextureBindWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.rthread.gameProfiler".equals(t.stage)) {
            return makeGameProfilerWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.java.typeCast".equals(t.stage)) {
            return makeTypeCastWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("CP613.physics.core".equals(t.stage)) return makeCP613PhysicsWrapper(r, original, originalNameIndex, renamed, desc, t);
        if ("CP613.moving.bucketUpdate".equals(t.stage) || "CP613.moving.bucketPost".equals(t.stage)) return makeCP613MovingWrapper(r, original, originalNameIndex, renamed, desc, t);
        if ("CP613.fbo.preparePass".equals(t.stage)) return makeCP613FboPassWrapper(r, original, originalNameIndex, renamed, desc, t);
        if ("CP613.fbo.prepareChunk".equals(t.stage)) return makeCP613FboChunkWrapper(r, original, originalNameIndex, renamed, desc, t);
        if ("CP6134.fbo.prepareChunkFast".equals(t.stage)) return makeCP6134FboChunkFastWrapper(r, original, originalNameIndex, renamed, desc, t);
        if ("CP613.lighting.update".equals(t.stage)) return makeCP613LightingWrapper(r, original, originalNameIndex, renamed, desc, t);
        if ("CP613.ragdoll.update".equals(t.stage)) return makeCP613RagdollUpdateWrapper(r, original, originalNameIndex, renamed, desc, t);
        if ("CP613.ragdoll.post".equals(t.stage)) return makeCP613RagdollPostWrapper(r, original, originalNameIndex, renamed, desc, t);
        if (isConditionalFastStage(t.stage)) {
            return makeConditionalVoidWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        if ("OPT.worldgen.mapBiome".equals(t.stage)) {
            return makeMapBiomeWrapper(r, original, originalNameIndex, renamed, desc, t);
        }
        int codeName = r.cp.addUtf8("Code");
        int profilerClass = r.cp.addClass("mglpz/chunkagent/Profiler");
        int enterRef = r.cp.addMethodRef(profilerClass, r.cp.addNameAndType(
                r.cp.addUtf8("enter"), r.cp.addUtf8("(Ljava/lang/String;Ljava/lang/Object;)V")));
        int exitRef = r.cp.addMethodRef(profilerClass, r.cp.addNameAndType(
                r.cp.addUtf8("exit"), r.cp.addUtf8("(Ljava/lang/String;Ljava/lang/Object;)V")));
        int stageStr = r.cp.addString(t.stage);
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);

        emitLdc(c, stageStr);
        emitChunkLoad(c, t.chunkLocal);
        c.writeByte(0xB8); c.writeShort(enterRef); // invokestatic

        final boolean isStatic = (original.access & ACC_STATIC) != 0;
        int local = isStatic ? 0 : 1;
        if (!isStatic) c.writeByte(0x2A); // aload_0 receiver
        List<Character> args = parseArgs(desc);
        for (Character k : args) {
            emitLoad(c, k.charValue(), local);
            local += slots(k.charValue());
        }
        if (isStatic) c.writeByte(0xB8);
        else c.writeByte((original.access & ACC_PRIVATE) != 0 ? 0xB7 : 0xB6); // invokespecial/virtual
        c.writeShort(originalRef);

        char ret = returnKind(desc);
        int retLocal = -1;
        if (ret != 'V') {
            retLocal = local;
            emitStore(c, ret, retLocal);
            local += slots(ret);
        }

        emitLdc(c, stageStr);
        emitChunkLoad(c, t.chunkLocal);
        c.writeByte(0xB8); c.writeShort(exitRef);

        if (ret != 'V') emitLoad(c, ret, retLocal);
        emitReturn(c, ret);
        c.flush();

        byte[] code = cb.toByteArray();
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(16); // max_stack, comfortably above all target descriptors
        a.writeShort(Math.max(local, 1));
        a.writeInt(code.length);
        a.write(code);
        a.writeShort(0); // exception_table_length
        a.writeShort(0); // code attributes_count; no branches => no StackMapTable needed
        a.flush();

        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        // Preserve declarations such as Exceptions and annotations on the public method.
        for (Attribute at : original.attrs) {
            String an = r.cp.utf8(at.nameIndex);
            if (!"Code".equals(an)) attrs.add(at.copy());
        }
        int publicAccess = original.access & ~ACC_SYNTHETIC;
        return new MethodInfo(publicAccess, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.10: reset prestage state when GameLoadingState starts. */
    private static MethodInfo makeZoneLoadingEnterWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                           String renamed, String desc, Target t) throws IOException {
        if (!"()V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.10 loading enter wrapper shape mismatch: " + desc);
        }
        int codeName=r.cp.addUtf8("Code");
        int helperClass=r.cp.addClass("zombie/gameStates/MGLPZZoneEntryStager");
        int resetRef=r.cp.addMethodRef(helperClass,r.cp.addNameAndType(r.cp.addUtf8("resetSession"),r.cp.addUtf8("()V")));
        int ownerClass=r.thisClass;
        int originalRef=r.cp.addMethodRef(ownerClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream(); DataOutputStream c=new DataOutputStream(cb);
        c.writeByte(0xB8); c.writeShort(resetRef);
        c.writeByte(0x2A); c.writeByte((original.access & ACC_PRIVATE)!=0?0xB7:0xB6); c.writeShort(originalRef); c.writeByte(0xB1); c.flush();
        byte[] code=cb.toByteArray(); ByteArrayOutputStream ab=new ByteArrayOutputStream(); DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(1); a.writeShort(1); a.writeInt(code.length); a.write(code); a.writeShort(0); a.writeShort(0); a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>(); attrs.add(new Attribute(codeName,ab.toByteArray()));
        for(Attribute at:original.attrs) if(!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    /** CP6.10: run bounded prestage work after vanilla GameLoadingState.update and gate Continue. */
    private static MethodInfo makeZoneLoadingUpdateWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                            String renamed, String desc, Target t) throws IOException {
        final String expected="()Lzombie/gameStates/GameStateMachine$StateAction;";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.10 loading update wrapper shape mismatch: " + desc);
        }
        int codeName=r.cp.addUtf8("Code");
        int ownerClass=r.thisClass;
        int doneField=r.cp.addFieldRef(ownerClass,r.cp.addNameAndType(r.cp.addUtf8("done"),r.cp.addUtf8("Z")));
        int helperClass=r.cp.addClass("zombie/gameStates/MGLPZZoneEntryStager");
        int adjustRef=r.cp.addMethodRef(helperClass,r.cp.addNameAndType(r.cp.addUtf8("adjustResult"),r.cp.addUtf8("(Lzombie/gameStates/GameStateMachine$StateAction;Z)Lzombie/gameStates/GameStateMachine$StateAction;")));
        int originalRef=r.cp.addMethodRef(ownerClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream(); DataOutputStream c=new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte((original.access & ACC_PRIVATE)!=0?0xB7:0xB6); c.writeShort(originalRef);
        c.writeByte(0xB2); c.writeShort(doneField);
        c.writeByte(0xB8); c.writeShort(adjustRef); c.writeByte(0xB0); c.flush();
        byte[] code=cb.toByteArray(); ByteArrayOutputStream ab=new ByteArrayOutputStream(); DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(2); a.writeShort(1); a.writeInt(code.length); a.write(code); a.writeShort(0); a.writeShort(0); a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>(); attrs.add(new Attribute(codeName,ab.toByteArray()));
        for(Attribute at:original.attrs) if(!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    /** CP6.10: suppress only the duplicate IngameState vehicle-meta kick after confirmed preload. */
    private static MethodInfo makeZoneMetaLoadKickWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                           String renamed, String desc, Target t) throws IOException {
        if (!"()V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.10 vehicle meta wrapper shape mismatch: " + desc);
        }
        int codeName=r.cp.addUtf8("Code"), stackMapName=r.cp.addUtf8("StackMapTable");
        int helperClass=r.cp.addClass("zombie/vehicles/MGLPZVehicleMetaPreload");
        int skipRef=r.cp.addMethodRef(helperClass,r.cp.addNameAndType(r.cp.addUtf8("shouldSkipVanillaKick"),r.cp.addUtf8("()Z")));
        int ownerClass=r.thisClass;
        int originalRef=r.cp.addMethodRef(ownerClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream(); DataOutputStream c=new DataOutputStream(cb);
        c.writeByte(0xB8); c.writeShort(skipRef);
        int ifeqPos=cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB1);
        int fallback=cb.size(); c.writeByte(0x2A); c.writeByte((original.access & ACC_PRIVATE)!=0?0xB7:0xB6); c.writeShort(originalRef); c.writeByte(0xB1); c.flush();
        byte[] code=cb.toByteArray(); patchBranch(code,ifeqPos,fallback); byte[] stackMap=oneSameFrameExtended(fallback);
        ByteArrayOutputStream ab=new ByteArrayOutputStream(); DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(1); a.writeShort(1); a.writeInt(code.length); a.write(code); a.writeShort(0); a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>(); attrs.add(new Attribute(codeName,ab.toByteArray()));
        for(Attribute at:original.attrs) if(!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    private static boolean isConditionalFastStage(String stage) {
        return "FORAGE.genForaging".equals(stage)
                || "DS.grid.addVehicles".equals(stage)
                || "DS.grid.randomizeBuildings".equals(stage)
                || "DS.grid.loadGridWorkaround".equals(stage)
                || "DS.grid.luaNewSquare".equals(stage)
                || "DS.worker.recalculateAll2".equals(stage)
                || "DS.worker.recalculateAll3".equals(stage);
    }

    /**
     * CP6 targeted fastpath for Project Zomboid's built-in performance probes.
     *
     * Vanilla AbstractPerformanceProfileProbe.start() always begins with
     * GameProfiler.isValidThread() (Thread.currentThread/getName/ArrayList.contains) before
     * discovering that GameProfiler is disabled. StateRun.render() executes this probe around
     * every render-sprite draw, so the disabled-profiler path is hot.
     *
     * start fastpath is entered only when:
     *   - CP6 toggle is enabled,
     *   - this probe is not currently running, and
     *   - GameProfiler.isRunning() is false.
     * It then performs the same normal-state result (isProfilerRunning=false) and returns.
     * Any active profiler or abnormal running probe falls back to the renamed vanilla method.
     *
     * end fastpath returns immediately only when isProfilerRunning is false. Otherwise the
     * exact vanilla method executes. This preserves onEnd/stack behavior when profiling is active.
     */
    /** CP6.2: fully handles DefaultShader.setChunkDepth when the guarded cache path is available. */
    private static MethodInfo makeChunkDepthWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                     String renamed, String desc, Target t) throws IOException {
        if (!"(F)V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.2 chunkDepth wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int handlerRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("handleChunkDepth"), r.cp.addUtf8("(Ljava/lang/Object;F)Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A);              // aload_0
        c.writeByte(0x23);              // fload_1
        c.writeByte(0xB8); c.writeShort(handlerRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0); // ifeq -> vanilla fallback
        c.writeByte(0xB1);              // handled => return

        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0x23);
        c.writeByte((original.access & ACC_PRIVATE) != 0 ? 0xB7 : 0xB6);
        c.writeShort(originalRef);
        c.writeByte(0xB1);
        c.flush();
        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);

        byte[] stackMap = oneSameFrameExtended(originalOffset);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(2); // this + float
        a.writeShort(2);
        a.writeInt(code.length); a.write(code);
        a.writeShort(0);
        a.writeShort(1);
        a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap);
        a.flush();

        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) {
            String an = r.cp.utf8(at.nameIndex);
            if (!"Code".equals(an)) attrs.add(at.copy());
        }
        int publicAccess = original.access & ~ACC_SYNTHETIC;
        return new MethodInfo(publicAccess, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.2: call vanilla compile first, then advance the chunkDepth compile epoch. */
    private static MethodInfo makeDefaultShaderCompileWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                               String renamed, String desc, Target t) throws IOException {
        if (!"(Lzombie/core/opengl/ShaderProgram;)V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.2 DefaultShader compile wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int hookRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("afterDefaultShaderCompile"), r.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)V")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0x2B); // this, ShaderProgram
        c.writeByte((original.access & ACC_PRIVATE) != 0 ? 0xB7 : 0xB6); c.writeShort(originalRef);
        c.writeByte(0x2A); c.writeByte(0x2B);
        c.writeByte(0xB8); c.writeShort(hookRef);
        c.writeByte(0xB1);
        c.flush();
        byte[] code = cb.toByteArray();

        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(2); // max stack
        a.writeShort(2); // this + program
        a.writeInt(code.length); a.write(code);
        a.writeShort(0); // exceptions
        a.writeShort(0); // code attrs, no branches => no StackMapTable required
        a.flush();

        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) {
            String an = r.cp.utf8(at.nameIndex);
            if (!"Code".equals(an)) attrs.add(at.copy());
        }
        int publicAccess = original.access & ~ACC_SYNTHETIC;
        return new MethodInfo(publicAccess, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.2: common RingBuffer.add glDraw is handled by same-package bulk packer; else vanilla. */
    private static MethodInfo makeRingPackWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                   String renamed, String desc, Target t) throws IOException {
        final String expected = "(Lzombie/core/textures/TextureDraw;Lzombie/core/textures/TextureDraw;Lzombie/core/Styles/Style;)V";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.2 ringPack wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int helperClass = r.cp.addClass("zombie/core/MGLPZRingFast");
        int helperRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("tryAdd"), r.cp.addUtf8("(Lzombie/core/SpriteRenderer$RingBuffer;Lzombie/core/textures/TextureDraw;Lzombie/core/textures/TextureDraw;Lzombie/core/Styles/Style;)Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0x2B); c.writeByte(0x2C); c.writeByte(0x2D);
        c.writeByte(0xB8); c.writeShort(helperRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB1);

        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0x2B); c.writeByte(0x2C); c.writeByte(0x2D);
        c.writeByte((original.access & ACC_PRIVATE) != 0 ? 0xB7 : 0xB6); c.writeShort(originalRef);
        c.writeByte(0xB1);
        c.flush();
        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);

        byte[] stackMap = oneSameFrameExtended(originalOffset);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(4); // max stack
        a.writeShort(4); // this + 3 args
        a.writeInt(code.length); a.write(code);
        a.writeShort(0);
        a.writeShort(1);
        a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap);
        a.flush();

        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) {
            String an = r.cp.utf8(at.nameIndex);
            if (!"Code".equals(an)) attrs.add(at.copy());
        }
        int publicAccess = original.access & ~ACC_SYNTHETIC;
        return new MethodInfo(publicAccess, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.2: vanilla RingBuffer.render with safe empty modelDrawCounts clear elision. */
    private static MethodInfo makeRingRenderWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                     String renamed, String desc, Target t) throws IOException {
        if (!"()V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.2 ringRender wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int helperClass = r.cp.addClass("zombie/core/MGLPZRingFast");
        int helperRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("tryRender"), r.cp.addUtf8("(Lzombie/core/SpriteRenderer$RingBuffer;)Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0xB8); c.writeShort(helperRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB1);
        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte((original.access & ACC_PRIVATE) != 0 ? 0xB7 : 0xB6); c.writeShort(originalRef); c.writeByte(0xB1);
        c.flush();
        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);
        byte[] stackMap = oneSameFrameExtended(originalOffset);

        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(1); a.writeShort(1); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>(); attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }


    /** CP6.3: last-ID shader registry cache. Cache miss runs exact vanilla getProgramByID then stores. */
    private static MethodInfo makeShaderProgramLookupWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                              String renamed, String desc, Target t) throws IOException {
        final String expected = "(I)Lzombie/core/opengl/ShaderProgram;";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.3 shaderProgramLookup wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int cachedRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("cachedShaderProgramById"), r.cp.addUtf8("(Ljava/lang/Object;I)Ljava/lang/Object;")));
        int afterRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("afterShaderProgramLookup"), r.cp.addUtf8("(Ljava/lang/Object;ILjava/lang/Object;)V")));
        int shaderProgramClass = r.cp.addClass("zombie/core/opengl/ShaderProgram");
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A);                // aload_0 owner
        c.writeByte(0x1B);                // iload_1 id
        c.writeByte(0xB8); c.writeShort(cachedRef);
        c.writeByte(0x4D);                // astore_2
        c.writeByte(0x2C);                // aload_2
        int ifnullPos = cb.size(); c.writeByte(0xC6); c.writeShort(0); // ifnull -> vanilla
        c.writeByte(0x2C);
        c.writeByte(0xC0); c.writeShort(shaderProgramClass); // checkcast
        c.writeByte(0xB0);                // areturn

        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0x1B);
        c.writeByte(0xB6); c.writeShort(originalRef); // renamed public instance method
        c.writeByte(0x4D);                // result
        c.writeByte(0x2A); c.writeByte(0x1B); c.writeByte(0x2C);
        c.writeByte(0xB8); c.writeShort(afterRef);
        c.writeByte(0x2C); c.writeByte(0xB0);
        c.flush();
        byte[] code = cb.toByteArray();
        patchBranch(code, ifnullPos, originalOffset);
        byte[] stackMap = oneSameFrameExtended(originalOffset);

        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(3); // owner,id,result
        a.writeShort(3); // this,int,Object
        a.writeInt(code.length); a.write(code);
        a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>(); attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.3: registry mutations execute vanilla first, then invalidate all per-thread last-ID caches. */
    private static MethodInfo makeShaderRegistryMutationWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                                 String renamed, String desc, Target t) throws IOException {
        final String expected = "(Lzombie/core/opengl/ShaderProgram;)V";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.3 shaderRegistryMutation wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int afterRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("afterShaderRegistryMutation"), r.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)V")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0x2B);
        c.writeByte(0xB6); c.writeShort(originalRef);
        c.writeByte(0x2A); c.writeByte(0x2B);
        c.writeByte(0xB8); c.writeShort(afterRef);
        c.writeByte(0xB1); c.flush();
        byte[] code = cb.toByteArray();

        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(2); a.writeShort(2); a.writeInt(code.length); a.write(code); a.writeShort(0); a.writeShort(0); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>(); attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.3: exact static VBO MVP path is handled by same-package helper; fallback stays vanilla. */
    private static MethodInfo makeMvpWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                              String renamed, String desc, Target t) throws IOException {
        final String expected = "(Lzombie/core/opengl/ShaderProgram;)V";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) == 0) {
            throw new IOException("CP6.3 MVP wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int helperClass = r.cp.addClass("zombie/core/opengl/MGLPZMvpFast");
        int helperRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("trySet"), r.cp.addUtf8("(Lzombie/core/opengl/ShaderProgram;)Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); // aload_0 (static arg0)
        c.writeByte(0xB8); c.writeShort(helperRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB1);
        int originalOffset = cb.size();
        c.writeByte(0x2A);
        c.writeByte(0xB8); c.writeShort(originalRef);
        c.writeByte(0xB1); c.flush();
        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);
        byte[] stackMap = oneSameFrameExtended(originalOffset);

        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(1); a.writeShort(1); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>(); attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }


    /** CP6.4: same-GPU-texture StateRun equivalence with exact renamed vanilla fallback when toggle is off. */
    private static MethodInfo makeStateRunTextureWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                          String renamed, String desc, Target t) throws IOException {
        final String expected = "(Lzombie/core/textures/TextureDraw;Lzombie/core/textures/TextureDraw;Lzombie/core/Styles/Style;Lzombie/core/textures/Texture;Lzombie/core/textures/Texture;Lzombie/core/textures/Texture;B)Z";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.4 stateRunTexture wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int enabledRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("rthreadStateRunTextureFastEnabled"), r.cp.addUtf8("()Z")));
        int helperClass = r.cp.addClass("zombie/core/MGLPZStateRunFast");
        int helperRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("stateChanged"),
                r.cp.addUtf8("(Lzombie/core/SpriteRenderer$RingBuffer;Lzombie/core/textures/TextureDraw;Lzombie/core/textures/TextureDraw;Lzombie/core/Styles/Style;Lzombie/core/textures/Texture;Lzombie/core/textures/Texture;Lzombie/core/textures/Texture;B)Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0xB8); c.writeShort(enabledRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);

        c.writeByte(0x2A); c.writeByte(0x2B); c.writeByte(0x2C); c.writeByte(0x2D);
        c.writeByte(0x19); c.writeByte(4);
        c.writeByte(0x19); c.writeByte(5);
        c.writeByte(0x19); c.writeByte(6);
        c.writeByte(0x15); c.writeByte(7);
        c.writeByte(0xB8); c.writeShort(helperRef);
        c.writeByte(0xAC);

        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0x2B); c.writeByte(0x2C); c.writeByte(0x2D);
        c.writeByte(0x19); c.writeByte(4);
        c.writeByte(0x19); c.writeByte(5);
        c.writeByte(0x19); c.writeByte(6);
        c.writeByte(0x15); c.writeByte(7);
        c.writeByte(0xB7); c.writeShort(originalRef);
        c.writeByte(0xAC);
        c.flush();

        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);
        byte[] stackMap = oneSameFrameExtended(originalOffset);

        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(8); a.writeShort(8); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>(); attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.6: bypass RingBuffer's unique "Render Style" PerformanceProfileProbe while
     * GameProfiler is idle. StateRun.render already null-checks the profile() return before close(). */
    private static MethodInfo makeDrawProbeWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                    String renamed, String desc, Target t) throws IOException {
        if (!"()Lzombie/core/profiling/AbstractPerformanceProfileProbe;".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.6 drawProbe wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int ownerClass = r.thisClass;
        int nameField = r.cp.addFieldRef(ownerClass, r.cp.addNameAndType(r.cp.addUtf8("name"), r.cp.addUtf8("Ljava/lang/String;")));
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int skipRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("skipIdleRenderStyleProbe"), r.cp.addUtf8("(Ljava/lang/String;)Z")));
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);
        ByteArrayOutputStream cb = new ByteArrayOutputStream(); DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0xB4); c.writeShort(nameField); // this.name
        c.writeByte(0xB8); c.writeShort(skipRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0x01); c.writeByte(0xB0); // aconst_null, areturn
        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0xB6); c.writeShort(originalRef); c.writeByte(0xB0);
        c.flush(); byte[] code=cb.toByteArray(); patchBranch(code, ifeqPos, originalOffset);
        byte[] stackMap=oneSameFrameExtended(originalOffset);
        ByteArrayOutputStream ab=new ByteArrayOutputStream(); DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(1); a.writeShort(1); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>(); attrs.add(new Attribute(codeName,ab.toByteArray()));
        for (Attribute at: original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }


    /** CP6.8: skip ShaderHelper registry/debug chain on the validated cached-program path. */
    private static MethodInfo makeShaderHelperMvpWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                          String renamed, String desc, Target t) throws IOException {
        if (!"()V".equals(desc) || (original.access & ACC_STATIC) == 0) {
            throw new IOException("CP6.8 ShaderHelper MVP wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int ownerClass = r.thisClass;
        int currentField = r.cp.addFieldRef(ownerClass, r.cp.addNameAndType(
                r.cp.addUtf8("currentlyBound"), r.cp.addUtf8("I")));
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int helperRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("handleShaderHelperMvp"), r.cp.addUtf8("(I)Z")));
        int originalRef = r.cp.addMethodRef(ownerClass, r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex));

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0xB2); c.writeShort(currentField); // getstatic currentlyBound
        c.writeByte(0xB8); c.writeShort(helperRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB1);
        int fallback = cb.size();
        c.writeByte(0xB8); c.writeShort(originalRef);
        c.writeByte(0xB1); c.flush();
        byte[] code=cb.toByteArray(); patchBranch(code,ifeqPos,fallback);
        byte[] stackMap=oneSameFrameExtended(fallback);
        ByteArrayOutputStream ab=new ByteArrayOutputStream(); DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(2); a.writeShort(0); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>(); attrs.add(new Attribute(codeName,ab.toByteArray()));
        for(Attribute at:original.attrs) if(!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.8: common StateRun.render path, with exact renamed vanilla fallback before side effects. */
    private static MethodInfo makeStateRunRenderWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                         String renamed, String desc, Target t) throws IOException {
        if (!"()V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.8 StateRun render wrapper shape mismatch: " + desc);
        }
        int codeName=r.cp.addUtf8("Code"), stackMapName=r.cp.addUtf8("StackMapTable");
        int helperClass=r.cp.addClass("zombie/core/MGLPZStateRunRenderFast");
        int helperRef=r.cp.addMethodRef(helperClass,r.cp.addNameAndType(r.cp.addUtf8("tryRender"),
                r.cp.addUtf8("(Lzombie/core/SpriteRenderer$RingBuffer$StateRun;)Z")));
        int ownerClass=r.thisClass;
        int originalRef=r.cp.addMethodRef(ownerClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream(); DataOutputStream c=new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0xB8); c.writeShort(helperRef);
        int ifeqPos=cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB1);
        int fallback=cb.size(); c.writeByte(0x2A);
        c.writeByte((original.access & ACC_PRIVATE)!=0?0xB7:0xB6); c.writeShort(originalRef); c.writeByte(0xB1); c.flush();
        byte[] code=cb.toByteArray(); patchBranch(code,ifeqPos,fallback);
        byte[] stackMap=oneSameFrameExtended(fallback);
        ByteArrayOutputStream ab=new ByteArrayOutputStream(); DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(2); a.writeShort(1); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>(); attrs.add(new Attribute(codeName,ab.toByteArray()));
        for(Attribute at:original.attrs) if(!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.6: hoist SpriteRenderer.ringBuffer outside buildDrawBuffer's hot sprite loop.
     * Fallback invokes the renamed vanilla method exactly. */
    private static MethodInfo makeBuildLoopWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                    String renamed, String desc, Target t) throws IOException {
        final String expected = "([Lzombie/core/textures/TextureDraw;[Lzombie/core/Styles/Style;I)V";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.6 buildLoop wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int helperClass = r.cp.addClass("zombie/core/MGLPZBuildFast");
        int helperRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("tryBuild"),
                r.cp.addUtf8("([Lzombie/core/textures/TextureDraw;[Lzombie/core/Styles/Style;I)Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2B); c.writeByte(0x2C); c.writeByte(0x1D); // args 1,2,3
        c.writeByte(0xB8); c.writeShort(helperRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB1);
        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0x2B); c.writeByte(0x2C); c.writeByte(0x1D);
        c.writeByte((original.access & ACC_PRIVATE) != 0 ? 0xB7 : 0xB6);
        c.writeShort(originalRef);
        c.writeByte(0xB1);
        c.flush();

        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);
        byte[] stackMap = oneSameFrameExtended(originalOffset);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(4); a.writeShort(4); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.6: avoid Texture.bind()'s Java/debug chain when the exact positive GL texture ID
     * is already bound and all safety/debug guards are satisfied. */
    private static MethodInfo makeTextureBindWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                      String renamed, String desc, Target t) throws IOException {
        if (!"(I)V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.6 textureBind wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int helperClass = r.cp.addClass("zombie/core/textures/MGLPZTextureBindFast");
        int helperRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("tryAlreadyBound"), r.cp.addUtf8("(Lzombie/core/textures/Texture;I)Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0x1B);
        c.writeByte(0xB8); c.writeShort(helperRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB1);
        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0x1B);
        c.writeByte(0xB6); c.writeShort(originalRef);
        c.writeByte(0xB1);
        c.flush();

        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);
        byte[] stackMap = oneSameFrameExtended(originalOffset);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(2); a.writeShort(2); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.6: GameProfiler.profile(String) already returns null immediately when the profiler
     * is idle. Mirror only that first guard; active profiler runs exact renamed vanilla. */
    private static MethodInfo makeGameProfilerWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                       String renamed, String desc, Target t) throws IOException {
        if (!"(Ljava/lang/String;)Lzombie/GameProfiler$ProfileArea;".equals(desc)
                || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.6 GameProfiler wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int skipRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("skipIdleGameProfilerArea"), r.cp.addUtf8("()Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0xB8); c.writeShort(skipRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0x01); c.writeByte(0xB0); // null
        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0x2B);
        c.writeByte(0xB6); c.writeShort(originalRef);
        c.writeByte(0xB0);
        c.flush();

        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);
        byte[] stackMap = oneSameFrameExtended(originalOffset);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(2); a.writeShort(2); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.4: aggregate-only draw census. Disabled mode is exactly the renamed vanilla call. */

    /** CP6.7: exact strict-identical glDraw state can reuse current StateRun without isStateChanged. */
    private static MethodInfo makePrepareSameWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                      String renamed, String desc, Target t) throws IOException {
        final String expected = "(Lzombie/core/textures/TextureDraw;Lzombie/core/textures/TextureDraw;Lzombie/core/Styles/Style;)Z";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.7 prepareSame wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int helperClass = r.cp.addClass("zombie/core/MGLPZPrepareFast");
        int helperRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("canReuse"), r.cp.addUtf8("(Lzombie/core/SpriteRenderer$RingBuffer;Lzombie/core/textures/TextureDraw;Lzombie/core/textures/TextureDraw;Lzombie/core/Styles/Style;)Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0x2B); c.writeByte(0x2C); c.writeByte(0x2D);
        c.writeByte(0xB8); c.writeShort(helperRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0x04); c.writeByte(0xAC); // true, ireturn
        int fallback = cb.size();
        c.writeByte(0x2A); c.writeByte(0x2B); c.writeByte(0x2C); c.writeByte(0x2D);
        c.writeByte(0xB7); c.writeShort(originalRef); // private original
        c.writeByte(0xAC);
        c.flush();
        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, fallback);
        byte[] stackMap = oneSameFrameExtended(fallback);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(4); a.writeShort(4); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    /** CP6.7: exact replacement for Type.tryCastTo, specialized for IsoZombie and generic otherwise. */
    private static MethodInfo makeTypeCastWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                   String renamed, String desc, Target t) throws IOException {
        final String expected = "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;";
        if (!expected.equals(desc) || (original.access & ACC_STATIC) == 0) {
            throw new IOException("CP6.7 typeCast wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int helperClass = r.cp.addClass("zombie/util/MGLPZTypeFast");
        int helperRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("tryCast"), r.cp.addUtf8(expected)));
        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0x2B);
        c.writeByte(0xB8); c.writeShort(helperRef);
        c.writeByte(0xB0);
        c.flush();
        byte[] code = cb.toByteArray();
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(2); a.writeShort(2); a.writeInt(code.length); a.write(code); a.writeShort(0); a.writeShort(0); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    private static MethodInfo makeDrawCensusWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                     String renamed, String desc, Target t) throws IOException {
        if (!"(IIII)V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6.4 drawCensus wrapper shape mismatch: " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int enabledRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("rthreadDrawCensusEnabled"), r.cp.addUtf8("()Z")));
        int helperClass = r.cp.addClass("zombie/core/MGLPZStateRunFast");
        int noteRef = r.cp.addMethodRef(helperClass, r.cp.addNameAndType(
                r.cp.addUtf8("noteDraw"), r.cp.addUtf8("(III)V")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        c.writeByte(0xB8); c.writeShort(enabledRef);
        int ifeqPos = cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0x1C);
        c.writeByte(0x1D);
        c.writeByte(0x15); c.writeByte(4);
        c.writeByte(0xB8); c.writeShort(noteRef);
        int originalOffset = cb.size();
        c.writeByte(0x2A); c.writeByte(0x1B); c.writeByte(0x1C); c.writeByte(0x1D);
        c.writeByte(0x15); c.writeByte(4);
        c.writeByte(0xB7); c.writeShort(originalRef);
        c.writeByte(0xB1); c.flush();

        byte[] code = cb.toByteArray();
        patchBranch(code, ifeqPos, originalOffset);
        byte[] stackMap = oneSameFrameExtended(originalOffset);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(5); a.writeShort(5); a.writeInt(code.length); a.write(code); a.writeShort(0);
        a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs = new ArrayList<Attribute>(); attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) if (!"Code".equals(r.cp.utf8(at.nameIndex))) attrs.add(at.copy());
        return new MethodInfo(original.access & ~ACC_SYNTHETIC, originalNameIndex, original.descIndex, attrs);
    }

    private static MethodInfo makeRthreadProfileWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                         String renamed, String desc, Target t) throws IOException {
        if (!"()V".equals(desc) || (original.access & ACC_STATIC) != 0) {
            throw new IOException("CP6 probe wrapper shape mismatch: " + t.stage + " " + desc);
        }
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int enabledRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("rthreadProfileFastEnabled"), r.cp.addUtf8("()Z")));
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        int boolDesc = r.cp.addUtf8("Z");
        int runningField = r.cp.addFieldRef(ownerClass, r.cp.addNameAndType(r.cp.addUtf8("isRunning"), boolDesc));
        int profilerRunningField = r.cp.addFieldRef(ownerClass, r.cp.addNameAndType(r.cp.addUtf8("isProfilerRunning"), boolDesc));

        int gameProfilerClass = r.cp.addClass("zombie/GameProfiler");
        int gpRunningRef = r.cp.addMethodRef(gameProfilerClass, r.cp.addNameAndType(
                r.cp.addUtf8("isRunning"), r.cp.addUtf8("()Z")));

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        ArrayList<Integer> branchesToOriginal = new ArrayList<Integer>();

        // if (!Optimizer.rthreadProfileFastEnabled()) goto original;
        c.writeByte(0xB8); c.writeShort(enabledRef);
        int p = cb.size(); c.writeByte(0x99); c.writeShort(0); branchesToOriginal.add(Integer.valueOf(p)); // ifeq

        if ("OPT.rthread.profileStart".equals(t.stage)) {
            // if (this.isRunning) goto original; abnormal/stale state stays vanilla-exact.
            c.writeByte(0x2A); c.writeByte(0xB4); c.writeShort(runningField);
            p = cb.size(); c.writeByte(0x9A); c.writeShort(0); branchesToOriginal.add(Integer.valueOf(p)); // ifne

            // if (GameProfiler.isRunning()) goto original;
            c.writeByte(0xB8); c.writeShort(gpRunningRef);
            p = cb.size(); c.writeByte(0x9A); c.writeShort(0); branchesToOriginal.add(Integer.valueOf(p)); // ifne

            // Normal disabled-profiler result: this.isProfilerRunning = false; return.
            c.writeByte(0x2A); c.writeByte(0x03); // aload_0, iconst_0
            c.writeByte(0xB5); c.writeShort(profilerRunningField);
            c.writeByte(0xB1);
        } else {
            // end(): if (!this.isProfilerRunning) return; this avoids the otherwise-side-effect-free
            // valid-thread name lookup. Active profiling always runs the exact vanilla end().
            c.writeByte(0x2A); c.writeByte(0xB4); c.writeShort(profilerRunningField);
            int ifne = cb.size(); c.writeByte(0x9A); c.writeShort(0); branchesToOriginal.add(Integer.valueOf(ifne));
            c.writeByte(0xB1);
        }

        int originalOffset = cb.size();
        c.writeByte(0x2A);
        c.writeByte((original.access & ACC_PRIVATE) != 0 ? 0xB7 : 0xB6);
        c.writeShort(originalRef);
        c.writeByte(0xB1);
        c.flush();
        byte[] code = cb.toByteArray();
        for (Integer bp : branchesToOriginal) patchBranch(code, bp.intValue(), originalOffset);

        byte[] stackMap = oneSameFrameExtended(originalOffset);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(2); // max_stack
        a.writeShort(1); // max_locals = this
        a.writeInt(code.length); a.write(code);
        a.writeShort(0); // exception table
        a.writeShort(1); // Code attributes
        a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap);
        a.flush();

        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) {
            String an = r.cp.utf8(at.nameIndex);
            if (!"Code".equals(an)) attrs.add(at.copy());
        }
        int publicAccess = original.access & ~ACC_SYNTHETIC;
        return new MethodInfo(publicAccess, originalNameIndex, original.descIndex, attrs);
    }

    private static MethodInfo makeConditionalVoidWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                          String renamed, String desc, Target t) throws IOException {
        if (returnKind(desc) != 'V') throw new IOException("conditional fast wrapper requires void: " + t.stage);
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int profilerClass = r.cp.addClass("mglpz/chunkagent/Profiler");
        int enterRef = r.cp.addMethodRef(profilerClass, r.cp.addNameAndType(
                r.cp.addUtf8("enter"), r.cp.addUtf8("(Ljava/lang/String;Ljava/lang/Object;)V")));
        int exitRef = r.cp.addMethodRef(profilerClass, r.cp.addNameAndType(
                r.cp.addUtf8("exit"), r.cp.addUtf8("(Ljava/lang/String;Ljava/lang/Object;)V")));
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        String handlerName, handlerDesc, afterName = null, afterDesc = null;
        if ("FORAGE.genForaging".equals(t.stage)) {
            handlerName="handleGenForaging"; handlerDesc="(Ljava/lang/Object;II)Z";
        } else if ("DS.grid.addVehicles".equals(t.stage)) {
            handlerName="handleAddVehicles"; handlerDesc="(Ljava/lang/Object;)Z";
        } else if ("DS.grid.randomizeBuildings".equals(t.stage)) {
            handlerName="handleRandomizeBuildings"; handlerDesc="(Ljava/lang/Object;Ljava/util/ArrayList;)Z";
        } else if ("DS.grid.loadGridWorkaround".equals(t.stage)) {
            handlerName="handleLoadGridSquare"; handlerDesc="(Ljava/lang/Object;)Z";
        } else if ("DS.grid.luaNewSquare".equals(t.stage)) {
            handlerName="handleLuaNewSquare"; handlerDesc="(Ljava/lang/Object;)Z";
        } else if ("DS.worker.recalculateAll2".equals(t.stage)) {
            handlerName="skipRecalc2"; handlerDesc="(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z";
            afterName="afterRecalc2"; afterDesc="(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
        } else if ("DS.worker.recalculateAll3".equals(t.stage)) {
            handlerName="skipRecalc3"; handlerDesc="(Ljava/lang/Object;ZLjava/lang/Object;Ljava/lang/Object;)Z";
            afterName="afterRecalc3"; afterDesc="(Ljava/lang/Object;ZLjava/lang/Object;Ljava/lang/Object;)V";
        } else throw new IOException("unknown fast stage " + t.stage);
        int handlerRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8(handlerName), r.cp.addUtf8(handlerDesc)));
        int afterRef = afterName == null ? 0 : r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8(afterName), r.cp.addUtf8(afterDesc)));
        int stageStr = r.cp.addString(t.stage);
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        emitLdc(c, stageStr); emitChunkLoad(c, t.chunkLocal);
        c.writeByte(0xB8); c.writeShort(enterRef);

        emitFastHandlerArgs(c, t.stage);
        c.writeByte(0xB8); c.writeShort(handlerRef);
        int ifnePos = cb.size(); c.writeByte(0x9A); c.writeShort(0); // ifne -> skip original

        final boolean isStatic = (original.access & ACC_STATIC) != 0;
        int local = isStatic ? 0 : 1;
        if (!isStatic) c.writeByte(0x2A);
        List<Character> args = parseArgs(desc);
        for (Character k : args) { emitLoad(c, k.charValue(), local); local += slots(k.charValue()); }
        if (isStatic) c.writeByte(0xB8);
        else c.writeByte((original.access & ACC_PRIVATE) != 0 ? 0xB7 : 0xB6);
        c.writeShort(originalRef);

        if (afterRef != 0) {
            emitFastHandlerArgs(c, t.stage);
            c.writeByte(0xB8); c.writeShort(afterRef);
        }

        int skipOffset = cb.size();
        emitLdc(c, stageStr); emitChunkLoad(c, t.chunkLocal);
        c.writeByte(0xB8); c.writeShort(exitRef);
        c.writeByte(0xB1);
        c.flush();
        byte[] code = cb.toByteArray();
        patchBranch(code, ifnePos, skipOffset);

        byte[] stackMap = oneSameFrameExtended(skipOffset);
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(ab);
        a.writeShort(20); a.writeShort(Math.max(local, 1));
        a.writeInt(code.length); a.write(code);
        a.writeShort(0); // exception table
        a.writeShort(1); // code attrs
        a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap);
        a.flush();

        ArrayList<Attribute> attrs = new ArrayList<Attribute>();
        attrs.add(new Attribute(codeName, ab.toByteArray()));
        for (Attribute at : original.attrs) {
            String an = r.cp.utf8(at.nameIndex);
            if (!"Code".equals(an)) attrs.add(at.copy());
        }
        int publicAccess = original.access & ~ACC_SYNTHETIC;
        return new MethodInfo(publicAccess, originalNameIndex, original.descIndex, attrs);
    }

    private static void emitFastHandlerArgs(DataOutputStream c, String stage) throws IOException {
        if ("FORAGE.genForaging".equals(stage)) {
            emitALoad(c,0); emitLoad(c,'I',1); emitLoad(c,'I',2);
        } else if ("DS.grid.addVehicles".equals(stage)) {
            emitALoad(c,0);
        } else if ("DS.grid.randomizeBuildings".equals(stage)) {
            emitALoad(c,0); emitALoad(c,1);
        } else if ("DS.grid.loadGridWorkaround".equals(stage) || "DS.grid.luaNewSquare".equals(stage)) {
            emitALoad(c,0); // static method arg0
        } else if ("DS.worker.recalculateAll2".equals(stage)) {
            emitALoad(c,0); emitALoad(c,1); emitALoad(c,2);
        } else if ("DS.worker.recalculateAll3".equals(stage)) {
            emitALoad(c,0); emitLoad(c,'I',1); emitALoad(c,2); emitALoad(c,3);
        } else throw new IOException("no handler args for " + stage);
    }


    /** CP6.13: hitch-only WorldSimulation fixed-step smoother. */
    private static MethodInfo makeCP613PhysicsWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                       String renamed, String desc, Target t) throws IOException {
        if (!"()V".equals(desc) || (original.access & ACC_STATIC) != 0) throw new IOException("CP613 physics shape " + desc);
        int codeName=r.cp.addUtf8("Code"), stackMapName=r.cp.addUtf8("StackMapTable");
        int helper=r.cp.addClass("zombie/core/physics/MGLPZCP613PhysicsFix");
        int hRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8("tryUpdate"),r.cp.addUtf8("(Lzombie/core/physics/WorldSimulation;)Z")));
        int oRef=r.cp.addMethodRef(r.thisClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream(); DataOutputStream c=new DataOutputStream(cb);
        c.writeByte(0x2A); c.writeByte(0xB8); c.writeShort(hRef);
        int ifeq=cb.size(); c.writeByte(0x99); c.writeShort(0); c.writeByte(0xB1);
        int fallback=cb.size(); c.writeByte(0x2A); c.writeByte((original.access&ACC_PRIVATE)!=0?0xB7:0xB6); c.writeShort(oRef); c.writeByte(0xB1); c.flush();
        byte[] code=cb.toByteArray(); patchBranch(code,ifeq,fallback); byte[] sm=oneSameFrameExtended(fallback);
        ByteArrayOutputStream ab=new ByteArrayOutputStream(); DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(1);a.writeShort(1);a.writeInt(code.length);a.write(code);a.writeShort(0);a.writeShort(1);a.writeShort(stackMapName);a.writeInt(sm.length);a.write(sm);a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>();attrs.add(new Attribute(codeName,ab.toByteArray()));for(Attribute at:original.attrs)if(!"Code".equals(r.cp.utf8(at.nameIndex)))attrs.add(at.copy());
        return new MethodInfo(original.access&~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    /** CP6.13: fair moving-object bucket burst smoother. */
    private static MethodInfo makeCP613MovingWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                      String renamed, String desc, Target t) throws IOException {
        if (!"(I)V".equals(desc) || (original.access & ACC_STATIC) != 0) throw new IOException("CP613 moving shape " + desc);
        int codeName=r.cp.addUtf8("Code"), stackMapName=r.cp.addUtf8("StackMapTable");
        int helper=r.cp.addClass("zombie/MGLPZCP613MovingFix");
        String hn="CP613.moving.bucketPost".equals(t.stage)?"tryPostUpdate":"tryUpdate";
        int hRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8(hn),r.cp.addUtf8("(Lzombie/MovingObjectUpdateSchedulerUpdateBucket;I)Z")));
        int oRef=r.cp.addMethodRef(r.thisClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream();DataOutputStream c=new DataOutputStream(cb);
        c.writeByte(0x2A);c.writeByte(0x1B);c.writeByte(0xB8);c.writeShort(hRef);
        int ifeq=cb.size();c.writeByte(0x99);c.writeShort(0);c.writeByte(0xB1);
        int fallback=cb.size();c.writeByte(0x2A);c.writeByte(0x1B);c.writeByte((original.access&ACC_PRIVATE)!=0?0xB7:0xB6);c.writeShort(oRef);c.writeByte(0xB1);c.flush();
        byte[] code=cb.toByteArray();patchBranch(code,ifeq,fallback);byte[] sm=oneSameFrameExtended(fallback);
        ByteArrayOutputStream ab=new ByteArrayOutputStream();DataOutputStream a=new DataOutputStream(ab);a.writeShort(2);a.writeShort(2);a.writeInt(code.length);a.write(code);a.writeShort(0);a.writeShort(1);a.writeShort(stackMapName);a.writeInt(sm.length);a.write(sm);a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>();attrs.add(new Attribute(codeName,ab.toByteArray()));for(Attribute at:original.attrs)if(!"Code".equals(r.cp.utf8(at.nameIndex)))attrs.add(at.copy());return new MethodInfo(original.access&~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    /** Reset FBO dirty-work budget before vanilla scans dirty chunk-levels. */
    private static MethodInfo makeCP613FboPassWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                       String renamed, String desc, Target t) throws IOException {
        if (!"(I)V".equals(desc) || (original.access & ACC_STATIC) != 0) throw new IOException("CP613 fbo pass shape " + desc);
        int codeName=r.cp.addUtf8("Code");int helper=r.cp.addClass("zombie/iso/fboRenderChunk/MGLPZCP613FBOFix");
        int hRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8("beginPass"),r.cp.addUtf8("(I)V")));
        int oRef=r.cp.addMethodRef(r.thisClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream();DataOutputStream c=new DataOutputStream(cb);c.writeByte(0x1B);c.writeByte(0xB8);c.writeShort(hRef);c.writeByte(0x2A);c.writeByte(0x1B);c.writeByte((original.access&ACC_PRIVATE)!=0?0xB7:0xB6);c.writeShort(oRef);c.writeByte(0xB1);c.flush();
        byte[] code=cb.toByteArray();ByteArrayOutputStream ab=new ByteArrayOutputStream();DataOutputStream a=new DataOutputStream(ab);a.writeShort(2);a.writeShort(2);a.writeInt(code.length);a.write(code);a.writeShort(0);a.writeShort(0);a.flush();ArrayList<Attribute>attrs=new ArrayList<Attribute>();attrs.add(new Attribute(codeName,ab.toByteArray()));for(Attribute at:original.attrs)if(!"Code".equals(r.cp.utf8(at.nameIndex)))attrs.add(at.copy());return new MethodInfo(original.access&~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    /** Skip only excess FBO prepareChunkForUpdating calls; dirty state remains for next pass. */
    private static MethodInfo makeCP613FboChunkWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                        String renamed, String desc, Target t) throws IOException {
        String ex="(ILzombie/iso/IsoChunk;I)V";if(!ex.equals(desc)||(original.access&ACC_STATIC)!=0)throw new IOException("CP613 fbo chunk shape "+desc);
        int codeName=r.cp.addUtf8("Code"),stackMapName=r.cp.addUtf8("StackMapTable");int helper=r.cp.addClass("zombie/iso/fboRenderChunk/MGLPZCP613FBOFix");
        int hRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8("allowChunk"),r.cp.addUtf8("(ILzombie/iso/IsoChunk;I)Z")));int oRef=r.cp.addMethodRef(r.thisClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream();DataOutputStream c=new DataOutputStream(cb);c.writeByte(0x1B);c.writeByte(0x2C);c.writeByte(0x1D);c.writeByte(0xB8);c.writeShort(hRef);int ifne=cb.size();c.writeByte(0x9A);c.writeShort(0);c.writeByte(0xB1);int run=cb.size();c.writeByte(0x2A);c.writeByte(0x1B);c.writeByte(0x2C);c.writeByte(0x1D);c.writeByte((original.access&ACC_PRIVATE)!=0?0xB7:0xB6);c.writeShort(oRef);c.writeByte(0xB1);c.flush();byte[]code=cb.toByteArray();patchBranch(code,ifne,run);byte[]sm=oneSameFrameExtended(run);ByteArrayOutputStream ab=new ByteArrayOutputStream();DataOutputStream a=new DataOutputStream(ab);a.writeShort(4);a.writeShort(4);a.writeInt(code.length);a.write(code);a.writeShort(0);a.writeShort(1);a.writeShort(stackMapName);a.writeInt(sm.length);a.write(sm);a.flush();ArrayList<Attribute>attrs=new ArrayList<Attribute>();attrs.add(new Attribute(codeName,ab.toByteArray()));for(Attribute at:original.attrs)if(!"Code".equals(r.cp.utf8(at.nameIndex)))attrs.add(at.copy());return new MethodInfo(original.access&~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }


    /** CP6.13.4 exact FBO inner-loop fastpath; true means helper fully reproduced vanilla work. */
    private static MethodInfo makeCP6134FboChunkFastWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                             String renamed, String desc, Target t) throws IOException {
        String ex="(ILzombie/iso/IsoChunk;I)V";if(!ex.equals(desc)||(original.access&ACC_STATIC)!=0)throw new IOException("CP6134 fbo chunk fast shape "+desc);
        int codeName=r.cp.addUtf8("Code"),stackMapName=r.cp.addUtf8("StackMapTable");
        int helper=r.cp.addClass("zombie/iso/fboRenderChunk/MGLPZCP6134FBOFast");
        int hRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8("tryPrepareChunk"),r.cp.addUtf8("(Lzombie/iso/fboRenderChunk/FBORenderCell;ILzombie/iso/IsoChunk;I)Z")));
        int oRef=r.cp.addMethodRef(r.thisClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream();DataOutputStream c=new DataOutputStream(cb);
        c.writeByte(0x2A);c.writeByte(0x1B);c.writeByte(0x2C);c.writeByte(0x1D);c.writeByte(0xB8);c.writeShort(hRef);
        int ifeq=cb.size();c.writeByte(0x99);c.writeShort(0);c.writeByte(0xB1);
        int fallback=cb.size();c.writeByte(0x2A);c.writeByte(0x1B);c.writeByte(0x2C);c.writeByte(0x1D);c.writeByte((original.access&ACC_PRIVATE)!=0?0xB7:0xB6);c.writeShort(oRef);c.writeByte(0xB1);c.flush();
        byte[]code=cb.toByteArray();patchBranch(code,ifeq,fallback);byte[]sm=oneSameFrameExtended(fallback);
        ByteArrayOutputStream ab=new ByteArrayOutputStream();DataOutputStream a=new DataOutputStream(ab);a.writeShort(4);a.writeShort(4);a.writeInt(code.length);a.write(code);a.writeShort(0);a.writeShort(1);a.writeShort(stackMapName);a.writeInt(sm.length);a.write(sm);a.flush();
        ArrayList<Attribute>attrs=new ArrayList<Attribute>();attrs.add(new Attribute(codeName,ab.toByteArray()));for(Attribute at:original.attrs)if(!"Code".equals(r.cp.utf8(at.nameIndex)))attrs.add(at.copy());return new MethodInfo(original.access&~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    /** Defer a LightingJNI tick instead of blocking MainThread on an unfinished checkLights future. */
    private static MethodInfo makeCP613LightingWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                        String renamed, String desc, Target t) throws IOException {
        if(!"()V".equals(desc)||(original.access&ACC_STATIC)==0)throw new IOException("CP613 lighting shape "+desc);
        int codeName=r.cp.addUtf8("Code"),stackMapName=r.cp.addUtf8("StackMapTable");int helper=r.cp.addClass("zombie/iso/MGLPZCP613LightingFix");int hRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8("shouldDefer"),r.cp.addUtf8("()Z")));int oRef=r.cp.addMethodRef(r.thisClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));ByteArrayOutputStream cb=new ByteArrayOutputStream();DataOutputStream c=new DataOutputStream(cb);c.writeByte(0xB8);c.writeShort(hRef);int ifeq=cb.size();c.writeByte(0x99);c.writeShort(0);c.writeByte(0xB1);int run=cb.size();c.writeByte(0xB8);c.writeShort(oRef);c.writeByte(0xB1);c.flush();byte[]code=cb.toByteArray();patchBranch(code,ifeq,run);byte[]sm=oneSameFrameExtended(run);ByteArrayOutputStream ab=new ByteArrayOutputStream();DataOutputStream a=new DataOutputStream(ab);a.writeShort(1);a.writeShort(0);a.writeInt(code.length);a.write(code);a.writeShort(0);a.writeShort(1);a.writeShort(stackMapName);a.writeInt(sm.length);a.write(sm);a.flush();ArrayList<Attribute>attrs=new ArrayList<Attribute>();attrs.add(new Attribute(codeName,ab.toByteArray()));for(Attribute at:original.attrs)if(!"Code".equals(r.cp.utf8(at.nameIndex)))attrs.add(at.copy());return new MethodInfo(original.access&~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }


    /** CP6.13: if one ragdoll update was catastrophically slow, reuse its last transform for only
     * the next sample. Normal calls are timed around the renamed vanilla method. */
    private static MethodInfo makeCP613RagdollUpdateWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                             String renamed, String desc, Target t) throws IOException {
        final String ex="(FLorg/lwjgl/util/vector/Vector3f;Lorg/lwjgl/util/vector/Quaternion;)V";
        if(!ex.equals(desc)||(original.access&ACC_STATIC)!=0)throw new IOException("CP613 ragdoll update shape "+desc);
        int codeName=r.cp.addUtf8("Code"),stackMapName=r.cp.addUtf8("StackMapTable");
        int helper=r.cp.addClass("zombie/core/physics/MGLPZCP613RagdollFix");
        int shouldRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8("shouldSkip"),r.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z")));
        int afterRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8("after"),r.cp.addUtf8("(Ljava/lang/Object;J)V")));
        int sys=r.cp.addClass("java/lang/System");
        int nanoRef=r.cp.addMethodRef(sys,r.cp.addNameAndType(r.cp.addUtf8("nanoTime"),r.cp.addUtf8("()J")));
        int oRef=r.cp.addMethodRef(r.thisClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream();DataOutputStream c=new DataOutputStream(cb);
        c.writeByte(0x2A);c.writeByte(0x2C);c.writeByte(0x2D);c.writeByte(0xB8);c.writeShort(shouldRef);
        int ifeq=cb.size();c.writeByte(0x99);c.writeShort(0);c.writeByte(0xB1);
        int run=cb.size();
        c.writeByte(0xB8);c.writeShort(nanoRef);c.writeByte(0x37);c.writeByte(4); // lstore 4
        c.writeByte(0x2A);c.writeByte(0x23);c.writeByte(0x2C);c.writeByte(0x2D);
        c.writeByte((original.access&ACC_PRIVATE)!=0?0xB7:0xB6);c.writeShort(oRef);
        c.writeByte(0x2A);c.writeByte(0xB8);c.writeShort(nanoRef);c.writeByte(0x16);c.writeByte(4);c.writeByte(0x65); // lsub
        c.writeByte(0xB8);c.writeShort(afterRef);c.writeByte(0xB1);c.flush();
        byte[]code=cb.toByteArray();patchBranch(code,ifeq,run);byte[]sm=oneSameFrameExtended(run);
        ByteArrayOutputStream ab=new ByteArrayOutputStream();DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(5);a.writeShort(6);a.writeInt(code.length);a.write(code);a.writeShort(0);a.writeShort(1);a.writeShort(stackMapName);a.writeInt(sm.length);a.write(sm);a.flush();
        ArrayList<Attribute>attrs=new ArrayList<Attribute>();attrs.add(new Attribute(codeName,ab.toByteArray()));for(Attribute at:original.attrs)if(!"Code".equals(r.cp.utf8(at.nameIndex)))attrs.add(at.copy());
        return new MethodInfo(original.access&~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    private static MethodInfo makeCP613RagdollPostWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                           String renamed, String desc, Target t) throws IOException {
        if(!"(F)V".equals(desc)||(original.access&ACC_STATIC)!=0)throw new IOException("CP613 ragdoll post shape "+desc);
        int codeName=r.cp.addUtf8("Code"),stackMapName=r.cp.addUtf8("StackMapTable");int helper=r.cp.addClass("zombie/core/physics/MGLPZCP613RagdollFix");
        int hRef=r.cp.addMethodRef(helper,r.cp.addNameAndType(r.cp.addUtf8("shouldSkipPost"),r.cp.addUtf8("(Ljava/lang/Object;)Z")));int oRef=r.cp.addMethodRef(r.thisClass,r.cp.addNameAndType(r.cp.addUtf8(renamed),original.descIndex));
        ByteArrayOutputStream cb=new ByteArrayOutputStream();DataOutputStream c=new DataOutputStream(cb);c.writeByte(0x2A);c.writeByte(0xB8);c.writeShort(hRef);int ifeq=cb.size();c.writeByte(0x99);c.writeShort(0);c.writeByte(0xB1);int run=cb.size();c.writeByte(0x2A);c.writeByte(0x23);c.writeByte((original.access&ACC_PRIVATE)!=0?0xB7:0xB6);c.writeShort(oRef);c.writeByte(0xB1);c.flush();
        byte[]code=cb.toByteArray();patchBranch(code,ifeq,run);byte[]sm=oneSameFrameExtended(run);ByteArrayOutputStream ab=new ByteArrayOutputStream();DataOutputStream a=new DataOutputStream(ab);a.writeShort(2);a.writeShort(2);a.writeInt(code.length);a.write(code);a.writeShort(0);a.writeShort(1);a.writeShort(stackMapName);a.writeInt(sm.length);a.write(sm);a.flush();ArrayList<Attribute>attrs=new ArrayList<Attribute>();attrs.add(new Attribute(codeName,ab.toByteArray()));for(Attribute at:original.attrs)if(!"Code".equals(r.cp.utf8(at.nameIndex)))attrs.add(at.copy());return new MethodInfo(original.access&~ACC_SYNTHETIC,originalNameIndex,original.descIndex,attrs);
    }

    private static MethodInfo makeMapBiomeWrapper(Reader r, MethodInfo original, int originalNameIndex,
                                                   String renamed, String desc, Target t) throws IOException {
        int codeName = r.cp.addUtf8("Code");
        int stackMapName = r.cp.addUtf8("StackMapTable");
        int optimizerClass = r.cp.addClass("mglpz/chunkagent/Optimizer");
        int hasRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("mapBiomeHas"), r.cp.addUtf8("(Ljava/lang/Object;IILjava/lang/String;)Z")));
        int lastRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("mapBiomeLast"), r.cp.addUtf8("()Ljava/lang/Object;")));
        int putRef = r.cp.addMethodRef(optimizerClass, r.cp.addNameAndType(
                r.cp.addUtf8("mapBiomePut"), r.cp.addUtf8("(Ljava/lang/Object;IILjava/lang/String;Ljava/lang/Object;)V")));
        int resultClass = r.cp.addClass("zombie/iso/worldgen/biomes/IBiome");
        int ownerClass = r.thisClass;
        int renamedNT = r.cp.addNameAndType(r.cp.addUtf8(renamed), original.descIndex);
        int originalRef = r.cp.addMethodRef(ownerClass, renamedNT);

        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cb);
        emitALoad(c,0); emitLoad(c,'I',1); emitLoad(c,'I',2); emitALoad(c,3);
        c.writeByte(0xB8); c.writeShort(hasRef);
        int ifeqPos=cb.size(); c.writeByte(0x99); c.writeShort(0);
        c.writeByte(0xB8); c.writeShort(lastRef);
        c.writeByte(0xC0); c.writeShort(resultClass);
        c.writeByte(0xB0);
        int missOffset=cb.size();
        emitALoad(c,0); emitLoad(c,'I',1); emitLoad(c,'I',2); emitALoad(c,3);
        c.writeByte(0xB6); c.writeShort(originalRef);
        emitStore(c,'L',4);
        emitALoad(c,0); emitLoad(c,'I',1); emitLoad(c,'I',2); emitALoad(c,3); emitALoad(c,4);
        c.writeByte(0xB8); c.writeShort(putRef);
        emitALoad(c,4); c.writeByte(0xB0);
        c.flush();
        byte[] code=cb.toByteArray(); patchBranch(code,ifeqPos,missOffset);
        byte[] stackMap=oneSameFrameExtended(missOffset);

        ByteArrayOutputStream ab=new ByteArrayOutputStream(); DataOutputStream a=new DataOutputStream(ab);
        a.writeShort(12); a.writeShort(5); a.writeInt(code.length); a.write(code);
        a.writeShort(0); a.writeShort(1); a.writeShort(stackMapName); a.writeInt(stackMap.length); a.write(stackMap); a.flush();
        ArrayList<Attribute> attrs=new ArrayList<Attribute>(); attrs.add(new Attribute(codeName,ab.toByteArray()));
        for(Attribute at:original.attrs){String an=r.cp.utf8(at.nameIndex); if(!"Code".equals(an))attrs.add(at.copy());}
        int publicAccess=original.access & ~ACC_SYNTHETIC;
        return new MethodInfo(publicAccess,originalNameIndex,original.descIndex,attrs);
    }

    private static void patchBranch(byte[] code, int opPos, int target) throws IOException {
        int off=target-opPos;
        if(off < Short.MIN_VALUE || off > Short.MAX_VALUE) throw new IOException("branch too far");
        code[opPos+1]=(byte)(off>>>8); code[opPos+2]=(byte)off;
    }
    private static byte[] oneSameFrameExtended(int targetOffset) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream o=new DataOutputStream(b);
        o.writeShort(1); o.writeByte(251); o.writeShort(targetOffset); o.flush(); return b.toByteArray();
    }

    private static void emitLdc(DataOutputStream o, int index) throws IOException {
        if (index <= 255) { o.writeByte(0x12); o.writeByte(index); }
        else { o.writeByte(0x13); o.writeShort(index); }
    }
    private static void emitChunkLoad(DataOutputStream o, int local) throws IOException {
        if (local < 0) o.writeByte(0x01); // aconst_null
        else emitALoad(o, local);
    }
    private static void emitALoad(DataOutputStream o, int local) throws IOException {
        if (local >= 0 && local <= 3) o.writeByte(0x2A + local);
        else { o.writeByte(0x19); o.writeByte(local); }
    }
    private static void emitLoad(DataOutputStream o, char kind, int local) throws IOException {
        int opcode;
        if (kind == 'L' || kind == '[') { emitALoad(o, local); return; }
        if (kind == 'J') opcode = 0x16;
        else if (kind == 'F') opcode = 0x17;
        else if (kind == 'D') opcode = 0x18;
        else opcode = 0x15;
        o.writeByte(opcode); o.writeByte(local);
    }
    private static void emitStore(DataOutputStream o, char kind, int local) throws IOException {
        int opcode;
        if (kind == 'L' || kind == '[') opcode = 0x3A;
        else if (kind == 'J') opcode = 0x37;
        else if (kind == 'F') opcode = 0x38;
        else if (kind == 'D') opcode = 0x39;
        else opcode = 0x36;
        o.writeByte(opcode); o.writeByte(local);
    }
    private static void emitReturn(DataOutputStream o, char kind) throws IOException {
        if (kind == 'V') o.writeByte(0xB1);
        else if (kind == 'L' || kind == '[') o.writeByte(0xB0);
        else if (kind == 'J') o.writeByte(0xAD);
        else if (kind == 'F') o.writeByte(0xAE);
        else if (kind == 'D') o.writeByte(0xAF);
        else o.writeByte(0xAC);
    }
    private static int slots(char kind) { return (kind == 'J' || kind == 'D') ? 2 : 1; }
    private static char returnKind(String desc) throws IOException {
        int close = desc.indexOf(')');
        if (close < 0 || close + 1 >= desc.length()) throw new IOException("bad descriptor: " + desc);
        char c = desc.charAt(close + 1);
        if (c == 'L') return 'L';
        if (c == '[') return '[';
        return c;
    }
    private static List<Character> parseArgs(String desc) throws IOException {
        ArrayList<Character> out = new ArrayList<Character>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'L') {
                out.add('L'); i = desc.indexOf(';', i) + 1; if (i == 0) throw new IOException("bad descriptor");
            } else if (c == '[') {
                out.add('['); i++; while (desc.charAt(i) == '[') i++;
                if (desc.charAt(i) == 'L') i = desc.indexOf(';', i) + 1; else i++;
            } else { out.add(c); i++; }
        }
        if (i >= desc.length() || desc.charAt(i) != ')') throw new IOException("bad descriptor: " + desc);
        return out;
    }

    private static final class Reader {
        final int magic, minor, major;
        final ConstantPool cp;
        int access, thisClass, superClass;
        int[] interfaces;
        List<Member> fields;
        List<MethodInfo> methods;
        List<Attribute> attrs;

        Reader(byte[] b) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            magic=in.readInt(); minor=in.readUnsignedShort(); major=in.readUnsignedShort();
            cp = ConstantPool.read(in);
            access=in.readUnsignedShort(); thisClass=in.readUnsignedShort(); superClass=in.readUnsignedShort();
            interfaces = new int[in.readUnsignedShort()]; for(int i=0;i<interfaces.length;i++) interfaces[i]=in.readUnsignedShort();
            fields = readMembers(in);
            int mc=in.readUnsignedShort(); methods=new ArrayList<MethodInfo>(mc);
            for(int i=0;i<mc;i++) methods.add(readMethod(in));
            attrs=readAttrs(in);
        }
        byte[] write() throws IOException {
            ByteArrayOutputStream bout=new ByteArrayOutputStream(); DataOutputStream o=new DataOutputStream(bout);
            o.writeInt(magic); o.writeShort(minor); o.writeShort(major); cp.write(o);
            o.writeShort(access); o.writeShort(thisClass); o.writeShort(superClass);
            o.writeShort(interfaces.length); for(int v:interfaces)o.writeShort(v);
            o.writeShort(fields.size()); for(Member m:fields)m.write(o);
            o.writeShort(methods.size()); for(MethodInfo m:methods)m.write(o);
            writeAttrs(o, attrs); o.flush(); return bout.toByteArray();
        }
        private List<Member> readMembers(DataInputStream in)throws IOException{
            int n=in.readUnsignedShort(); ArrayList<Member> x=new ArrayList<Member>(n);
            for(int i=0;i<n;i++) x.add(new Member(in.readUnsignedShort(),in.readUnsignedShort(),in.readUnsignedShort(),readAttrs(in)));
            return x;
        }
        private MethodInfo readMethod(DataInputStream in)throws IOException{
            return new MethodInfo(in.readUnsignedShort(),in.readUnsignedShort(),in.readUnsignedShort(),readAttrs(in));
        }
        private List<Attribute> readAttrs(DataInputStream in)throws IOException{
            int n=in.readUnsignedShort(); ArrayList<Attribute>x=new ArrayList<Attribute>(n);
            for(int i=0;i<n;i++){int ni=in.readUnsignedShort();int len=in.readInt();byte[]d=new byte[len];in.readFully(d);x.add(new Attribute(ni,d));}
            return x;
        }
    }

    private static class Member {
        int access,nameIndex,descIndex; List<Attribute> attrs;
        Member(int a,int n,int d,List<Attribute>x){access=a;nameIndex=n;descIndex=d;attrs=x;}
        void write(DataOutputStream o)throws IOException{o.writeShort(access);o.writeShort(nameIndex);o.writeShort(descIndex);writeAttrs(o,attrs);}
    }
    private static final class MethodInfo extends Member {
        MethodInfo(int a,int n,int d,List<Attribute>x){super(a,n,d,x);}
    }
    private static final class Attribute {
        final int nameIndex; final byte[] data;
        Attribute(int n,byte[]d){nameIndex=n;data=d;}
        Attribute copy(){return new Attribute(nameIndex,data.clone());}
    }
    private static void writeAttrs(DataOutputStream o,List<Attribute>a)throws IOException{
        o.writeShort(a.size());for(Attribute x:a){o.writeShort(x.nameIndex);o.writeInt(x.data.length);o.write(x.data);}
    }

    private static final class ConstantPool {
        final ArrayList<Cp> entries = new ArrayList<Cp>(); // slot 0 = null
        final Map<String,Integer> utf8 = new HashMap<String,Integer>();
        ConstantPool(){entries.add(null);}
        static ConstantPool read(DataInputStream in)throws IOException{
            ConstantPool p=new ConstantPool(); int count=in.readUnsignedShort();
            for(int i=1;i<count;i++){
                int tag=in.readUnsignedByte(); ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream o=new DataOutputStream(b); o.writeByte(tag);
                String text=null;
                switch(tag){
                    case 1:{int len=in.readUnsignedShort();byte[]d=new byte[len];in.readFully(d);o.writeShort(len);o.write(d);text=new String(d,StandardCharsets.UTF_8);break;}
                    case 3: case 4:o.writeInt(in.readInt());break;
                    case 5: case 6:o.writeLong(in.readLong());break;
                    case 7: case 8: case 16: case 19: case 20:o.writeShort(in.readUnsignedShort());break;
                    case 9: case 10: case 11: case 12: case 17: case 18:o.writeShort(in.readUnsignedShort());o.writeShort(in.readUnsignedShort());break;
                    case 15:o.writeByte(in.readUnsignedByte());o.writeShort(in.readUnsignedShort());break;
                    default:throw new IOException("unsupported cp tag "+tag);
                }
                o.flush(); p.entries.add(new Cp(tag,b.toByteArray(),text));
                if(text!=null)p.utf8.put(text,i);
                if(tag==5||tag==6){p.entries.add(null);i++;}
            }
            return p;
        }
        void write(DataOutputStream o)throws IOException{
            o.writeShort(entries.size());for(int i=1;i<entries.size();i++){Cp c=entries.get(i);if(c!=null)o.write(c.raw);}
        }
        String utf8(int i){Cp c=entries.get(i);return c==null?null:c.text;}
        int addUtf8(String s)throws IOException{
            Integer got=utf8.get(s);if(got!=null)return got;
            byte[]d=s.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);o.writeByte(1);o.writeShort(d.length);o.write(d);o.flush();
            int idx=entries.size();entries.add(new Cp(1,b.toByteArray(),s));utf8.put(s,idx);return idx;
        }
        int addClass(String internal)throws IOException{return addOneIndex(7,addUtf8(internal));}
        int addString(String s)throws IOException{return addOneIndex(8,addUtf8(s));}
        int addNameAndType(int n,int d)throws IOException{return addTwoIndex(12,n,d);}
        int addFieldRef(int c,int nt)throws IOException{return addTwoIndex(9,c,nt);}
        int addMethodRef(int c,int nt)throws IOException{return addTwoIndex(10,c,nt);}
        private int addOneIndex(int tag,int x)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);o.writeByte(tag);o.writeShort(x);o.flush();int i=entries.size();entries.add(new Cp(tag,b.toByteArray(),null));return i;}
        private int addTwoIndex(int tag,int a,int bidx)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);o.writeByte(tag);o.writeShort(a);o.writeShort(bidx);o.flush();int i=entries.size();entries.add(new Cp(tag,b.toByteArray(),null));return i;}
    }
    private static final class Cp { final int tag; final byte[] raw; final String text; Cp(int t,byte[]r,String s){tag=t;raw=r;text=s;} }
}
