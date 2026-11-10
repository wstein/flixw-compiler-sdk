package dev.flixw.sdk.kit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Launches the caller's bridge on an application classpath with its selected compiler. */
public final class Launcher {
    private Launcher() { }

    public static int spawnBridge(Context context, Path pluginArtifact, String mainClass,
                                  String[] args) {
        try {
            return new ProcessBuilder(bridgeCommand(context, pluginArtifact, mainClass, args))
                .directory(context.projectRoot().toFile())
                .inheritIO().start().waitFor();
        } catch (IOException e) {
            throw new Usage("cannot start compiler bridge: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Usage("interrupted while waiting for compiler bridge");
        }
    }

    /** Caller artifact first; the SDK's own CodeSource is never substituted. */
    public static List<String> bridgeCommand(Context context, Path pluginArtifact, String mainClass,
                                             String[] args) {
        List<String> command = new ArrayList<>();
        command.add(context.java().toString());
        // Flix constraint visitors require the same worker-stack floor as the original shell.
        command.add("-Xss64m");
        command.add("-cp");
        command.add(pluginArtifact + File.pathSeparator + context.compilerJar());
        command.add(mainClass);
        command.add("--bridge");
        command.addAll(Arrays.asList(args));
        return List.copyOf(command);
    }
}
