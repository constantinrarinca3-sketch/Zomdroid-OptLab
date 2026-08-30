package zombie.core.opengl;

import org.lwjgl.opengl.GL20;
import org.joml.Matrix4f;

/** Compile/test subset matching the B42 uniform API used by CP6.2. */
public class ShaderProgram {
    public Uniform uniform;
    public int lookups;
    public int uploads;
    public boolean compiled = true;
    public final Matrix4f modelView = new Matrix4f();
    public final Matrix4f projection = new Matrix4f();

    public Uniform getUniform(String name, int type) {
        lookups++;
        return uniform;
    }

    public void setValue(String name, float value) {
        Uniform current = getUniform(name, 5126);
        if (current != null) GL20.glUniform1f(current.loc, value);
    }

    public boolean isCompiled() {
        return compiled;
    }

    void setTransformMatrix(int location, Matrix4f value) {
        uploads++;
    }

    public static class Uniform {
        public int loc;

        public Uniform(int location) {
            loc = location;
        }
    }
}
