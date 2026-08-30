package com.zomdroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host contract for the versioned, named Build 42 preset catalog. */
public final class OptLabCustomPresetStoreUnit {
    public static void main(String[] args) {
        lifecycleAndIsolation();
        dependencyRepairAndForwardSafety();
        corruptCatalogFailsClosed();
        System.out.println("OPTLAB_CUSTOM_PRESET_STORE_UNIT PASS schema=1 lifecycle=6"
                + " stable_ids=1 dependencies=repaired scope=build42_only corrupt=fail_closed");
    }

    private static void lifecycleAndIsolation() {
        FakeContext context = new FakeContext();
        OptLabPreferences prefs = OptLabPreferences.from(context);
        prefs.setChunkCp2cDirtyClear(true);
        prefs.setBuild42LabProfile(OptLabPreferences.LabProfile.RECOMMENDED);
        prefs.setFeatureEnabled(OptLabFeatureRegistry.Feature.RTHREAD_MVP, false);
        prefs.setFeatureEnabled(OptLabFeatureRegistry.Feature.FBO_INNER_LOOP, true);

        OptLabCustomPresetStore.Preset first =
                prefs.createCustomBuild42Preset("  Telefon   Ω  ");
        require("Telefon Ω".equals(first.getName()), "name normalization");
        require(first.getFeatureStates().size() == visibleBuild42Count(),
                "snapshot is not complete");
        require(first.getFeatureStates().containsKey(
                        OptLabFeatureRegistry.Feature.RTHREAD_MVP.id),
                "snapshot does not use stable feature IDs");
        require(prefs.getBuild42LabProfile() == OptLabPreferences.LabProfile.CUSTOM,
                "created preset did not become Custom");
        require(first.getId().equals(prefs.getActiveCustomBuild42Preset().getId()),
                "created preset not active");
        require(!prefs.isActiveCustomBuild42PresetModified(),
                "fresh snapshot marked modified");
        require(prefs.isChunkCp2cDirtyClear(),
                "Build 42 snapshot rewrote independent Experimental CP2C");

        expectIllegalArgument(() -> prefs.createCustomBuild42Preset("telefon ω"),
                "case-insensitive duplicate name accepted");

        prefs.setFeatureEnabled(OptLabFeatureRegistry.Feature.RTHREAD_MVP, true);
        require(prefs.isActiveCustomBuild42PresetModified(),
                "manual edit did not mark active preset modified");
        prefs.applyCustomBuild42Preset(first.getId());
        require(!prefs.isFeatureEnabled(OptLabFeatureRegistry.Feature.RTHREAD_MVP),
                "apply did not restore saved internal toggle");
        require(prefs.isFeatureEnabled(OptLabFeatureRegistry.Feature.FBO_INNER_LOOP),
                "apply did not restore saved experimental B42 toggle");
        require(!prefs.isActiveCustomBuild42PresetModified(),
                "applied snapshot marked modified");

        OptLabCustomPresetStore.Preset renamed =
                prefs.renameCustomBuild42Preset(first.getId(), "Telefon stabil");
        require(first.getId().equals(renamed.getId()), "rename changed identity");
        require("Telefon stabil".equals(
                        prefs.getActiveCustomBuild42Preset().getName()),
                "active rename not visible");

        OptLabCustomPresetStore.Preset duplicate =
                prefs.duplicateCustomBuild42Preset(first.getId(), "Exterior");
        require(!duplicate.getId().equals(first.getId()), "duplicate reused identity");
        require(prefs.getCustomBuild42Presets().size() == 2,
                "duplicate not persisted");
        require(first.getId().equals(prefs.getActiveCustomBuild42Preset().getId()),
                "duplicate unexpectedly changed active preset");

        prefs.applyCustomBuild42Preset(duplicate.getId());
        prefs.setFeatureEnabled(OptLabFeatureRegistry.Feature.RTHREAD_TEXTURE_BIND, false);
        require(prefs.isActiveCustomBuild42PresetModified(),
                "edit before update not detected");
        OptLabCustomPresetStore.Preset updated =
                prefs.updateCustomBuild42Preset(duplicate.getId());
        require(updated.getId().equals(duplicate.getId()), "update changed identity");
        require(!prefs.isActiveCustomBuild42PresetModified(),
                "updated preset not exact");

        OptLabPreferences restored = OptLabPreferences.from(context);
        require(restored.getCustomBuild42Presets().size() == 2,
                "catalog did not survive a new preferences instance");
        require("Exterior".equals(restored.getActiveCustomBuild42Preset().getName()),
                "active identity did not persist");
        require(restored.machineReadable().contains("customPresetSchema=1")
                        && restored.machineReadable().contains("customPresetCount=2"),
                "preset diagnostics missing");

        restored.setBuild42LabProfile(OptLabPreferences.LabProfile.SAFE);
        require(restored.getActiveCustomBuild42Preset() == null,
                "built-in profile retained a false custom identity");
        require(restored.getCustomBuild42Presets().size() == 2,
                "built-in profile deleted saved presets");
        restored.applyCustomBuild42Preset(duplicate.getId());
        restored.setBuild42ProductionAllOff();
        require(restored.getActiveCustomBuild42Preset() != null
                        && restored.isActiveCustomBuild42PresetModified(),
                "ALL OFF should preserve the saved base and mark it modified");

        restored.deleteCustomBuild42Preset(first.getId());
        require(restored.getCustomBuild42Presets().size() == 1,
                "delete non-active preset failed");
        restored.deleteCustomBuild42Preset(duplicate.getId());
        require(restored.getCustomBuild42Presets().isEmpty()
                        && restored.getActiveCustomBuild42Preset() == null,
                "delete active preset failed");
        require(restored.isChunkCp2cDirtyClear(),
                "preset lifecycle rewrote independent Experimental CP2C");
    }

    private static void dependencyRepairAndForwardSafety() {
        FakeContext context = new FakeContext();
        LinkedHashMap<String, Boolean> saved = new LinkedHashMap<>();
        saved.put(OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_UPLOAD.id, false);
        saved.put(OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_LOOKUP.id, true);
        // Deliberately omit all other IDs to emulate a preset from an older registry.
        OptLabCustomPresetStore.Preset injected = new OptLabCustomPresetStore.Preset(
                "dependency-repair", "Dependency repair", 1L, 1L, saved);
        context.preferences.edit().putString(OptLabCustomPresetStore.K_CATALOG,
                OptLabCustomPresetStore.encode(Collections.singletonList(injected))).commit();

        OptLabPreferences prefs = OptLabPreferences.from(context);
        prefs.setBuild42LabProfile(OptLabPreferences.LabProfile.AGGRESSIVE);
        prefs.applyCustomBuild42Preset(injected.getId());
        require(prefs.isFeatureEnabled(
                        OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_UPLOAD),
                "apply did not repair required dependency");
        require(prefs.isFeatureEnabled(
                        OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_LOOKUP),
                "requested dependent feature not applied");
        require(!prefs.isFeatureEnabled(OptLabFeatureRegistry.Feature.FBO_INNER_LOOP),
                "missing future feature did not fail safely to OFF");
        require(!prefs.isActiveCustomBuild42PresetModified(),
                "normalized dependency repair should be the active exact state");
    }

    private static void corruptCatalogFailsClosed() {
        FakeContext context = new FakeContext();
        String unknown = "ZOMDROID_BUILD42_PRESETS\t99\nfuture-data-must-survive";
        context.preferences.edit().putString(OptLabCustomPresetStore.K_CATALOG, unknown)
                .commit();
        OptLabPreferences prefs = OptLabPreferences.from(context);
        prefs.setFeatureEnabled(OptLabFeatureRegistry.Feature.RTHREAD_MVP, true);
        require(prefs.hasCustomBuild42PresetStorageProblem(),
                "unknown schema not reported");
        require(prefs.getCustomBuild42Presets().isEmpty(),
                "unknown schema exposed partial records");
        expectIllegalState(() -> prefs.createCustomBuild42Preset("Do not overwrite"),
                "mutation overwrote unknown schema");
        require(unknown.equals(context.preferences.getString(
                        OptLabCustomPresetStore.K_CATALOG, "")),
                "unknown catalog data was destroyed");
        require(prefs.isFeatureEnabled(OptLabFeatureRegistry.Feature.RTHREAD_MVP),
                "catalog failure changed live Build 42 state");
    }

    private static int visibleBuild42Count() {
        int count = 0;
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.Feature.values()) {
            if (feature.category == OptLabFeatureRegistry.Category.BUILD42
                    && feature.preferenceKey != null && feature.isVisible()) count++;
        }
        return count;
    }

    private static void expectIllegalArgument(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectIllegalState(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeContext extends Context {
        private final FakePreferences preferences = new FakePreferences();
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    private static final class FakePreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }
        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                @Override public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }
                @Override public Editor putString(String key, String value) {
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
}
