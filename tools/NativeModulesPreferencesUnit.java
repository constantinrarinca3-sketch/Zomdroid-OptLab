import android.content.Context;
import android.content.SharedPreferences;

import com.zomdroid.NativeModulesPreferences;

import java.util.HashMap;
import java.util.Map;

/** Compiles and exercises the real SharedPreferences-backed native-module settings class. */
public final class NativeModulesPreferencesUnit {
    public static void main(String[] args) {
        FakeContext context = new FakeContext();
        NativeModulesPreferences first = NativeModulesPreferences.from(context);
        require(first.isLighting64Enabled(), "Lighting default ON");
        require(first.isPzClipperEnabled(), "PZClipper default ON");
        require(!first.isPathfindingEnabled(), "Pathfinding default OFF");
        require(!first.isPopManEnabled(), "PopMan unavailable");

        first.setLighting64Enabled(false);
        first.setPzClipperEnabled(false);
        first.setPathfindingEnabled(true);
        NativeModulesPreferences restored = NativeModulesPreferences.from(context);
        require(!restored.isLighting64Enabled(), "Lighting persisted OFF");
        require(!restored.isPzClipperEnabled(), "PZClipper persisted OFF");
        require(restored.isPathfindingEnabled(), "Pathfinding persisted ON");
        require(restored.machineReadable().contains("lighting64=0 pzClipper=0 pathfinding=1 popMan=0"),
                "machine-readable state");
        System.out.println("NATIVE_MODULES_PREFERENCES_UNIT PASS");
    }

    private static final class FakeContext extends Context {
        private final FakePreferences preferences = new FakePreferences();
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    private static final class FakePreferences implements SharedPreferences {
        private final Map<String, Boolean> values = new HashMap<>();
        @Override public boolean getBoolean(String key, boolean fallback) {
            Boolean value = values.get(key);
            return value == null ? fallback : value;
        }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Boolean> pending = new HashMap<>();
                @Override public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }
                @Override public boolean commit() {
                    values.putAll(pending);
                    return true;
                }
            };
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
