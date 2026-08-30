package com.zomdroid;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Versioned, fail-closed catalog for named Build 42 snapshots.
 *
 * Presets deliberately contain only registry-owned, visible BUILD42 features.
 * General, Native and the independent EXPERIMENTAL category are never captured
 * or rewritten. Stable feature IDs are serialized instead of enum ordinals so
 * registry reordering cannot silently change a saved configuration.
 */
public final class OptLabCustomPresetStore {
    public static final int SCHEMA = 1;
    public static final int MAX_PRESETS = 32;
    public static final int MAX_NAME_CODE_POINTS = 48;

    static final String K_CATALOG = "build42_custom_presets_v1";
    static final String K_ACTIVE_ID = "build42_custom_preset_active_v1";
    private static final String HEADER_MAGIC = "ZOMDROID_BUILD42_PRESETS";

    private final SharedPreferences prefs;

    OptLabCustomPresetStore(@NonNull SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Immutable snapshot exposed to the UI. */
    public static final class Preset {
        private final String id;
        private final String name;
        private final long createdAtUtcMillis;
        private final long updatedAtUtcMillis;
        private final Map<String, Boolean> featureStates;

        Preset(String id, String name, long createdAtUtcMillis, long updatedAtUtcMillis,
               Map<String, Boolean> featureStates) {
            this.id = id;
            this.name = name;
            this.createdAtUtcMillis = createdAtUtcMillis;
            this.updatedAtUtcMillis = updatedAtUtcMillis;
            this.featureStates = Collections.unmodifiableMap(
                    new LinkedHashMap<>(featureStates));
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public long getCreatedAtUtcMillis() { return createdAtUtcMillis; }
        public long getUpdatedAtUtcMillis() { return updatedAtUtcMillis; }
        public Map<String, Boolean> getFeatureStates() { return featureStates; }
        public boolean isEnabled(String featureId) {
            return Boolean.TRUE.equals(featureStates.get(featureId));
        }
        @Override public String toString() { return name; }
    }

    @NonNull
    public List<Preset> list() {
        LoadResult result = load();
        return result.problem == null ? result.presets : Collections.emptyList();
    }

    public Preset active() {
        final String activeId;
        try {
            activeId = prefs.getString(K_ACTIVE_ID, "");
        } catch (RuntimeException error) {
            return null;
        }
        if (activeId == null || activeId.isEmpty()) return null;
        for (Preset preset : list()) {
            if (preset.id.equals(activeId)) return preset;
        }
        return null;
    }

    public boolean isActiveModified() {
        Preset active = active();
        return active != null
                && !normalizedState(active.featureStates).equals(snapshotCurrentState());
    }

    public boolean hasStorageProblem() { return load().problem != null; }

    public String storageProblem() { return load().problem; }

    @NonNull
    public Preset create(@NonNull String requestedName) {
        LoadResult result = writableCatalog();
        if (result.presets.size() >= MAX_PRESETS) {
            throw new IllegalStateException("Maximum " + MAX_PRESETS + " presets");
        }
        String name = validatedUniqueName(requestedName, result.presets, null);
        long now = System.currentTimeMillis();
        Preset created = new Preset(UUID.randomUUID().toString(), name, now, now,
                snapshotCurrentState());
        ArrayList<Preset> next = new ArrayList<>(result.presets);
        next.add(created);
        commit(prefs.edit()
                .putString(K_CATALOG, encode(next))
                .putString(K_ACTIVE_ID, created.id)
                .putString(OptLabPreferences.K_BUILD42_PROFILE,
                        OptLabPreferences.LabProfile.CUSTOM.name()));
        return created;
    }

    @NonNull
    public Preset apply(@NonNull String id) {
        LoadResult result = writableCatalog();
        Preset preset = requirePreset(result.presets, id);
        EnumMap<OptLabFeatureRegistry.Feature, Boolean> desired =
                normalizedFeatureState(preset.featureStates);
        SharedPreferences.Editor editor = prefs.edit()
                .putString(OptLabPreferences.K_BUILD42_PROFILE,
                        OptLabPreferences.LabProfile.CUSTOM.name())
                .putString(K_ACTIVE_ID, preset.id);
        for (Map.Entry<OptLabFeatureRegistry.Feature, Boolean> entry
                : desired.entrySet()) {
            editor.putBoolean(entry.getKey().preferenceKey, entry.getValue());
        }
        commit(editor);
        return preset;
    }

    @NonNull
    public Preset update(@NonNull String id) {
        LoadResult result = writableCatalog();
        int index = requirePresetIndex(result.presets, id);
        Preset previous = result.presets.get(index);
        long now = Math.max(System.currentTimeMillis(), previous.updatedAtUtcMillis);
        Preset updated = new Preset(previous.id, previous.name,
                previous.createdAtUtcMillis, now,
                snapshotCurrentState());
        ArrayList<Preset> next = new ArrayList<>(result.presets);
        next.set(index, updated);
        commit(prefs.edit()
                .putString(K_CATALOG, encode(next))
                .putString(K_ACTIVE_ID, updated.id)
                .putString(OptLabPreferences.K_BUILD42_PROFILE,
                        OptLabPreferences.LabProfile.CUSTOM.name()));
        return updated;
    }

    @NonNull
    public Preset rename(@NonNull String id, @NonNull String requestedName) {
        LoadResult result = writableCatalog();
        int index = requirePresetIndex(result.presets, id);
        Preset previous = result.presets.get(index);
        String name = validatedUniqueName(requestedName, result.presets, id);
        long now = Math.max(System.currentTimeMillis(), previous.updatedAtUtcMillis);
        Preset renamed = new Preset(previous.id, name, previous.createdAtUtcMillis,
                now, previous.featureStates);
        ArrayList<Preset> next = new ArrayList<>(result.presets);
        next.set(index, renamed);
        commit(prefs.edit().putString(K_CATALOG, encode(next)));
        return renamed;
    }

    @NonNull
    public Preset duplicate(@NonNull String id, @NonNull String requestedName) {
        LoadResult result = writableCatalog();
        if (result.presets.size() >= MAX_PRESETS) {
            throw new IllegalStateException("Maximum " + MAX_PRESETS + " presets");
        }
        Preset source = requirePreset(result.presets, id);
        String name = validatedUniqueName(requestedName, result.presets, null);
        long now = System.currentTimeMillis();
        Preset duplicate = new Preset(UUID.randomUUID().toString(), name, now, now,
                source.featureStates);
        ArrayList<Preset> next = new ArrayList<>(result.presets);
        next.add(duplicate);
        commit(prefs.edit().putString(K_CATALOG, encode(next)));
        return duplicate;
    }

    public void delete(@NonNull String id) {
        LoadResult result = writableCatalog();
        int index = requirePresetIndex(result.presets, id);
        ArrayList<Preset> next = new ArrayList<>(result.presets);
        next.remove(index);
        SharedPreferences.Editor editor = prefs.edit().putString(K_CATALOG, encode(next));
        String activeId;
        try {
            activeId = prefs.getString(K_ACTIVE_ID, "");
        } catch (RuntimeException ignored) {
            activeId = "";
            editor.remove(K_ACTIVE_ID);
        }
        if (id.equals(activeId)) editor.putString(K_ACTIVE_ID, "");
        commit(editor);
    }

    /** Explicit recovery that removes only the custom catalog, never launch feature values. */
    public void resetCatalog() {
        commit(prefs.edit().remove(K_CATALOG).remove(K_ACTIVE_ID));
    }

    void clearActive(SharedPreferences.Editor editor) {
        editor.putString(K_ACTIVE_ID, "");
    }

    private LoadResult writableCatalog() {
        LoadResult result = load();
        if (result.problem != null) {
            throw new IllegalStateException("Preset catalog is read-only: " + result.problem);
        }
        return result;
    }

    private LoadResult load() {
        final String raw;
        try {
            raw = prefs.getString(K_CATALOG, "");
        } catch (RuntimeException error) {
            return new LoadResult(Collections.emptyList(),
                    "catalog storage type mismatch: " + error.getClass().getSimpleName());
        }
        if (raw == null || raw.isEmpty()) {
            return new LoadResult(Collections.emptyList(), null);
        }
        String[] lines = raw.split("\\n", -1);
        String[] header = lines[0].split("\\t", -1);
        if (header.length != 4 || !HEADER_MAGIC.equals(header[0])
                || !Integer.toString(SCHEMA).equals(header[1])) {
            return new LoadResult(Collections.emptyList(), "unknown or corrupt schema");
        }
        int expectedRecords;
        try {
            expectedRecords = Integer.parseInt(header[2]);
        } catch (NumberFormatException error) {
            return new LoadResult(Collections.emptyList(), "invalid record count");
        }
        String body = lines.length == 1 ? "" : raw.substring(raw.indexOf('\n') + 1);
        if (expectedRecords < 0 || expectedRecords != lines.length - 1
                || !checksum(body).equals(header[3])) {
            return new LoadResult(Collections.emptyList(), "catalog checksum mismatch");
        }
        ArrayList<Preset> presets = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                return new LoadResult(Collections.emptyList(), "empty preset record");
            }
            try {
                Preset preset = decodePreset(lines[i]);
                if (findPreset(presets, preset.id) != null
                        || hasName(presets, preset.name, null)) {
                    return new LoadResult(Collections.emptyList(),
                            "duplicate preset identity at record " + i);
                }
                presets.add(preset);
                if (presets.size() > MAX_PRESETS) {
                    return new LoadResult(Collections.emptyList(), "preset limit exceeded");
                }
            } catch (RuntimeException error) {
                return new LoadResult(Collections.emptyList(),
                        "corrupt record " + i + ": " + error.getMessage());
            }
        }
        return new LoadResult(Collections.unmodifiableList(presets), null);
    }

    static String encode(List<Preset> presets) {
        StringBuilder body = new StringBuilder();
        for (Preset preset : presets) {
            if (body.length() > 0) body.append('\n');
            body.append(preset.id).append('\t')
                    .append(preset.createdAtUtcMillis).append('\t')
                    .append(preset.updatedAtUtcMillis).append('\t')
                    .append(urlEncode(preset.name)).append('\t');
            boolean first = true;
            for (Map.Entry<String, Boolean> state : preset.featureStates.entrySet()) {
                if (!isSafeToken(state.getKey())) {
                    throw new IllegalArgumentException("invalid feature ID");
                }
                if (!first) body.append(',');
                first = false;
                body.append(state.getKey()).append('=')
                        .append(Boolean.TRUE.equals(state.getValue()) ? '1' : '0');
            }
        }
        String header = HEADER_MAGIC + '\t' + SCHEMA + '\t' + presets.size()
                + '\t' + checksum(body.toString());
        return body.length() == 0 ? header : header + '\n' + body;
    }

    private static Preset decodePreset(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 5 || !isSafeToken(fields[0])) {
            throw new IllegalArgumentException("invalid fields");
        }
        long created = Long.parseLong(fields[1]);
        long updated = Long.parseLong(fields[2]);
        if (created < 0 || updated < created) {
            throw new IllegalArgumentException("invalid timestamps");
        }
        String name = normalizedName(urlDecode(fields[3]));
        LinkedHashMap<String, Boolean> states = new LinkedHashMap<>();
        if (!fields[4].isEmpty()) {
            for (String token : fields[4].split(",", -1)) {
                String[] pair = token.split("=", -1);
                if (pair.length != 2 || !isSafeToken(pair[0])
                        || !("0".equals(pair[1]) || "1".equals(pair[1]))
                        || states.put(pair[0], "1".equals(pair[1])) != null) {
                    throw new IllegalArgumentException("invalid feature state");
                }
            }
        }
        return new Preset(fields[0], name, created, updated, states);
    }

    private Map<String, Boolean> snapshotCurrentState() {
        EnumMap<OptLabFeatureRegistry.Feature, Boolean> state =
                new EnumMap<>(OptLabFeatureRegistry.Feature.class);
        for (OptLabFeatureRegistry.Feature feature : configurableFeatures()) {
            state.put(feature, prefs.getBoolean(feature.preferenceKey,
                    feature.defaultEnabled));
        }
        closeDependencies(state);
        LinkedHashMap<String, Boolean> snapshot = new LinkedHashMap<>();
        for (OptLabFeatureRegistry.Feature feature : configurableFeatures()) {
            snapshot.put(feature.id, Boolean.TRUE.equals(state.get(feature)));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private static Map<String, Boolean> normalizedState(Map<String, Boolean> saved) {
        EnumMap<OptLabFeatureRegistry.Feature, Boolean> normalized =
                normalizedFeatureState(saved);
        LinkedHashMap<String, Boolean> output = new LinkedHashMap<>();
        for (OptLabFeatureRegistry.Feature feature : configurableFeatures()) {
            output.put(feature.id, Boolean.TRUE.equals(normalized.get(feature)));
        }
        return output;
    }

    private static EnumMap<OptLabFeatureRegistry.Feature, Boolean> normalizedFeatureState(
            Map<String, Boolean> saved) {
        EnumMap<OptLabFeatureRegistry.Feature, Boolean> state =
                new EnumMap<>(OptLabFeatureRegistry.Feature.class);
        for (OptLabFeatureRegistry.Feature feature : configurableFeatures()) {
            // A feature introduced after the preset was saved is safely OFF.
            state.put(feature, Boolean.TRUE.equals(saved.get(feature.id)));
        }
        closeDependencies(state);
        return state;
    }

    private static void closeDependencies(
            EnumMap<OptLabFeatureRegistry.Feature, Boolean> state) {
        EnumSet<OptLabFeatureRegistry.Feature> enabled =
                EnumSet.noneOf(OptLabFeatureRegistry.Feature.class);
        for (Map.Entry<OptLabFeatureRegistry.Feature, Boolean> entry : state.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                enableDependencies(entry.getKey(), state, enabled);
            }
        }
    }

    private static void enableDependencies(OptLabFeatureRegistry.Feature feature,
                                           EnumMap<OptLabFeatureRegistry.Feature, Boolean> state,
                                           EnumSet<OptLabFeatureRegistry.Feature> seen) {
        if (!seen.add(feature)) return;
        state.put(feature, true);
        for (OptLabFeatureRegistry.Feature dependency : feature.dependencies) {
            if (dependency.category == OptLabFeatureRegistry.Category.BUILD42
                    && dependency.preferenceKey != null && dependency.isVisible()) {
                enableDependencies(dependency, state, seen);
            }
        }
    }

    private static List<OptLabFeatureRegistry.Feature> configurableFeatures() {
        ArrayList<OptLabFeatureRegistry.Feature> features = new ArrayList<>();
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.Feature.values()) {
            if (feature.category == OptLabFeatureRegistry.Category.BUILD42
                    && feature.preferenceKey != null && feature.isVisible()) {
                features.add(feature);
            }
        }
        return features;
    }

    private static String validatedUniqueName(String requestedName, List<Preset> presets,
                                              String ignoredId) {
        String name = normalizedName(requestedName);
        if (hasName(presets, name, ignoredId)) {
            throw new IllegalArgumentException("Preset name already exists");
        }
        return name;
    }

    private static String normalizedName(String requestedName) {
        if (requestedName == null) throw new IllegalArgumentException("Preset name is required");
        String name = requestedName.trim().replaceAll("\\s+", " ");
        int length = name.codePointCount(0, name.length());
        if (length == 0 || length > MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("Preset name must contain 1-"
                    + MAX_NAME_CODE_POINTS + " characters");
        }
        return name;
    }

    private static boolean hasName(List<Preset> presets, String name, String ignoredId) {
        String key = name.toLowerCase(Locale.ROOT);
        for (Preset preset : presets) {
            if ((ignoredId == null || !ignoredId.equals(preset.id))
                    && preset.name.toLowerCase(Locale.ROOT).equals(key)) return true;
        }
        return false;
    }

    private static Preset requirePreset(List<Preset> presets, String id) {
        Preset preset = findPreset(presets, id);
        if (preset == null) throw new IllegalArgumentException("Unknown preset ID");
        return preset;
    }

    private static int requirePresetIndex(List<Preset> presets, String id) {
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(id)) return i;
        }
        throw new IllegalArgumentException("Unknown preset ID");
    }

    private static Preset findPreset(List<Preset> presets, String id) {
        if (id == null) return null;
        for (Preset preset : presets) {
            if (preset.id.equals(id)) return preset;
        }
        return null;
    }

    private static boolean isSafeToken(String value) {
        return value != null && !value.isEmpty() && value.length() <= 96
                && value.matches("[A-Za-z0-9_.-]+");
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String checksum(String value) {
        CRC32 crc = new CRC32();
        try {
            crc.update(value.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
        return Long.toHexString(crc.getValue());
    }

    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("Could not persist Build 42 custom presets");
        }
    }

    private static final class LoadResult {
        final List<Preset> presets;
        final String problem;
        LoadResult(List<Preset> presets, String problem) {
            this.presets = presets;
            this.problem = problem;
        }
    }
}
