package zombie.core.opengl;

import com.zomdroid.agent.optimization.HotPathOptimizationRuntime;
import org.joml.Matrix4f;
import zombie.core.Core;

/** Same-package MVP helper, required for ShaderProgram.setTransformMatrix package access. */
public final class ZDOptMvpFast {
    private static final int GL_FLOAT_MAT4 = 35676;
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final ThreadLocal<Matrix4f> SCRATCH =
            ThreadLocal.withInitial(Matrix4f::new);

    private ZDOptMvpFast() {}

    /** true means fully handled; false tells Advice to execute untouched PZ code. */
    public static boolean trySet(Object programObject) {
        if (!HotPathOptimizationRuntime.isMvpEnabled()) return false;
        ShaderProgram program = (ShaderProgram) programObject;
        if (program == null || !program.isCompiled()) return true;

        Core core = Core.getInstance();
        boolean empty = core.modelViewMatrixStack.isEmpty();
        Matrix4f modelView = empty ? IDENTITY : core.modelViewMatrixStack.peek();
        Matrix4f projection = empty ? IDENTITY : core.projectionMatrixStack.peek();
        if (modelView.equals(program.modelView) && projection.equals(program.projection)) {
            HotPathOptimizationRuntime.noteMvpSkip();
            return true;
        }

        ShaderProgram.Uniform uniform = program.getUniform("ModelViewProjection", GL_FLOAT_MAT4);
        if (uniform == null) return true;
        Matrix4f mvp = SCRATCH.get();
        program.modelView.set(modelView);
        program.projection.set(projection);
        mvp.set(projection).mul(modelView);
        program.setTransformMatrix(uniform.loc, mvp);
        return true;
    }
}
