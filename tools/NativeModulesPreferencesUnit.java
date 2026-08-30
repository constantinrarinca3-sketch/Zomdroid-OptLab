import android.content.Context;
import android.content.SharedPreferences;

import com.zomdroid.NativeModulesPreferences;
import com.zomdroid.OptLabPreferences;

import java.util.HashMap;
import java.util.Map;

/** Compiles and exercises the real SharedPreferences-backed native-module settings class. */
public final class NativeModulesPreferencesUnit {
    public static void main(String[] args) {
        FakeContext context = new FakeContext();
        NativeModulesPreferences first = NativeModulesPreferences.from(context);
        require(!first.isLighting64Enabled(), "Experimental Lighting default OFF");
        require(first.isPzClipperEnabled(), "PZClipper default ON");
        require(!first.isPathfindingEnabled(), "Pathfinding default OFF");
        require(!first.isPopManEnabled(), "PopMan default OFF");

        first.setLighting64Enabled(true);
        first.setPzClipperEnabled(false);
        first.setPathfindingEnabled(true);
        first.setPopManEnabled(true);
        NativeModulesPreferences restored = NativeModulesPreferences.from(context);
        require(restored.isLighting64Enabled(), "Lighting persisted ON by explicit opt-in");
        require(!restored.isPzClipperEnabled(), "PZClipper persisted OFF");
        require(restored.isPathfindingEnabled(), "Pathfinding persisted ON");
        require(restored.isPopManEnabled(), "PopMan persisted ON");
        require(restored.machineReadable().contains("lighting64=1 pzClipper=0 pathfinding=1 popMan=1"),
                "machine-readable state");

        OptLabPreferences optLab = OptLabPreferences.from(context);
        optLab.setSafeModeEnabled(true);
        NativeModulesPreferences safe = NativeModulesPreferences.from(context);
        require(!safe.isLighting64Enabled(), "Safe Mode bypasses Lighting");
        require(!safe.isPzClipperEnabled(), "Safe Mode bypasses PZClipper");
        require(!safe.isPathfindingEnabled(), "Safe Mode bypasses Pathfinding");
        require(!safe.isPopManEnabled(), "Safe Mode bypasses PopMan");
        require(safe.machineReadable().contains("safeMode=1"), "Safe Mode diagnostic");
        optLab.setSafeModeEnabled(false);
        NativeModulesPreferences resumed = NativeModulesPreferences.from(context);
        require(resumed.isLighting64Enabled(), "stored Lighting restored");
        require(!resumed.isPzClipperEnabled(), "stored PZClipper restored");
        require(resumed.isPathfindingEnabled(), "stored Pathfinding restored");
        require(resumed.isPopManEnabled(), "stored PopMan restored");
        require(NativeModulesPreferences.SCHEMA == 4, "native schema 4");
        System.out.println("NATIVE_MODULES_PREFERENCES_UNIT PASS schema=4 safe_mode=1"
                + " lighting_default_off=1");
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
