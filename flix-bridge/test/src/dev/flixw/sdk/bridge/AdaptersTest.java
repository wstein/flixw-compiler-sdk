package dev.flixw.sdk.bridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Checks the public shape of {@link Adapters}; runs without a compiler on the class path. */
public final class AdaptersTest {
    private static int passed;

    private AdaptersTest() { }

    public static void main(String[] args) {
        // An adapter is loaded by the bridge's own loader, so the compiler it links against is the
        // one that loader sees. A selector that takes some other loader would check one compiler's
        // contract and instantiate an adapter against another (NoSuchMethodError at measure()).
        for (Method method : Adapters.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            require(Arrays.stream(method.getParameterTypes()).noneMatch(ClassLoader.class::isAssignableFrom),
                "Adapters." + method.getName() + " takes no ClassLoader: adapters link against the class path");
        }
        require(Arrays.stream(Adapters.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("select") && method.getParameterCount() == 0
                    && Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())),
            "Adapters.select() is public static API");
        System.out.println("AdaptersTest: " + passed + " passed, 0 failed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}
