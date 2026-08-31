import com.zomdroid.NativeModulesPreferences;
import com.zomdroid.OptLabFeatureRegistry;
import com.zomdroid.OptLabPreferences;
import com.zomdroid.game.GameInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Host checks for requested/active/native-disabled registry semantics. */
public final class OptLabRegistryUnit {
    public static void main(String[] args) {
        GameInstance b42 = new GameInstance("42", "/tmp/home", "/tmp/game");
        OptLabFeatureRegistry.Snapshot active = OptLabFeatureRegistry.evaluate(
                b42, new OptLabPreferences(true, true),
                new NativeModulesPreferences(true, true, true, true), "known-jar",
                true, true, true, true);
        require(active.get(OptLabFeatureRegistry.Feature.LIGHTING64_NATIVE).requested,
                "Lighting requested");
        require(active.get(OptLabFeatureRegistry.Feature.PZCLIPPER_NATIVE).compatibility
                        == OptLabFeatureRegistry.Compatibility.VERIFIED,
                "PZClipper verified");
        require(active.get(OptLabFeatureRegistry.Feature.PATHFINDING_NATIVE).compatibility
                        == OptLabFeatureRegistry.Compatibility.VERIFIED,
                "Pathfinding verified");
        require(active.get(OptLabFeatureRegistry.Feature.POPMAN_NATIVE).compatibility
                        == OptLabFeatureRegistry.Compatibility.VERIFIED,
                "PopMan verified when audited payload is active");
        require(active.get(OptLabFeatureRegistry.Feature.CHUNK_VEHICLE_INDEX).requested,
                "Vehicle index requested");
        require(active.get(OptLabFeatureRegistry.Feature.CHUNK_NEIGHBOUR_MAIN).compatibility
                        == OptLabFeatureRegistry.Compatibility.VERIFIED,
                "Chunk mechanism verified independently");
        require(active.get(OptLabFeatureRegistry.Feature.CHUNK_CP2C_DIRTY_CLEAR).feature.category
                        == OptLabFeatureRegistry.Category.EXPERIMENTAL,
                "CP2C remains experimental");
        require(active.get(OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_LOOKUP).requested,
                "CP6.2 lookup requested independently");
        require(active.get(OptLabFeatureRegistry.Feature.RTHREAD_RING_RENDER_CLEAR).compatibility
                        == OptLabFeatureRegistry.Compatibility.VERIFIED,
                "CP6.2 ring clear verified for a known B42 jar");
        List<String> activeProperties = new ArrayList<>();
        active.addAgentFeatureProperties(activeProperties);
        HashSet<String> uniqueProperties = new HashSet<>(activeProperties);
        require(uniqueProperties.size() == activeProperties.size(),
                "registry emitted a duplicate JVM property");
        int expectedProperties = 0;
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.Feature.values()) {
            if (feature.agentProperty != null) expectedProperties++;
        }
        require(activeProperties.size() == expectedProperties,
                "registry did not emit every agent property");
        require(activeProperties.contains("-Dzomdroid.optlab.render.shader.lookup=1"),
                "CP6.6 shader property missing");
        require(activeProperties.contains("-Dzomdroid.optlab.render.texture.bind=1"),
                "CP6.6 texture bind property missing");
        require(activeProperties.contains("-Dzomdroid.optlab.render.build.loop=1"),
                "CP6.6 build-loop property missing");

        OptLabFeatureRegistry.Snapshot disabled = OptLabFeatureRegistry.evaluate(
                b42, new OptLabPreferences(true),
                new NativeModulesPreferences(false, false, false, false), "other-jar",
                false, false, false, false);
        OptLabFeatureRegistry.Entry path =
                disabled.get(OptLabFeatureRegistry.Feature.PATHFINDING_NATIVE);
        require(!path.requested, "Pathfinding not requested");
        require(path.compatibility == OptLabFeatureRegistry.Compatibility.COMPATIBLE,
                "disabled path is compatible, not failed");
        require("USER_DISABLED".equals(path.reason), "disabled reason");
        List<String> disabledProperties = new ArrayList<>();
        disabled.addAgentFeatureProperties(disabledProperties);
        for (String property : disabledProperties) {
            require(property.endsWith("=0"), "disabled registry emitted ON: " + property);
        }
        System.out.println("OPTLAB_REGISTRY_UNIT PASS properties=" + expectedProperties
                + " unique=1 cp66_wired=1");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
