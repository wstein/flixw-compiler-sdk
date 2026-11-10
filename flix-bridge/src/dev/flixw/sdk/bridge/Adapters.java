package dev.flixw.sdk.bridge;

import java.util.List;
import java.util.Map;

/**
 * Finds the adapter that links against the compiler on this class path.
 *
 * <p>Selection is by <em>linkage</em>, not by version string. The bytecode-derived ABI contract is
 * checked before an adapter is instantiated, because the JVM is allowed to resolve method
 * references lazily. A fork reporting an unfamiliar version may still link; a compiler reporting
 * a familiar one may not. The JVM knows, and it is cheaper to ask it than to maintain a table of
 * which versions are secretly compatible.
 *
 * <p>Loaded by name so that nothing outside this method mentions an adapter class. The phase
 * that runs without a compiler on its class path can hold a reference to {@link CompilerModel}
 * all day; it never loads anything that would need Scala or Flix to resolve.
 */
public final class Adapters {
    private Adapters() { }

    /**
     * Newest first, since a newer adapter is the better answer when two both link.
     *
     * <p>The registry is the family list of {@code versions.json}. Supporting another Flix
     * generation is a class and a family entry there -- and explicitly not an edit to the report,
     * the smells, the formats or the cache, which is the property the SDK exists to buy.
     */
    private static final List<VersionData.Family> FAMILIES = VersionData.load().families();

    private static final List<String> KNOWN = FAMILIES.stream().map(VersionData.Family::adapter).toList();

    /**
     * An adapter that links, with the explicit fields a plugin's cache key takes from the bridge.
     *
     * @param family the adapter family id from {@code versions.json}, such as {@code flix0761}
     */
    public record Selection(CompilerModel model, String family, String adapterClass,
                            Map<String, String> cacheFields) { }

    /** The first adapter that links, or null when none does. */
    public static CompilerModel resolve() {
        Selection selection = select();
        return selection == null ? null : selection.model();
    }

    /**
     * The first adapter that links against the class path, with its family; null when none does.
     *
     * <p>There is deliberately no overload taking another class loader: an adapter is loaded by
     * this class's own loader, so it links against the compiler that loader sees. Checking the
     * contract against a different compiler would select an adapter that then fails to bind.
     */
    public static Selection select() {
        ClassLoader compiler = ClassLoader.getSystemClassLoader();
        VersionData data = VersionData.load();
        for (VersionData.Family family : FAMILIES) {
            String name = family.adapter();
            try {
                if (!AdapterAbi.missing(AdapterAbi.contract(name), compiler).isEmpty()) continue;
                Class<?> type = Class.forName(name);
                Object instance = type.getDeclaredConstructor().newInstance();
                if (instance instanceof CompilerModel model)
                    return new Selection(model, family.id(), name, data.cacheFields(family.id()));
            } catch (ReflectiveOperationException | LinkageError e) {
                // Exactly the case this loop is for: the adapter cannot bind to this compiler.
                // LinkageError is the one that matters and is not an Exception, which is why it
                // is named -- NoClassDefFoundError from a missing AST type arrives as an Error.
            }
        }
        return null;
    }

    /** What this build could support, for a diagnostic that names something actionable. */
    public static List<String> known() {
        return KNOWN;
    }
}
