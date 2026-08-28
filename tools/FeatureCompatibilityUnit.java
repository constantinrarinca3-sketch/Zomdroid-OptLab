import com.zomdroid.agent.optimization.FeatureCompatibility;

/** Host checks for the per-mechanism B42 compatibility state machine. */
public final class FeatureCompatibilityUnit {
    private static final String EXACT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    public static void main(String[] args) {
        testExactHashIsVerifiedCandidate();
        testUnknownB42RequiresAndPassesShapeProbe();
        testNonB42IsUnsupported();
        testExplicitCrossFamilyProbe();
        testMissingClassIsUnsupported();
        testFailureIsIsolated();
        System.out.println("FEATURE_COMPATIBILITY_UNIT PASS");
    }

    private static void testExactHashIsVerifiedCandidate() {
        configure("42", true, true);
        FeatureCompatibility.Decision decision = FeatureCompatibility.evaluate(
                "EXACT", true, 2, requirement(EXACT));
        require(decision.isCandidate(), "exact B42 class must be a candidate");
        decision.passPart("class_a");
        require(!decision.isActive(), "all structural parts must pass before ACTIVE");
        decision.passPart("class_b");
        require(decision.isActive(), "exact candidate must activate after all shapes pass");
    }

    private static void testUnknownB42RequiresAndPassesShapeProbe() {
        configure("42", false, true);
        FeatureCompatibility.Decision decision = FeatureCompatibility.evaluate(
                "PROBE", true, 1, requirement(OTHER));
        require(decision.isCandidate(), "unknown B42 hash must reach its structural probe");
        require(!decision.isActive(), "probe must not be active before its shape passes");
        decision.passPart("shape");
        require(decision.isActive(), "compatible shape must activate an unknown B42 hash");
    }

    private static void testNonB42IsUnsupported() {
        configure("41", false, true);
        FeatureCompatibility.Decision decision = FeatureCompatibility.evaluate(
                "B42_ONLY", true, 1, requirement(EXACT));
        require(!decision.isCandidate(), "B41 must not reach B42 bytecode transforms");
    }

    private static void testExplicitCrossFamilyProbe() {
        configure("41", false, false);
        FeatureCompatibility.Decision decision = FeatureCompatibility.evaluate(
                "CROSS_FAMILY", true, 1, requirement(EXACT));
        require(decision.isCandidate(), "Only Build 42 OFF must permit a structural probe");
        require(!decision.isActive(), "cross-family probe must never start as verified");
        decision.passPart("shape");
        require(decision.isActive(), "matching cross-family shape may become compatible");
    }

    private static void testMissingClassIsUnsupported() {
        configure("42", false, true);
        FeatureCompatibility.Decision decision = FeatureCompatibility.evaluate(
                "MISSING_CLASS", true, 1,
                new FeatureCompatibility.Requirement("target", EXACT, "MISSING"));
        require(!decision.isCandidate(), "missing target class must fail open");
    }

    private static void testFailureIsIsolated() {
        configure("42", false, true);
        FeatureCompatibility.Decision first = FeatureCompatibility.evaluate(
                "FIRST", true, 1, requirement(OTHER));
        FeatureCompatibility.Decision second = FeatureCompatibility.evaluate(
                "SECOND", true, 1, requirement(OTHER));
        first.fallback("test_shape_mismatch");
        require(!first.isCandidate(), "failed mechanism must be disabled");
        require(second.isCandidate(), "one failure must not block another mechanism");
        second.passPart("shape");
        require(second.isActive(), "unrelated mechanism must still activate");
    }

    private static FeatureCompatibility.Requirement requirement(String actual) {
        return new FeatureCompatibility.Requirement("target", EXACT, actual);
    }

    private static void configure(String family, boolean knownJar, boolean onlyBuild42) {
        System.setProperty("zomdroid.optlab.pz.build.family", family);
        System.setProperty("zomdroid.optlab.pz.version.hint", family + ".test");
        System.setProperty("zomdroid.optlab.jar.sha256", knownJar ? EXACT : OTHER);
        System.setProperty("zomdroid.optlab.jar.known", knownJar ? "1" : "0");
        System.setProperty("zomdroid.optlab.only.build42", onlyBuild42 ? "1" : "0");
        FeatureCompatibility.configureFromProperties();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
