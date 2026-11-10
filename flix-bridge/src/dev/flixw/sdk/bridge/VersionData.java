package dev.flixw.sdk.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The supported Flix releases as data, read from the packaged {@code versions.json}.
 *
 * <p>The file is the one place a release, its SHA-256 digest, its adapter family and its status are
 * named. The release matrix, the registry of adapter classes, the digests the fetch script checks
 * and the warning for an unverified compiler all derive from it.
 *
 * <p>This is not admission. Whether a compiler can be used is decided only by the bytecode-derived
 * linkage check in {@link AdapterAbi}. A compiler that links but is not listed is still usable; the
 * warning only says that nobody has compared its results with a recorded release.
 */
public final class VersionData {
    private static final String RESOURCE = "versions.json";
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern JAR = Pattern.compile("flix-(\\d+\\.\\d+\\.\\d+)(?:-[0-9a-f]+)?\\.jar");
    private static final String POINTER = "see flix-bridge/docs/compatibility";

    /** An adapter family: one adapter class per capability, written against {@code floor}. */
    public record Family(String id, String adapter, String floor, String testVersion,
                         List<String> capabilities) { }

    /** One Flix release. {@code family} is set when verified, {@code rejection} when rejected. */
    public record Release(String version, String sha256, String status, String family, String fixture,
                          String rejection) { }

    private final List<Family> families;
    private final List<Release> releases;

    private VersionData(List<Family> families, List<Release> releases) {
        this.families = List.copyOf(families);
        this.releases = List.copyOf(releases);
    }

    /** The data packaged with this bridge. */
    public static VersionData load() {
        try (InputStream in = VersionData.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses and validates a document: schema 1, unique families and releases, resolvable references. */
    public static VersionData parse(String document) {
        Map<String, Object> root = object(Json.parse(document), "document");
        if (!Long.valueOf(1).equals(root.get("schema"))) throw bad("unsupported schema");
        List<Family> families = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object entry : list(root.get("families"), "families")) {
            Map<String, Object> value = object(entry, "family");
            Family family = new Family(text(value, "id"), text(value, "adapter"), version(value, "floor"),
                version(value, "testVersion"), strings(value.get("capabilities"), "capabilities"));
            if (!ids.add(family.id())) throw bad("duplicate family " + family.id());
            families.add(family);
        }
        List<Release> releases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object entry : list(root.get("releases"), "releases")) {
            Map<String, Object> value = object(entry, "release");
            String status = text(value, "status");
            String sha = text(value, "sha256");
            if (!sha.matches("[0-9a-f]{64}")) throw bad("bad digest for " + value.get("version"));
            Release release = new Release(version(value, "version"), sha, status,
                optional(value, "family"), optional(value, "fixture"), optional(value, "rejection"));
            if (!seen.add(release.version())) throw bad("duplicate release " + release.version());
            switch (status) {
                case "verified" -> {
                    if (release.family() == null || !ids.contains(release.family()))
                        throw bad("verified release " + release.version() + " names no known family");
                }
                case "rejected" -> {
                    if (release.rejection() == null)
                        throw bad("rejected release " + release.version() + " names no rejection");
                }
                default -> throw bad("unknown status " + status);
            }
            releases.add(release);
        }
        for (Family family : families)
            if (!seen.contains(family.floor()) || !seen.contains(family.testVersion()))
                throw bad("family " + family.id() + " names a release that is not listed");
        return new VersionData(families, releases);
    }

    /** Families, newest first: the order in which a linking adapter is preferred. */
    public List<Family> families() {
        return families;
    }

    /** All releases in the file, oldest first. */
    public List<Release> releases() {
        return releases;
    }

    public Optional<Family> family(String id) {
        return families.stream().filter(family -> family.id().equals(id)).findFirst();
    }

    public Optional<Release> release(String version) {
        return releases.stream().filter(release -> release.version().equals(version)).findFirst();
    }

    /** Verified releases of one family, oldest first. */
    public List<Release> releasesOf(String family) {
        return releases.stream().filter(release -> family.equals(release.family())).toList();
    }

    /** The versions that have a compatibility checkpoint, oldest first. */
    public List<String> verified() {
        return releases.stream().filter(release -> release.status().equals("verified"))
            .map(Release::version).toList();
    }

    /**
     * The fields the plugin's cache key takes from the bridge: the SDK version and the adapter
     * family. Both are explicit so that a change of either one misses the cache.
     */
    public Map<String, String> cacheFields(String familyId) {
        if (family(familyId).isEmpty()) throw new IllegalArgumentException("unknown family " + familyId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("sdkVersion", Integer.toString(CompilerModel.SDK_VERSION));
        fields.put("adapterFamily", familyId);
        return Map.copyOf(fields);
    }

    /**
     * The Flix version flixw announces in {@code FLIXW_COMPILER_VERSION}, or else the one in the
     * compiler jar's file name ({@code flix-<version>-<sha256>.jar} in flixw's compiler cache).
     */
    public static Optional<String> flixVersion(Map<String, String> environment, Path compilerJar) {
        String announced = environment.get("FLIXW_COMPILER_VERSION");
        if (announced != null && VERSION.matcher(announced.strip()).matches()) return Optional.of(announced.strip());
        Matcher matcher = JAR.matcher(compilerJar.getFileName().toString());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The single warning line for a compiler jar without a checkpoint, or empty when verified. */
    public Optional<String> warning(String plugin, Path compilerJar) {
        String name = compilerJar.getFileName().toString();
        Matcher matcher = JAR.matcher(name);
        if (!matcher.matches())
            return Optional.of(plugin + ": warning: cannot read the Flix version from the compiler jar name "
                + name + "; this release has no compatibility checkpoint (newest verified: "
                + newest(verified()) + "), " + POINTER);
        return warningFor(plugin, matcher.group(1));
    }

    /** The single warning line for a Flix version without a checkpoint, or empty when verified. */
    public Optional<String> warningFor(String plugin, String version) {
        if (verified().contains(version)) return Optional.empty();
        return Optional.of(plugin + ": warning: Flix " + version + " has no compatibility checkpoint (newest "
            + "verified: " + newest(verified()) + "); results are not verified for it, " + POINTER);
    }

    /** The numerically newest version of the list, or {@code none} when it is empty. */
    public static String newest(List<String> versions) {
        return versions.stream().max(Comparator.comparing(VersionData::key)).orElse("none");
    }

    private static long key(String version) {
        String[] parts = version.split("\\.");
        return (Long.parseLong(parts[0]) * 100_000L + Long.parseLong(parts[1])) * 100_000L
            + Long.parseLong(parts[2]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw bad(what + " must be an object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String what) {
        if (value instanceof List<?> list) return (List<Object>) list;
        throw bad(what + " must be an array");
    }

    private static List<String> strings(Object value, String what) {
        List<String> result = new ArrayList<>();
        for (Object entry : list(value, what)) {
            if (!(entry instanceof String text)) throw bad(what + " must hold strings");
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String text(Map<String, Object> value, String key) {
        if (value.get(key) instanceof String text && !text.isEmpty()) return text;
        throw bad("missing " + key);
    }

    private static String optional(Map<String, Object> value, String key) {
        return value.get(key) instanceof String text ? text : null;
    }

    private static String version(Map<String, Object> value, String key) {
        String text = text(value, key);
        if (!VERSION.matcher(text).matches()) throw bad("bad version " + text);
        return text;
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException("versions.json: " + message);
    }
}
