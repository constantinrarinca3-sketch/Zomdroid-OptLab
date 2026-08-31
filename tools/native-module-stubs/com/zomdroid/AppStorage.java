package com.zomdroid;

public final class AppStorage {
    private static final AppStorage INSTANCE = new AppStorage();
    private static String libraryPath = "";
    private static String homePath = "";

    public static AppStorage requireSingleton() { return INSTANCE; }
    public static void setLibraryPath(String value) { libraryPath = value; }
    public static void setHomePath(String value) { homePath = value; }
    public String getLibraryPath() { return libraryPath; }
    public String getHomePath() { return homePath; }
}
