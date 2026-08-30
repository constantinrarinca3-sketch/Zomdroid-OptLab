package android.content;

public interface SharedPreferences {
    boolean getBoolean(String key, boolean fallback);
    default String getString(String key, String fallback) { return fallback; }
    Editor edit();

    interface Editor {
        Editor putBoolean(String key, boolean value);
        default Editor putString(String key, String value) { return this; }
        boolean commit();
    }
}
