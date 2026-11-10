package dev.flixw.sdk.bridge;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.spi.ToolProvider;

/** Checks that capability detection is based on loadable compiler classes, not filenames. */
public final class CompilerCapabilitiesTest {
    private CompilerCapabilitiesTest() { }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("flixw-bridge-test-");
        try {
            Path empty = work.resolve("empty.jar");
            jar(empty, work.resolve("none"));
            CompilerCapabilities noApi = inspect(empty);
            require(!noApi.hasFlixApi() && !noApi.hasEngineApi(),
                "empty jar has no capabilities");
            require(!noApi.missing().isEmpty(), "an empty jar reports what it is missing");
            Set<String> linked = AdapterAbi.contracts().stream()
                .flatMap(contract -> contract.references().stream())
                .map(AdapterAbi.Reference::display).collect(Collectors.toSet());
            require(AdapterAbi.contracts().stream().anyMatch(contract -> noApi.missing().containsAll(
                contract.references().stream().map(AdapterAbi.Reference::display).toList())),
                "the capability gate reports every reference in its closest adapter contract");
            require(AdapterAbi.contracts().size() == 7,
                "the capability gate derives a separate contract for each adapter");
            require(linked.stream().anyMatch(r -> r.contains("formatType$default$2:()")),
                "the bytecode contract includes Scala default-argument accessors");
            require(linked.stream().anyMatch(r -> r.contains("MODULE$:") && r.contains("FormatType$")),
                "the bytecode contract includes Scala singleton fields");
            require(linked.stream().anyMatch(r -> r.contains("formatType:(Lca/uwaterloo/flix/language/ast/Type;")),
                "the bytecode contract includes full JVM method descriptors");

            Path source = work.resolve("source");
            write(source, "ca/uwaterloo/flix/api/Flix.java",
                "package ca.uwaterloo.flix.api; public final class Flix { }");
            write(source, "ca/uwaterloo/flix/language/phase/Lexer$.java",
                "package ca.uwaterloo.flix.language.phase; public final class Lexer$ { }");
            write(source, "ca/uwaterloo/flix/language/ast/TokenKind.java",
                "package ca.uwaterloo.flix.language.ast; public interface TokenKind { }");
            write(source, "ca/uwaterloo/flix/language/fmt/FormatType$.java",
                "package ca.uwaterloo.flix.language.fmt; public final class FormatType$ { "
                + "public Object formatType(Object a, Object b, Object c, Object d, Object e) "
                + "{ return null; } }");
            Path classes = work.resolve("classes");
            Files.createDirectories(classes);
            int compiled = ToolProvider.findFirst("javac").orElseThrow()
                .run(System.out, System.err, "-d", classes.toString(),
                    source.resolve("ca/uwaterloo/flix/api/Flix.java").toString(),
                    source.resolve("ca/uwaterloo/flix/language/phase/Lexer$.java").toString(),
                    source.resolve("ca/uwaterloo/flix/language/ast/TokenKind.java").toString(),
                    source.resolve("ca/uwaterloo/flix/language/fmt/FormatType$.java").toString());
            require(compiled == 0, "fixture compiler succeeds");
            Path metrics = work.resolve("metrics.jar");
            jar(metrics, classes);
            CompilerCapabilities found = inspect(metrics);
            require(found.hasFlixApi() && !found.hasEngineApi(),
                "fixture distinguishes Flix presence from the adapter engine API");
            require(found.missing().contains("class dev.flix.runtime.Global"),
                "the gate requires the compiler runtime class used by its standard library");

            // The gate must name the members the engine truly calls. A jar carrying a Flix
            // class with the *old* gate's members and none of the engine's must still fail:
            // that combination is exactly what used to pass and then die mid-run.
            require(found.missing().stream().anyMatch(m -> m.contains("Bootstrap")),
                "the gate requires Bootstrap, which the engine calls");
            require(found.missing().stream().anyMatch(m -> m.contains("Options$")),
                "the gate requires Options$, which the engine calls");
            require(found.missing().stream().noneMatch(m -> m.contains("addFile")),
                "the gate does not require addFile, which the engine never calls");
            require(found.missing().stream().anyMatch(m -> m.contains("Lexer$.lex:(")),
                "the gate requires the lexer entry point used for source metrics");
            require(found.missing().stream().anyMatch(m -> m.contains("TokenKind.isComment:()Z")),
                "the gate requires token comment classification");
            String formatter = linked.stream()
                .filter(r -> r.contains("FormatType$.formatType:("))
                .findFirst().orElseThrow();
            require(found.missing().contains(formatter),
                "a same-arity formatter with the wrong JVM descriptor fails the gate");
            require(found.missing().stream().anyMatch(m -> m.contains("TypedAst$Constraint")),
                "the gate requires typed Datalog nodes");
            require(found.missing().stream().anyMatch(m -> m.contains("TypedAst$CatchRule")),
                "the gate requires every control-flow rule class the adapter matches");
            require(found.missing().stream().anyMatch(m -> m.contains("TypeConstructor")),
                "the gate requires typed return-shape constructors");
            require(found.missing().stream().anyMatch(m -> m.contains("Expr$ApplyDef")),
                "the gate requires the call-site node module coupling resolves through");
            require(found.missing().stream().anyMatch(m -> m.contains("FormalParam")),
                "the gate requires the formal parameter node used for names and FlixDoc width");

            nestedClasses(work);
            descriptors(work);
            require(CompilerCapabilities.present(ClassLoader.getPlatformClassLoader(), "java.lang.String")
                    && !CompilerCapabilities.present(ClassLoader.getPlatformClassLoader(), "no.Such"),
                "presence is judged by loading without initializing");
            try {
                CompilerCapabilities.inspect(ClassLoader.getPlatformClassLoader(), work.resolve("absent.jar"));
                throw new AssertionError("a missing compiler jar is accepted");
            } catch (dev.flixw.sdk.kit.Usage expected) {
                require(expected.getMessage().contains("FLIXW_COMPILER_JAR is not a regular file"),
                    "a missing compiler jar is a usage error naming the variable");
            }

            if (args.length >= 1) {
                CompilerCapabilities stock = inspect(Path.of(args[0]));
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the pinned compiler satisfies every bytecode-derived adapter requirement");
            }
            if (args.length >= 2) {
                Path older = Path.of(args[1]);
                CompilerCapabilities stock = inspect(older);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the older compiler satisfies one bytecode-derived adapter requirement");
                require(!missing(older, AdapterAbi.contracts().get(0)).isEmpty(),
                    "the older compiler does not satisfy the newer adapter contract");
                require(missing(older, AdapterAbi.contracts().get(1)).isEmpty(),
                    "the older compiler satisfies its dedicated adapter contract");
            }
            if (args.length >= 3) {
                Path oldest = Path.of(args[2]);
                CompilerCapabilities stock = inspect(oldest);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the oldest compiler satisfies one bytecode-derived adapter requirement");
                require(!missing(oldest, AdapterAbi.contracts().get(1)).isEmpty(),
                    "the 0.68 compiler does not satisfy the 0.75.3 adapter contract");
                require(missing(oldest, AdapterAbi.contracts().get(2)).isEmpty(),
                    "the oldest compiler satisfies its dedicated adapter contract");
            }
            if (args.length >= 4) {
                Path earliest = Path.of(args[3]);
                CompilerCapabilities stock = inspect(earliest);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the earliest compiler satisfies one bytecode-derived adapter requirement");
                require(!missing(earliest, AdapterAbi.contracts().get(2)).isEmpty(),
                    "the 0.67.2 compiler does not satisfy the 0.68 adapter contract");
                require(missing(earliest, AdapterAbi.contracts().get(3)).isEmpty(),
                    "the earliest compiler satisfies its dedicated adapter contract");
            }
            if (args.length >= 5) {
                Path resultEra = Path.of(args[4]);
                CompilerCapabilities stock = inspect(resultEra);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the Result-era compiler satisfies an adapter contract");
                require(!missing(resultEra, AdapterAbi.contracts().get(3)).isEmpty(),
                    "the 0.66.1 compiler does not satisfy the 0.67.2 adapter contract");
                require(missing(resultEra, AdapterAbi.contracts().get(4)).isEmpty(),
                    "the Result-era compiler satisfies its dedicated adapter contract");
            }
            if (args.length >= 6) {
                Path validation = Path.of(args[5]);
                CompilerCapabilities stock = inspect(validation);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the Validation-era compiler satisfies an adapter contract");
                require(!missing(validation, AdapterAbi.contracts().get(4)).isEmpty(),
                    "the Validation-era compiler does not satisfy the Result-era contract");
                require(missing(validation, AdapterAbi.contracts().get(5)).isEmpty(),
                    "the Validation-era compiler satisfies its dedicated contract");
            }
            if (args.length >= 7) {
                Path preExtMatch = Path.of(args[6]);
                CompilerCapabilities stock = inspect(preExtMatch);
                require(stock.hasEngineApi() && stock.missing().isEmpty(),
                    "the pre-extensible-match compiler satisfies an adapter contract");
                require(!missing(preExtMatch, AdapterAbi.contracts().get(5)).isEmpty(),
                    "the pre-extensible-match compiler does not satisfy the 0.61 contract");
                require(missing(preExtMatch, AdapterAbi.contracts().get(6)).isEmpty(),
                    "the pre-extensible-match compiler satisfies its dedicated contract");
            }
            System.out.println("CompilerCapabilitiesTest: " + passed + " passed, 0 failed");
        } finally {
            delete(work);
        }
    }

    /**
     * A contract follows nested classes of the adapter, where the Scala compiler puts closures and
     * singletons, and ignores classes whose name merely starts with the adapter name.
     */
    private static void nestedClasses(Path work) throws IOException {
        Path source = work.resolve("nested-source");
        write(source, "ca/uwaterloo/flix/api/Flix.java",
            "package ca.uwaterloo.flix.api; public final class Flix { public static int one() { return 1; } }");
        write(source, "ca/uwaterloo/flix/api/Other.java",
            "package ca.uwaterloo.flix.api; public final class Other { public static int two() { return 2; } }");
        write(source, "ca/uwaterloo/flix/api/Third.java",
            "package ca.uwaterloo.flix.api; public final class Third { public static int three() { return 3; } }");
        write(source, "ca/uwaterloo/flix/api/Stray.java",
            "package ca.uwaterloo.flix.api; public final class Stray { public static int only() { return 5; } }");
        write(source, "fixture/Outer.java", "package fixture; public final class Outer { "
            + "public int plain() { return ca.uwaterloo.flix.api.Flix.one() + new OuterSibling().sibling(); } "
            + "public static final class Inner { public int nested() { return ca.uwaterloo.flix.api.Other.two(); } } "
            + "public static final class Deeper { public static final class Innermost { "
            + "public int deepest() { return ca.uwaterloo.flix.api.Third.three(); } } } }");
        write(source, "fixture/OuterSibling.java", "package fixture; public final class OuterSibling { "
            + "public int sibling() { return ca.uwaterloo.flix.api.Flix.class.hashCode(); } "
            + "public int stray() { return ca.uwaterloo.flix.api.Stray.only(); } }");
        Path classes = work.resolve("nested-classes");
        Files.createDirectories(classes);
        try (var paths = Files.walk(source)) {
            List<String> command = new java.util.ArrayList<>(List.of("-d", classes.toString()));
            paths.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(command::add);
            require(ToolProvider.findFirst("javac").orElseThrow()
                .run(System.out, System.err, command.toArray(String[]::new)) == 0, "nested fixture compiles");
        }
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            List<String> references = AdapterAbi.contract("fixture.Outer", loader).references().stream()
                .map(AdapterAbi.Reference::display).toList();
            require(references.contains("ca.uwaterloo.flix.api.Flix.one:()I"),
                "the contract has a reference of the adapter class itself");
            require(references.contains("ca.uwaterloo.flix.api.Other.two:()I"),
                "the contract has a reference that only a nested class makes");
            require(references.contains("ca.uwaterloo.flix.api.Third.three:()I"),
                "the contract has a reference that only a doubly nested class makes");
            require(references.contains("class ca.uwaterloo.flix.api.Other"),
                "the contract has the class referenced from a nested class");
            require(references.stream().noneMatch(r -> r.contains("Stray")),
                "a class that only shares the adapter's name prefix, though the adapter refers to it, is not part of the contract");
            AdapterAbi.Contract sibling = AdapterAbi.contract("fixture.OuterSibling", loader);
            require(sibling.references().stream().map(AdapterAbi.Reference::display)
                    .anyMatch(r -> r.equals("ca.uwaterloo.flix.api.Stray.only:()I")),
                "a sibling has its own contract");
            try {
                AdapterAbi.contract("fixture.Absent", loader);
                throw new AssertionError("an absent adapter class is accepted");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains("missing adapter class resource"),
                    "an absent adapter class names the resource it could not read");
            }
        }
    }

    /**
     * A field and a constructor are matched by descriptor as well as by name: a compiler that has
     * a member of that name with another type does not satisfy the contract.
     */
    private static void descriptors(Path work) throws IOException {
        Path exact = work.resolve("descriptor-exact");
        Path other = work.resolve("descriptor-other");
        String type = "package ca.uwaterloo.flix.api; public final class Flix { ";
        write(exact, "ca/uwaterloo/flix/api/Flix.java",
            type + "public static int level; public Flix(int a) { } }");
        write(other, "ca/uwaterloo/flix/api/Flix.java",
            type + "public static long level; public Flix(long a) { } }");
        write(work.resolve("descriptor-caller"), "fixture/Caller.java", "package fixture; public final class Caller { "
            + "public Object make() { ca.uwaterloo.flix.api.Flix.level = 1; "
            + "return new ca.uwaterloo.flix.api.Flix(2); } }");
        Path exactClasses = work.resolve("descriptor-exact-classes");
        Path otherClasses = work.resolve("descriptor-other-classes");
        Path callerClasses = work.resolve("descriptor-caller-classes");
        for (Path dir : List.of(exactClasses, otherClasses, callerClasses)) Files.createDirectories(dir);
        compile(exactClasses, null, exact.resolve("ca/uwaterloo/flix/api/Flix.java"));
        compile(otherClasses, null, other.resolve("ca/uwaterloo/flix/api/Flix.java"));
        compile(callerClasses, exactClasses, work.resolve("descriptor-caller/fixture/Caller.java"));
        try (java.net.URLClassLoader callerLoader = new java.net.URLClassLoader(
                new java.net.URL[] {callerClasses.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             java.net.URLClassLoader exactLoader = new java.net.URLClassLoader(
                new java.net.URL[] {exactClasses.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             java.net.URLClassLoader otherLoader = new java.net.URLClassLoader(
                new java.net.URL[] {otherClasses.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            AdapterAbi.Contract contract = AdapterAbi.contract("fixture.Caller", callerLoader);
            List<String> references = contract.references().stream().map(AdapterAbi.Reference::display).toList();
            require(references.contains("ca.uwaterloo.flix.api.Flix.level:I")
                    && references.contains("ca.uwaterloo.flix.api.Flix.<init>:(I)V"),
                "the fixture contract has a field and a constructor with their descriptors");
            require(AdapterAbi.missing(contract, exactLoader).isEmpty(),
                "a compiler with the same field and constructor descriptors satisfies the contract");
            List<String> missing = AdapterAbi.missing(contract, otherLoader);
            require(missing.contains("ca.uwaterloo.flix.api.Flix.level:I"),
                "a field of the same name with another type is missing");
            require(missing.contains("ca.uwaterloo.flix.api.Flix.<init>:(I)V"),
                "a constructor with other parameter types is missing");
        }
    }

    private static void compile(Path output, Path classPath, Path source) {
        List<String> command = new java.util.ArrayList<>(List.of("-d", output.toString()));
        if (classPath != null) command.addAll(List.of("-cp", classPath.toString()));
        command.add(source.toString());
        require(ToolProvider.findFirst("javac").orElseThrow()
            .run(System.out, System.err, command.toArray(String[]::new)) == 0, "descriptor fixture compiles");
    }

    /** Inspection needs a loader now; the gate never initialises through it. */
    private static CompilerCapabilities inspect(Path jar) throws IOException {
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return CompilerCapabilities.inspect(loader, jar);
        }
    }

    private static List<String> missing(Path jar, AdapterAbi.Contract contract) throws IOException {
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return AdapterAbi.missing(contract, loader);
        }
    }

    private static void write(Path base, String relative, String source) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static void jar(Path output, Path contents) throws IOException, InterruptedException {
        Files.createDirectories(contents);
        int status = new ProcessBuilder("jar", "--create", "--file", output.toString(), "-C",
            contents.toString(), ".").inheritIO().start().waitFor();
        require(status == 0, "fixture jar succeeds");
    }

    private static int passed;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
