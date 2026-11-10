import dev.flixw.sdk.bridge.Adapters;
import dev.flixw.sdk.bridge.CompilerCapabilities;
import dev.flixw.sdk.bridge.CompilerModel;
import dev.flixw.sdk.bridge.VersionData;
import dev.flixw.sdk.kit.Context;
import dev.flixw.sdk.kit.ExitCodes;
import dev.flixw.sdk.kit.Launcher;
import dev.flixw.sdk.kit.Usage;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * A two-phase example plugin for the packaged check. The outer phase has no compiler on its class
 * path: it inspects the compiler jar and warns about an unverified release. The bridge phase runs
 * with the compiler: it selects the adapter by linkage and prints what it measured.
 */
public final class BridgeExample {
    private BridgeExample() { }

    public static void main(String[] args) throws Exception {
        try {
            Context context = Context.read();
            if (args.length > 0 && args[0].equals("--bridge")) {
                Adapters.Selection selection = Adapters.select();
                if (selection == null) throw new Usage("no adapter links against this compiler");
                CompilerModel.Model model = selection.model().measure(context.projectRoot());
                System.out.println("family=" + selection.family() + " cache=" + selection.cacheFields()
                    + " defs=" + model.defs().size());
                System.exit(ExitCodes.SUCCESS);
            }
            VersionData.flixVersion(System.getenv(), context.compilerJar()).flatMap(
                version -> VersionData.load().warningFor("example", version)).ifPresent(System.err::println);
            try (URLClassLoader loader = new URLClassLoader(new URL[] {context.compilerJar().toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                CompilerCapabilities found = CompilerCapabilities.inspect(loader, context.compilerJar());
                if (!found.hasEngineApi())
                    throw new Usage("the compiler jar is not supported; missing " + found.missing());
            }
            Path artifact = Path.of(BridgeExample.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            System.exit(Launcher.spawnBridge(context, artifact, BridgeExample.class.getName(), args));
        } catch (Usage failure) {
            System.err.println(failure.getMessage());
            System.exit(ExitCodes.ERROR);
        }
    }
}
