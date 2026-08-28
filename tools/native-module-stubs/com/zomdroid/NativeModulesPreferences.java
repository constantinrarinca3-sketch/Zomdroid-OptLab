package com.zomdroid;

public final class NativeModulesPreferences {
    private final boolean lighting;
    private final boolean clipper;
    private final boolean pathfinding;
    private final boolean popMan;

    public NativeModulesPreferences(boolean lighting, boolean clipper, boolean pathfinding,
                                    boolean popMan) {
        this.lighting = lighting;
        this.clipper = clipper;
        this.pathfinding = pathfinding;
        this.popMan = popMan;
    }

    public boolean isLighting64Enabled() { return lighting; }
    public boolean isPzClipperEnabled() { return clipper; }
    public boolean isPathfindingEnabled() { return pathfinding; }
    public boolean isPopManEnabled() { return popMan; }
}
