package org.joml;

/** Compile/test subset used by the CP6.3 MVP parity test. */
public class Matrix4f implements Matrix4fc {
    private int token;

    public Matrix4f() { identity(); }
    public Matrix4f identity() { token = 0; return this; }
    public Matrix4f set(Matrix4fc value) {
        token = value instanceof Matrix4f ? ((Matrix4f) value).token : 0;
        return this;
    }
    public Matrix4f mul(Matrix4fc value) {
        token = token * 31 + (value instanceof Matrix4f ? ((Matrix4f) value).token : 0);
        return this;
    }
    public Matrix4f setToken(int value) { token = value; return this; }
    public int token() { return token; }
    @Override public boolean equals(Object value) {
        return value instanceof Matrix4f && ((Matrix4f) value).token == token;
    }
}
