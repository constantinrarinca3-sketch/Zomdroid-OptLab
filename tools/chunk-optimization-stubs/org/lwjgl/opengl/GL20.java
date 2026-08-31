package org.lwjgl.opengl;

/** Compile/test stub for the single GL entrypoint used by CP6.2. */
public final class GL20 {
    public static int uniform1fCalls;
    public static boolean throwNext;

    private GL20() {}

    public static void glUniform1f(int location, float value) {
        uniform1fCalls++;
        if (throwNext) {
            throwNext = false;
            throw new RuntimeException("test GL failure");
        }
    }
}
