package zombie.core;

import zombie.core.opengl.ShaderProgram;

/** Compile-only subset matching PZ Build 42 DefaultShader. */
public class DefaultShader {
    private ShaderProgram program;

    public ShaderProgram getProgram() {
        return program;
    }

    public void setProgram(ShaderProgram value) {
        program = value;
    }

    public void onCompileSuccess(ShaderProgram value) {
        program = value;
    }

    public void setChunkDepth(float value) {
        getProgram().setValue("chunkDepth", value);
    }
}
