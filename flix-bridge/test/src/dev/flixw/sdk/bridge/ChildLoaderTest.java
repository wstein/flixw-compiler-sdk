package dev.flixw.sdk.bridge;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The bridge selects an adapter against the compiler of the loader it is loaded by, not against
 * the system class path.
 *
 * <p>Run with a system class path that holds only an old compiler and no bridge. {@code args[0]}
 * is the class path of a plugin loader (bridge, adapters, kit and a newer compiler), {@code args[1]}
 * the family expected for it. The probe reaches the bridge only through reflection, so nothing here
 * links against the bridge of the system loader.
 */
public final class ChildLoaderTest {
    private ChildLoaderTest() { }

    public static void main(String[] args) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : args[0].split(File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> adapters = Class.forName("dev.flixw.sdk.bridge.Adapters", true, loader);
            Object selection = adapters.getMethod("select").invoke(null);
            if (selection == null) throw new AssertionError("no adapter links in the child loader");
            Method family = selection.getClass().getMethod("family");
            Method model = selection.getClass().getMethod("model");
            Object chosen = family.invoke(selection);
            if (!args[1].equals(chosen))
                throw new AssertionError("the child loader sees the newer compiler: expected " + args[1]
                    + ", selected " + chosen);
            if (model.invoke(selection).getClass().getClassLoader() != loader)
                throw new AssertionError("the adapter is loaded by the loader that was checked");
        }
        System.out.println("ChildLoaderTest: 3 passed, 0 failed");
    }
}
