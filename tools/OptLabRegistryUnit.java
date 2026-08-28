import com.zomdroid.NativeModulesPreferences;
import com.zomdroid.OptLabFeatureRegistry;
import com.zomdroid.OptLabPreferences;
import com.zomdroid.game.GameInstance;

/** Host checks for requested/active/native-disabled registry semantics. */
public final class OptLabRegistryUnit {
    public static void main(String[] args) {
        GameInstance b42 = new GameInstance("42", "/tmp/home", "/tmp/game");
        OptLabFeatureRegistry.Snapshot active = OptLabFeatureRegistry.evaluate(
                b42, new OptLabPreferences(true),
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
        System.out.println("OPTLAB_REGISTRY_UNIT PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
