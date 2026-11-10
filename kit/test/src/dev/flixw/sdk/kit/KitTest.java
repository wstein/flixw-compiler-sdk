package dev.flixw.sdk.kit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;

/** Executable public API contract, without a compiler dependency. */
public final class KitTest {
    private static int checks;
    private KitTest() { }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("kit-contract-");
        try {
            var env = new HashMap<String, String>();
            env.put("FLIXW_ABI_VERSION", "1");
            env.put("FLIXW_PROJECT_ROOT", root.toString());
            env.put("FLIXW_COMPILER_JAR", root.resolve("compiler.jar").toString());
            env.put("FLIXW_JAVA_HOME", System.getProperty("java.home"));
            Context context = Context.read(env);
            check(context.pluginCache() == null, "cache is optional");
            check(context.java().equals(Path.of(System.getProperty("java.home"), "bin", "java")), "java executable");
            env.put("FLIXW_ABI_VERSION", "2");
            rejects(() -> Context.read(env), "requires flixw ABI version 1");
            env.put("FLIXW_ABI_VERSION", "1");
            env.remove("FLIXW_PROJECT_ROOT");
            rejects(() -> Context.read(env), "missing FLIXW_PROJECT_ROOT");
            Path plugin = root.resolve("caller-plugin.jar");
            var command = Launcher.bridgeCommand(context, plugin, "example.Shell", new String[] {"a b", "--flag"});
            check(command.equals(List.of(context.java().toString(), "-Xss64m", "-cp",
                plugin + java.io.File.pathSeparator + context.compilerJar(), "example.Shell", "--bridge", "a b", "--flag")), "bridge command contract");
            try (var loader = InspectionLoader.open(context.compilerJar())) {
                check(loader.getParent() == ClassLoader.getPlatformClassLoader(), "inspection parent");
                check(loader.getURLs()[0].equals(context.compilerJar().toUri().toURL()), "inspection artifact");
            }
            AtomicOutput.write(root, Path.of("report.txt"), "first");
            AtomicOutput.write(root, Path.of("report.txt"), "second");
            check(Files.readString(root.resolve("report.txt")).equals("second"), "replace report");
            try { AtomicOutput.write(root, Path.of("missing/report"), "partial"); throw new AssertionError("missing output parent accepted"); }
            catch (java.io.IOException expected) { check(!Files.exists(root.resolve("missing")), "no output directory created"); }
            Path victim = root.resolve("original.txt");
            Files.writeString(victim, "original");
            Path link = root.resolve("linked-report.txt");
            Files.createSymbolicLink(link, victim);
            AtomicOutput.write(root, link, "replacement");
            check(Files.readString(victim).equals("original") && !Files.isSymbolicLink(link),
                "atomic output replaces directory entry");
            Path compiler = context.compilerJar();
            Files.writeString(compiler, "compiler-one");
            Files.writeString(plugin, "plugin-one");
            Path source = root.resolve("source.flix");
            Files.writeString(source, "source-one");
            String key = KeyedStore.key(context, List.of(source), "v1", plugin);
            check(key != null && key.length() == 64, "key is SHA-256");
            Files.writeString(compiler, "compiler-two");
            check(!key.equals(KeyedStore.key(context, List.of(source), "v1", plugin)), "compiler bytes affect key");
            Files.writeString(compiler, "compiler-one");
            Path renamed = root.resolve("renamed.flix");
            Files.move(source, renamed);
            check(!key.equals(KeyedStore.key(context, List.of(renamed), "v1", plugin)), "source paths affect key");
            Files.move(renamed, source);
            Files.writeString(plugin, "plugin-two");
            check(!key.equals(KeyedStore.key(context, List.of(source), "v1", plugin)), "caller plugin bytes affect key");
            Files.writeString(plugin, "plugin-one");
            check(!key.equals(KeyedStore.key(context, List.of(source), "v2", plugin)), "plugin version affects key");
            Files.writeString(root.resolve("flix.toml"), "manifest");
            check(!key.equals(KeyedStore.key(context, List.of(source), "v1", plugin)), "manifest affects key");
            Files.writeString(source, "source-two");
            check(!key.equals(KeyedStore.key(context, List.of(source), "v1", plugin)), "source contents affect key");
            Path cache = root.resolve("cache");
            check(KeyedStore.read(cache, key) == null, "absent cache misses");
            KeyedStore.write(cache, key, "opaque\u0000payload");
            check("opaque\u0000payload".equals(KeyedStore.read(cache, key)), "opaque payload round trip");
            KeyedStore.write(plugin, key, "ignored");
            check(KeyedStore.read(plugin, key) == null, "unwritable cache is best effort");
            check(KeyedStore.key(context, List.of(root.resolve("absent")), "v1", plugin) == null, "unreadable input disables cache");
            check(KeyedStore.directory(context) == null, "no guessed cache directory");
            check(ExitCodes.SUCCESS == 0 && ExitCodes.POLICY_FINDING == 1 && ExitCodes.ERROR == 2, "exit codes");
            var provenance = ProvenanceData.of(context, "v1", null);
            check(provenance.commit().equals("(not a git checkout)"), "non-git fallback");
            check(provenance.inputDigest().equals("(unavailable)"), "input digest fallback");
            check(provenance.compilerArtifact().equals("compiler.jar"), "compiler artifact name");
            String java = context.java().toString();
            String cp = System.getProperty("java.class.path");
            long start = System.nanoTime();
            check(ProvenanceData.command(root, Duration.ofMillis(50), List.of(java, "-cp", cp, Probe.class.getName(), "wait")).isEmpty(), "git timeout fallback");
            check(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 3, "bounded process timeout");
            check(ProvenanceData.command(root, Duration.ofSeconds(5), List.of(java, "-cp", cp, Probe.class.getName(), "ok")).equals("ok"), "successful command output");
            Thread.currentThread().interrupt();
            check(ProvenanceData.command(root, Duration.ofSeconds(5), List.of(java, "-cp", cp, Probe.class.getName(), "wait")).isEmpty(), "interruption fallback");
            check(Thread.interrupted(), "interrupt flag restored");
            try (var paths = Files.list(root)) {
                check(paths.noneMatch(p -> p.getFileName().toString().startsWith(".flixw-")), "output temporaries cleaned");
            }
            System.out.println("KitTest: " + checks + " passed, 0 failed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); throw new AssertionError("usage accepted"); }
        catch (Usage expected) { check(expected.getMessage().equals(message), "usage diagnostic"); }
    }
    public static final class Probe {
        private Probe() { }
        public static void main(String[] args) throws Exception {
            if (args[0].equals("wait")) Thread.sleep(10000);
            System.out.println("ok");
        }
    }
}
