package dev.flixw.sdk.bridge;

import java.util.Arrays;
import java.util.Optional;

/**
 * What an adapter family can do for a plugin. A family implements one class per capability;
 * {@code versions.json} lists the ids a family provides.
 *
 * <p>Only {@link #METRICS} has an implementation today: {@link CompilerModel} is its interface and
 * every {@code Flix*Adapter} implements it. The others are named so that adding one is additive
 * (a new interface and one class per family), never a rename.
 */
public enum Capability {
    METRICS("metrics"),
    PROGRAM_LOADER("programLoader"),
    TEST_RUNNER("testRunner"),
    SOLVER_HOOK("solverHook");

    private final String id;

    Capability(String id) {
        this.id = id;
    }

    /** The spelling used in {@code versions.json}. */
    public String id() {
        return id;
    }

    public static Optional<Capability> of(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }
}
