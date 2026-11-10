package dev.flixw.sdk.kit;

import java.nio.file.Path;
import java.util.Map;

/** The flixw ABI 1 paths supplied to a plugin. */
public record Context(Path projectRoot, Path compilerJar, Path java, Path pluginCache) {
    public static Context read() {
        return read(System.getenv());
    }

    /** Reads the ABI 1 environment; the cache variable remains optional. */
    public static Context read(Map<String, String> environment) {
        if (!"1".equals(environment.get("FLIXW_ABI_VERSION")))
            throw new Usage("requires flixw ABI version 1");
        // Optional, unlike the rest. It arrived after ABI 1 was first published, so a
        // wrapper that predates it simply does not set it -- and a report is correct
        // without a cache, so absence disables caching rather than failing the run.
        String cache = environment.get("FLIXW_PLUGIN_CACHE");
        return new Context(path(environment, "FLIXW_PROJECT_ROOT"), path(environment, "FLIXW_COMPILER_JAR"),
            path(environment, "FLIXW_JAVA_HOME").resolve("bin").resolve("java"),
            cache == null || cache.isBlank()
                ? null : Path.of(cache).toAbsolutePath().normalize());
    }

    private static Path path(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new Usage("missing " + name);
        return Path.of(value).toAbsolutePath().normalize();
    }
}
