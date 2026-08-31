package zombie.core.textures;

public class Texture {
    public static int lastTextureID;
    public boolean bindAlways;
    private int id;
    private boolean destroyed;
    private boolean valid = true;
    private boolean ready = true;

    public Texture() {}
    public Texture(int value) { id = value; }
    public int getID() { return id; }
    public boolean isDestroyed() { return destroyed; }
    public boolean isValid() { return valid; }
    public boolean isReady() { return ready; }
    public void bind(int target) { lastTextureID = id; }
    public void setID(int value) { id = value; }
    public void setDestroyed(boolean value) { destroyed = value; }
    public void setValid(boolean value) { valid = value; }
    public void setReady(boolean value) { ready = value; }
}
