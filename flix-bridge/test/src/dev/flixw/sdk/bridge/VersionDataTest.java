package dev.flixw.sdk.bridge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Checks the version data file and everything derived from it. */
public final class VersionDataTest {
    private static int passed;

    private VersionDataTest() { }

    public static void main(String[] args) {
        VersionData data = VersionData.load();
        require(data.verified().equals(List.of("0.60.0", "0.61.0", "0.65.0", "0.66.1", "0.67.1", "0.67.2",
                "0.68.0", "0.75.2", "0.75.3", "0.76.0", "0.76.1", "0.77.0", "0.78.0", "0.79.0")),
            "the data file lists the fourteen verified releases, oldest first");
        require(data.verified().size() == 14, "fourteen verified releases");
        require(!data.verified().contains("0.59.0") && !data.verified().contains("0.66.0"),
            "the rejected releases are not verified");
        require(data.release("0.59.0").orElseThrow().rejection().equals("Symbol$EffSym")
                && data.release("0.66.0").orElseThrow().rejection().equals("dev.flix.runtime.Global"),
            "a rejected release names the reference its jar lacks");
        require(data.families().stream().map(VersionData.Family::id).toList().equals(
                List.of("flix0761", "flix0753", "flix0680", "flix0672", "flix0661", "flix0610", "flix0600")),
            "seven families, newest first");
        require(data.releases().stream().map(VersionData.Release::sha256).distinct().count() == 16
                && data.releases().stream().allMatch(r -> r.sha256().matches("[0-9a-f]{64}")),
            "every release has its own SHA-256 digest");
        require(data.family("flix0761").orElseThrow().adapter().equals("dev.flixw.sdk.bridge.adapter.Flix0761Adapter")
                && data.family("flix0761").orElseThrow().capabilities().equals(List.of("metrics")),
            "a family names its adapter class and capabilities");
        require(data.release("0.79.0").orElseThrow().family().equals("flix0761"),
            "a verified release names its family");
        require(data.releasesOf("flix0661").stream().map(VersionData.Release::version).toList()
                .equals(List.of("0.66.1", "0.67.1")), "releases of a family are listed oldest first");

        require(data.warning("metrics", Path.of("/cache/compilers",
                "flix-0.79.0-8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf.jar")).isEmpty(),
            "a verified release in flixw's cache naming gets no notice");
        require(data.warning("metrics", Path.of("/cache/compilers/flix-0.77.0.jar")).isEmpty(),
            "a verified release by plain name gets no notice");
        String unknown = data.warning("metrics", Path.of("/cache/compilers/flix-0.80.0-abcdef0123.jar")).orElseThrow();
        require(unknown.startsWith("metrics: warning: ") && unknown.indexOf('\n') < 0,
            "the notice is a single warning line led by the plugin name");
        require(unknown.contains("0.80.0") && unknown.contains("newest verified: 0.79.0")
                && unknown.contains("flix-bridge/docs/compatibility"),
            "the notice names the release, the newest verified one and the compatibility records");
        require(data.warning("metrics", Path.of("/cache/compilers/flix-0.80.0.jar")).orElseThrow()
                .equals(unknown.replace("-abcdef0123", "")), "the cache suffix does not change the notice");
        require(VersionData.newest(List.of("0.9.0", "0.10.0", "0.2.5")).equals("0.10.0"),
            "the newest release is chosen numerically, not as text");
        require(VersionData.newest(List.of("0.79.0", "0.100.0", "0.80.0")).equals("0.100.0"),
            "a three-digit minor version is newer than a two-digit one");
        require(VersionData.newest(List.of()).equals("none"), "no verified release is named none");
        require(data.warning("metrics", Path.of("flix-0.100.0.jar")).orElseThrow().contains("0.100.0 has no"),
            "a release with a longer minor version is judged as itself");
        require(data.warning("metrics", Path.of("flix-0.59.0.jar")).orElseThrow().contains("0.59.0"),
            "a rejected release is noticed as well");
        for (String name : List.of("xflix-0.79.0.jar", "flix-0.79.0-rc1.jar", "flix-0.79.0-ABCDEF.jar",
                "flix-00.79.0.jar", "flix-0.79.0.jar.bak", "flix-0.79.00.jar", "flix-0.7.0.jar")) {
            require(data.warning("metrics", Path.of("/c", name)).isPresent(),
                name + " is not a verified release name");
        }
        require(data.warning("metrics", Path.of("/c/flix-0.79.0-ABCDEF.jar")).orElseThrow().contains("cannot read"),
            "an upper-case digest suffix is not a cache name");
        String unreadable = data.warning("metrics", Path.of("/c/flix.jar")).orElseThrow();
        require(unreadable.contains("cannot read the Flix version") && unreadable.indexOf('\n') < 0,
            "a jar name without a version gets a single notice that says so");

        require(VersionData.flixVersion(Map.of("FLIXW_COMPILER_VERSION", "0.79.0"), Path.of("/c/flix.jar"))
                .equals(Optional.of("0.79.0")), "FLIXW_COMPILER_VERSION names the Flix version");
        require(VersionData.flixVersion(Map.of("FLIXW_COMPILER_VERSION", "0.79.0"), Path.of("/c/flix-0.60.0.jar"))
                .equals(Optional.of("0.79.0")), "the variable wins over the jar name");
        require(VersionData.flixVersion(Map.of(), Path.of("/c/flix-0.60.0-abc.jar"))
                .equals(Optional.of("0.60.0")), "without the variable the jar name is the fallback");
        require(VersionData.flixVersion(Map.of("FLIXW_COMPILER_VERSION", " "), Path.of("/c/flix.jar")).isEmpty(),
            "a blank variable and an unreadable name give no version");
        require(data.warningFor("metrics", "0.79.0").isEmpty() && data.warningFor("metrics", "0.80.0").isPresent(),
            "a notice can be derived from the version alone");
        require(data.cacheFields("flix0761").equals(Map.of("sdkVersion", "1", "adapterFamily", "flix0761")),
            "SDK version and adapter family are explicit cache fields");

        require(CompilerModel.CAPABILITY == Capability.METRICS && Capability.of("metrics").orElseThrow() == Capability.METRICS
                && Capability.of("solverHook").orElseThrow() == Capability.SOLVER_HOOK
                && Capability.of("nothing").isEmpty(), "the capabilities are named, and Metrics is CompilerModel");
        require(data.families().stream().allMatch(f -> f.capabilities().equals(List.of("metrics"))),
            "every family provides the metrics capability");
        rejects(minimal("\"status\": \"verified\", \"family\": \"f\"").replace("[\"metrics\"]", "[\"magic\"]"),
            "unknown capability");
        rejects("{", "unterminated object");
        rejects("{\"a\": 1, \"a\": 2}", "duplicate key");
        rejects("{} x", "trailing text");
        rejects(minimal("\"status\": \"verified\", \"family\": \"nope\""), "unknown family");
        rejects(minimal("\"status\": \"verified\""), "verified release without a family");
        rejects(minimal("\"status\": \"maybe\", \"family\": \"f\""), "unknown status");
        rejects(minimal("\"status\": \"rejected\""), "rejected release without a rejection");
        rejects(minimal("\"status\": \"verified\", \"family\": \"f\"").replace("0.60.0", "0.6"), "bad version");
        rejects(minimal("\"status\": \"verified\", \"family\": \"f\"").replace(hex(), "abc"), "bad digest");
        rejects(minimal("\"status\": \"verified\", \"family\": \"f\"").replace("\"schema\": 1", "\"schema\": 2"),
            "unsupported schema");
        rejects(minimal("\"status\": \"verified\", \"family\": \"f\"").replace("\"floor\": \"0.60.0\"",
            "\"floor\": \"0.59.9\""), "floor is not a release");
        require(VersionData.parse(minimal("\"status\": \"verified\", \"family\": \"f\"")).verified()
            .equals(List.of("0.60.0")), "the minimal document is accepted");
        System.out.println("VersionDataTest: " + passed + " passed, 0 failed");
    }

    private static String hex() {
        return "0".repeat(64);
    }

    private static String minimal(String release) {
        return "{\"schema\": 1, \"families\": [{\"id\": \"f\", \"adapter\": \"a.B\", \"floor\": \"0.60.0\","
            + " \"testVersion\": \"0.60.0\", \"capabilities\": [\"metrics\"]}], \"releases\": [{\"version\": \"0.60.0\","
            + " \"sha256\": \"" + hex() + "\", " + release + "}]}";
    }

    private static void rejects(String document, String reason) {
        try {
            VersionData.parse(document);
        } catch (IllegalArgumentException e) {
            passed++;
            return;
        }
        throw new AssertionError("accepted: " + reason);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}
