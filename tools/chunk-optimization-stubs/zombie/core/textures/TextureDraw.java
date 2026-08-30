package zombie.core.textures;

public final class TextureDraw {
    public enum Type { glDraw, DrawModel, other }

    public Type type = Type.glDraw;
    public boolean flipped;
    public int col0;
    public int col1;
    public int col2;
    public int col3;
    public float x0;
    public float x1;
    public float x2;
    public float x3;
    public float y0;
    public float y1;
    public float y2;
    public float y3;
    public float u0;
    public float u1;
    public float u2;
    public float u3;
    public float v0;
    public float v1;
    public float v2;
    public float v3;
    public float z;
    public float chunkDepth;
    public Texture tex;
    public Texture tex1;
    public Texture tex2;
    public byte useAttribArray;
    public float tex1U0;
    public float tex1U1;
    public float tex1U2;
    public float tex1U3;
    public float tex1V0;
    public float tex1V1;
    public float tex1V2;
    public float tex1V3;
    public float tex2U0;
    public float tex2U1;
    public float tex2U2;
    public float tex2U3;
    public float tex2V0;
    public float tex2V1;
    public float tex2V2;
    public float tex2V3;
    public boolean singleCol;

    public int getColor(int index) {
        if (singleCol) return col0;
        switch (index) {
            case 1: return col1;
            case 2: return col2;
            case 3: return col3;
            default: return col0;
        }
    }
}
