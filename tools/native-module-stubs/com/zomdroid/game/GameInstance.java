package com.zomdroid.game;

public final class GameInstance {
    private final String buildVersion;
    private final String homePath;
    private final String gamePath;

    public GameInstance(String buildVersion, String homePath, String gamePath) {
        this.buildVersion = buildVersion;
        this.homePath = homePath;
        this.gamePath = gamePath;
    }

    public String getBuildVersion() { return buildVersion; }
    public String getHomePath() { return homePath; }
    public String getGamePath() { return gamePath; }
    public boolean isBuild4220Plus() { return "42".equals(buildVersion); }
    public String getPresetName() { return "host-test"; }
}
