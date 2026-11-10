package dev.flixw.sdk.kit;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** An inspection loader whose parent cannot expose plugin application classes. */
public final class InspectionLoader {
    private InspectionLoader() { }
    public static URLClassLoader open(Path compilerJar) {
        try {
            return new URLClassLoader("flix-inspect", new URL[] {compilerJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        } catch (MalformedURLException e) {
            throw new Usage("cannot open the compiler jar " + compilerJar + ": " + e.getMessage());
        }
    }
}
