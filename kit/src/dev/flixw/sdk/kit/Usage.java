package dev.flixw.sdk.kit;

/** A shell or context error; the plugin maps it to {@link ExitCodes#ERROR}. */
public final class Usage extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public Usage(String message) { super(message); }
}
