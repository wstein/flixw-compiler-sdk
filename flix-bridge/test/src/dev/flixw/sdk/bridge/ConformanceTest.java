package dev.flixw.sdk.bridge;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The conformance suite of one adapter family, independent of any plugin.
 *
 * <p>Two modes, both driven by {@code versions.json}:
 * <ul>
 * <li>{@code linkage <family> <libDir>} needs no compiler on the class path. It checks the digest of
 *     every jar of the family, that the family's bytecode-derived contract is satisfied by each
 *     one, and that no newer family claims it, so the registry selects this family for it.
 * <li>{@code adapter <family> <version> <fixtureRoot>} runs with that release's jar on the class
 *     path. It checks that {@link Adapters#select()} chooses this family and that the adapter
 *     measures the fixture, and that its measurement digest is the recorded one.
 * <li>{@code rejected <libDir>} checks that every rejected release is refused: the
 *     ABI check refuses it and names the reference that {@code versions.json} gives as the reason.
 * </ul>
 */
public final class ConformanceTest {
    private static int passed;

    /**
     * Digest of the per-definition measurement of the fixtures. The two fixtures hold the same
     * program, and every adapter family measures it identically; a change here is a change in what
     * an adapter reports, to be made on purpose and recorded with the new value.
     */
    private static final String MEASUREMENT = "0d5322308d140a9b";

    private ConformanceTest() { }

    public static void main(String[] args) throws Exception {
        VersionData data = VersionData.load();
        if (args.length == 3 && args[0].equals("linkage")) linkage(data, args[1], Path.of(args[2]));
        else if (args.length == 2 && args[0].equals("rejected")) rejected(data, Path.of(args[1]));
        else if (args.length == 4 && args[0].equals("adapter"))
            adapter(data, args[1], args[2], Path.of(args[3]));
        else throw new IllegalArgumentException(
            "usage: linkage <family> <libDir> | rejected <libDir> | adapter <family> <version> <fixtureRoot>");
    }

    private static void linkage(VersionData data, String familyId, Path lib) throws Exception {
        VersionData.Family family = data.family(familyId).orElseThrow();
        AdapterAbi.Contract contract = AdapterAbi.contract(family.adapter());
        require(!contract.references().isEmpty(), familyId + ": the contract is not empty");
        require(contract.references().stream().allMatch(r -> r.owner().startsWith("ca/uwaterloo/flix/")),
            familyId + ": the contract names only Flix classes");
        require(contract.references().stream().anyMatch(r -> r.kind() == AdapterAbi.Kind.METHOD),
            familyId + ": the contract names methods, not only classes");
        require(family.adapter().equals("dev.flixw.sdk.bridge.adapter.Flix" + familyId.substring(4) + "Adapter"),
            familyId + ": the adapter class is named after its family");
        List<VersionData.Family> newer = data.families().subList(0, data.families().indexOf(family));
        List<VersionData.Release> releases = data.releasesOf(familyId);
        require(!releases.isEmpty(), familyId + ": the family has a verified release");
        require(releases.stream().anyMatch(r -> r.version().equals(family.floor())),
            familyId + ": the floor is one of its releases");
        for (VersionData.Release release : releases) {
            Path jar = lib.resolve("flix-" + release.version() + ".jar");
            require(sha256(jar).equals(release.sha256()), release.version() + ": digest matches versions.json");
            try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                List<String> missing = AdapterAbi.missing(contract, loader);
                require(missing.isEmpty(), release.version() + ": satisfies the " + familyId + " contract, missing "
                    + missing.stream().limit(3).collect(Collectors.joining("; ")));
                for (VersionData.Family other : newer)
                    require(!AdapterAbi.missing(AdapterAbi.contract(other.adapter()), loader).isEmpty(),
                        release.version() + ": newer family " + other.id() + " does not claim it");
                CompilerCapabilities found = CompilerCapabilities.inspect(loader, jar);
                require(found.hasFlixApi() && found.hasEngineApi() && found.missing().isEmpty(),
                    release.version() + ": the ABI check accepts it");
            }
        }
        System.out.println("ConformanceTest linkage " + familyId + ": " + passed + " passed, 0 failed");
    }

    private static void rejected(VersionData data, Path lib) throws Exception {
        int count = 0;
        for (VersionData.Release release : data.releases()) {
            if (!release.status().equals("rejected")) continue;
            count++;
            Path jar = lib.resolve("flix-" + release.version() + ".jar");
            require(sha256(jar).equals(release.sha256()), release.version() + ": digest matches versions.json");
            try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                CompilerCapabilities found = CompilerCapabilities.inspect(loader, jar);
                require(!found.hasEngineApi() && found.missing().stream().anyMatch(m -> m.contains(release.rejection())),
                    release.version() + ": refused for " + release.rejection() + ", got " + found.missing());
            }
        }
        require(count == 2, "two releases are rejected");
        System.out.println("ConformanceTest rejected: " + passed + " passed, 0 failed");
    }

    private static void adapter(VersionData data, String familyId, String version, Path fixtureRoot)
            throws Exception {
        VersionData.Release release = data.release(version).orElseThrow();
        require(familyId.equals(release.family()), version + ": versions.json assigns it to " + familyId);
        Adapters.Selection selection = Adapters.select();
        require(selection != null, version + ": an adapter links");
        require(selection.family().equals(familyId), version + ": the registry selects " + familyId
            + ", not " + selection.family());
        require(selection.adapterClass().equals(data.family(familyId).orElseThrow().adapter()),
            version + ": the selection names the family's adapter class");
        require(selection.cacheFields().equals(data.cacheFields(familyId)),
            version + ": the selection carries the explicit cache fields");
        Path project = Files.createTempDirectory("flixw-bridge-conformance-");
        try {
            copy(fixtureRoot.resolve(release.fixture()), project);
            // 0.78.0 and later reject manifest keys that earlier releases require.
            Flix0753AdapterTest.adaptManifest(project);
            CompilerModel.Model model = selection.model().measure(project);
            require(!model.defs().isEmpty() && model.lines().code() > 0,
                version + ": the adapter measures the fixture");
            StringBuilder summary = new StringBuilder();
            for (CompilerModel.DefInfo def : model.defs())
                summary.append(def.name()).append('|').append(def.file()).append('|').append(def.line())
                    .append('|').append(def.lines()).append('|').append(def.codeLines()).append('|')
                    .append(def.parameters()).append('|').append(def.cognitive()).append('|')
                    .append(def.nesting()).append('|').append(def.isTest()).append('\n');
            summary.append(model.lines()).append(model.modules().size()).append(model.enums());
            String measurement = sha256(summary.toString());
            require(measurement.equals(MEASUREMENT), version + ": measurement " + measurement
                + " differs from the recorded " + MEASUREMENT + " that every release gives for the fixtures");
            System.out.println("ConformanceTest adapter " + familyId + " " + version + ": " + passed
                + " passed, 0 failed, defs=" + model.defs().size() + " measurement=" + measurement);
        } finally {
            delete(project);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return HexFormat.of().formatHex(digest().digest(in.readAllBytes()));
        }
    }

    private static String sha256(String text) {
        return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static void delete(Path root) throws IOException {
        List<Path> all = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(all::add);
        }
        for (Path path : all) Files.delete(path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}
