package dev.flixw.sdk.kit;

/** Shared shell codes. The plugin decides when its policy produces a finding. */
public final class ExitCodes {
    public static final int SUCCESS = 0;
    public static final int POLICY_FINDING = 1;
    public static final int ERROR = 2;
    private ExitCodes() { }
}
