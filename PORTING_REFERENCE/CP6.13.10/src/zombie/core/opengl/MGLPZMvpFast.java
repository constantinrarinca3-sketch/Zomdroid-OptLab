package zombie.core.opengl;

import org.joml.Matrix4f;
import zombie.core.Core;
import mglpz.chunkagent.Optimizer;

/**
 * CP6.3 fastpath for VertexBufferObject.setModelViewProjection(ShaderProgram).
 *
 * Build 42.20.3 vanilla does two pieces of work before discovering that MVP is unchanged:
 *   1) ShaderProgram.getUniform("ModelViewProjection", GL_FLOAT_MAT4)
 *   2) copies model-view and projection stack matrices into temporary Matrix4f objects.
 * On the changed path it then calls ShaderProgram.setValue(String, Matrix4f), which performs a
 * second getUniform lookup for the same name.
 *
 * This helper preserves vanilla semantics but:
 *   - compares current stack matrices directly with ShaderProgram.modelView/projection first;
 *   - unchanged path returns before any uniform lookup/copy;
 *   - changed path performs one getUniform and calls package-private setTransformMatrix directly.
 *
 * ShaderProgram.compile() in B42.20.3 resets modelView/projection to identity before recompiling,
 * so the public matrices themselves provide the same invalidation behavior used by vanilla.
 */
public final class MGLPZMvpFast {
    private static final int GL_FLOAT_MAT4 = 35676;
    private static final int PUBLISH_MASK = 4095;
    private static final Matrix4f IDENTITY = new Matrix4f();

    private static final ThreadLocal<Stats> STATS = new ThreadLocal<Stats>() {
        @Override protected Stats initialValue() { return new Stats(); }
    };

    private MGLPZMvpFast() {}

    /** true = fully handled; false = wrapper must execute renamed vanilla method. */
    public static boolean trySet(ShaderProgram program) {
        if (Optimizer.rthreadLeanTelemetryEnabled()) return trySetLean(program);
        Stats s = STATS.get();
        ++s.calls;
        if (!Optimizer.rthreadMvpFastEnabled()) {
            ++s.toggleFallback; s.publishMaybe(); return false;
        }
        if (program == null || !program.isCompiled()) {
            ++s.programNoop; ++s.handled; s.publishMaybe(); return true;
        }
        final Core core = Core.getInstance();
        final boolean modelViewEmpty = core.modelViewMatrixStack.isEmpty();
        final Matrix4f mv, prj;
        if (modelViewEmpty) { mv = IDENTITY; prj = IDENTITY; }
        else { mv = core.modelViewMatrixStack.peek(); prj = core.projectionMatrixStack.peek(); }
        if (mv.equals(program.modelView) && prj.equals(program.projection)) {
            ++s.unchangedSkips; ++s.handled; s.publishMaybe(); return true;
        }
        final ShaderProgram.Uniform uniform = program.getUniform("ModelViewProjection", GL_FLOAT_MAT4);
        ++s.uniformLookups;
        if (uniform == null) { ++s.uniformNull; ++s.handled; s.publishMaybe(); return true; }
        final Matrix4f mvp = s.mvp;
        program.modelView.set(mv); program.projection.set(prj); mvp.set(prj).mul(mv);
        program.setTransformMatrix(uniform.loc, mvp);
        ++s.changedUploads; ++s.handled; s.publishMaybe(); return true;
    }

    private static boolean trySetLean(ShaderProgram program) {
        if (!Optimizer.rthreadMvpFastEnabled()) return false;
        if (program == null || !program.isCompiled()) return true;
        final Core core = Core.getInstance();
        final boolean modelViewEmpty = core.modelViewMatrixStack.isEmpty();
        final Matrix4f mv, prj;
        if (modelViewEmpty) { mv = IDENTITY; prj = IDENTITY; }
        else { mv = core.modelViewMatrixStack.peek(); prj = core.projectionMatrixStack.peek(); }
        // 99%+ device path: no ThreadLocal lookup, no telemetry writes, no uniform lookup.
        if (mv.equals(program.modelView) && prj.equals(program.projection)) return true;
        final ShaderProgram.Uniform uniform = program.getUniform("ModelViewProjection", GL_FLOAT_MAT4);
        if (uniform == null) return true;
        // Scratch is needed only on the changed path.
        final Matrix4f mvp = STATS.get().mvp;
        program.modelView.set(mv); program.projection.set(prj); mvp.set(prj).mul(mv);
        program.setTransformMatrix(uniform.loc, mvp);
        return true;
    }

    private static final class Stats {
        final Matrix4f mvp = new Matrix4f();
        long calls, handled, unchangedSkips, changedUploads, uniformLookups, uniformNull, programNoop, toggleFallback;
        void publishMaybe() {
            if ((calls & PUBLISH_MASK) != 0L) return;
            Optimizer.publishMvpFast(calls, handled, unchangedSkips, changedUploads, uniformLookups,
                    uniformNull, programNoop, toggleFallback);
        }
    }
}
