package com.zomdroid;

public final class NativeModulesPreferences {
    private final boolean lighting;
    private final boolean clipper;
    private final boolean pathfinding;

    public NativeModulesPreferences(boolean lighting, boolean clipper, boolean pathfinding) {
        this.lighting = lighting;
        this.clipper = clipper;
        this.pathfinding = pathfinding;
    }

    public boolean isLighting64Enabled() { return lighting; }
    public boolean isPzClipperEnabled() { return clipper; }
    public boolean isPathfindingEnabled() { return pathfinding; }
}
