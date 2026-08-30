package com.zomdroid;

public final class OptLabPreferences {
    public static final String EXPECTED_PZ_JAR_SHA256 = "known-jar";
    public static boolean isKnownPzJarSha256(String value) {
        return EXPECTED_PZ_JAR_SHA256.equals(value) || "known-jar-42.20.3".equals(value);
    }
    public enum StdioMode { LEGACY, BUFFERED }
    public enum Box64Policy { LEGACY_3_0, SAFE }
    public enum SurfaceMode { LEGACY, GEN_ACK }
    public enum DisplayFpsHint { OFF, NATIVE_REFRESH }
    public enum InputQueueMode { LEGACY, MUTEX_SAFE }

    private final boolean onlyBuild42;
    private final boolean chunk;
    public OptLabPreferences(boolean onlyBuild42) { this(onlyBuild42, false); }
    public OptLabPreferences(boolean onlyBuild42, boolean chunk) {
        this.onlyBuild42 = onlyBuild42;
        this.chunk = chunk;
    }
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
    public boolean isChunkForage() { return chunk; }
    public boolean isChunkNeighbourWorker() { return chunk; }
    public boolean isChunkNeighbourMain() { return chunk; }
    public boolean isChunkGridLoad() { return chunk; }
    public boolean isChunkVehicles() { return chunk; }
    public boolean isChunkRandomizedBuildings() { return chunk; }
    public boolean isChunkLuaMapObjects() { return chunk; }
    public boolean isChunkWorldgenBiome() { return chunk; }
    public boolean isChunkCp2cDirtyClear() { return chunk; }
    public boolean isRenderChunkDepthUpload() { return chunk; }
    public boolean isRenderChunkDepthLookup() { return chunk; }
    public boolean isRenderRingEmptyClear() { return chunk; }
    public boolean isOnlyBuild42() { return onlyBuild42; }
    public boolean isFeatureEnabled(OptLabFeatureRegistry.Feature feature) {
        return chunk && feature.category == OptLabFeatureRegistry.Category.BUILD42
                && feature.isVisible();
    }
}
