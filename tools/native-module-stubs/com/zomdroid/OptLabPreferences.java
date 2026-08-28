package com.zomdroid;

public final class OptLabPreferences {
    public static final String EXPECTED_PZ_JAR_SHA256 = "known-jar";
    public enum StdioMode { LEGACY, BUFFERED }
    public enum Box64Policy { LEGACY_3_0, SAFE }
    public enum SurfaceMode { LEGACY, GEN_ACK }
    public enum DisplayFpsHint { OFF, NATIVE_REFRESH }
    public enum InputQueueMode { LEGACY, MUTEX_SAFE }

    private final boolean onlyBuild42;
    public OptLabPreferences(boolean onlyBuild42) { this.onlyBuild42 = onlyBuild42; }
    public boolean isQuietRuntime() { return false; }
    public StdioMode getStdioMode() { return StdioMode.LEGACY; }
    public boolean isSqliteAndroidNative() { return false; }
    public Box64Policy getBox64Policy() { return Box64Policy.LEGACY_3_0; }
    public SurfaceMode getSurfaceMode() { return SurfaceMode.LEGACY; }
    public DisplayFpsHint getDisplayFpsHint() { return DisplayFpsHint.OFF; }
    public InputQueueMode getInputQueueMode() { return InputQueueMode.LEGACY; }
    public boolean isAnalogFilter() { return false; }
    public boolean isInputCoalesce() { return false; }
    public boolean isMobileGlFileLogEnabled() { return false; }
    public boolean isMainloopPacing() { return false; }
    public boolean isStreamWake() { return false; }
    public boolean isStreamQueueFast() { return false; }
    public boolean isStreamVelocityEta() { return false; }
    public boolean isFboDirtyDedup() { return false; }
    public boolean isFboFrameBudget() { return false; }
    public boolean isStreamFboCoordinator() { return false; }
    public boolean isOnlyBuild42() { return onlyBuild42; }
}
