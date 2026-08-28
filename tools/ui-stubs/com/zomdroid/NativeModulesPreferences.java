package com.zomdroid;

import android.content.Context;

public final class NativeModulesPreferences {
    public static NativeModulesPreferences from(Context context) {
        return new NativeModulesPreferences();
    }
    public boolean isLighting64Enabled() { return true; }
    public boolean isPzClipperEnabled() { return true; }
    public boolean isPathfindingEnabled() { return false; }
    public boolean isPopManEnabled() { return false; }
    public void setLighting64Enabled(boolean value) {}
    public void setPzClipperEnabled(boolean value) {}
    public void setPathfindingEnabled(boolean value) {}
    public void setPopManEnabled(boolean value) {}
}
