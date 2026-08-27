package zombie.characters;

public final class IsoPlayer {
    public static final IsoPlayer[] players = new IsoPlayer[4];
    private final float x;
    private final float y;

    public IsoPlayer(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }
}
