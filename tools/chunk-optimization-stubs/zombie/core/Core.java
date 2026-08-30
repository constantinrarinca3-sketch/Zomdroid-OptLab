package zombie.core;

import zombie.core.opengl.MatrixStack;

public final class Core {
    private static final Core INSTANCE = new Core();
    public final MatrixStack projectionMatrixStack = new MatrixStack();
    public final MatrixStack modelViewMatrixStack = new MatrixStack();
    public static Core getInstance() { return INSTANCE; }
}
