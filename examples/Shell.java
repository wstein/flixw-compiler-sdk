import dev.flixw.sdk.kit.Context;
import dev.flixw.sdk.kit.ExitCodes;
import dev.flixw.sdk.kit.Launcher;
import dev.flixw.sdk.kit.Usage;
import java.nio.file.Path;

/** A plugin owns argument parsing, policy and error boundaries. */
public final class Shell {
    private Shell() { }
    public static void main(String[] args) throws Exception {
        try {
            Context context = Context.read();
            if (args.length > 0 && args[0].equals("--bridge")) {
                System.out.println("example bridge");
                System.exit(args.length > 1 && args[1].equals("--finding")
                    ? ExitCodes.POLICY_FINDING : ExitCodes.SUCCESS);
            }
            Path artifact = Path.of(Shell.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            System.exit(Launcher.spawnBridge(context, artifact, Shell.class.getName(), args));
        } catch (Usage failure) {
            System.err.println(failure.getMessage());
            System.exit(ExitCodes.ERROR);
        }
    }
}
