#!/usr/bin/env python3
"""Targeted opt-in mutations in isolated temporary source copies; never edit the worktree."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
cases = [
    ("abi", "Context", '!"1".equals(environment.get("FLIXW_ABI_VERSION"))', 'false', "usage accepted"),
    ("cache-optional", "Context", 'String cache = environment.get("FLIXW_PLUGIN_CACHE");', 'String cache = environment.get("FLIXW_PLUGIN_CACHE"); if (cache == null) throw new Usage("missing cache");', "missing cache"),
    ("plugin-artifact", "Launcher", 'command.add(pluginArtifact + File.pathSeparator + context.compilerJar());', 'command.add(Path.of("sdk.jar") + File.pathSeparator + context.compilerJar());', "bridge command contract"),
    ("compiler-hash", "KeyedStore", 'update(digest, "compiler", hash(context.compilerJar()));', '', "compiler bytes affect key"),
    ("source-content", "KeyedStore", 'context.projectRoot().relativize(source).toString(), hash(source)', 'context.projectRoot().relativize(source).toString(), "unchanged"', "source contents affect key"),
    ("source-path", "KeyedStore", 'context.projectRoot().relativize(source).toString(), hash(source)', '"source", hash(source)', "source paths affect key"),
    ("cache-write", "KeyedStore", '// Deliberately silent; see the javadoc above.', 'throw new java.io.UncheckedIOException(e);', "UncheckedIOException"),
    ("cache-atomic", "KeyedStore", 'Files.move(tmp, dir.resolve(key + ".measurements"), StandardCopyOption.ATOMIC_MOVE,\n                    StandardCopyOption.REPLACE_EXISTING);', 'Files.writeString(dir.resolve(key + ".measurements"), payload);', "cache atomic replacement"),
    ("atomic-output", "AtomicOutput", 'Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,\n                    StandardCopyOption.REPLACE_EXISTING);', 'Files.writeString(target, contents);', "atomic output"),
    ("git-timeout", "ProvenanceData", 'process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)', 'process.waitFor(30, TimeUnit.SECONDS)', "git timeout fallback"),
    ("bridge-stack", "Launcher", 'command.add("-Xss64m");', 'command.add("-Xss1m");', "bridge command contract"),
    ("inspection-parent", "InspectionLoader", 'ClassLoader.getPlatformClassLoader()', 'ClassLoader.getSystemClassLoader()', "inspection parent"),
    ("policy-exit", "ExitCodes", 'POLICY_FINDING = 1', 'POLICY_FINDING = 0', "exit codes"),
]
selected = os.environ.get("MUT_ONLY")
if selected and selected not in {case[0] for case in cases}:
    raise SystemExit("unknown MUT_ONLY=" + selected)
java_home = os.environ.get("JAVA_HOME")
javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
java = str(Path(java_home) / "bin/java") if java_home else "java"
passed = 0
for name, filename, before, after, reason in cases:
    if selected and name != selected:
        continue
    with tempfile.TemporaryDirectory(prefix="sdk-kit-mutation-") as directory:
        scratch = Path(directory)
        shutil.copytree(root / "kit", scratch / "kit")
        path = scratch / "kit/src/dev/flixw/sdk/kit" / (filename + ".java")
        text = path.read_text()
        if text.count(before) != 1:
            raise SystemExit(name + ": mutation target is not unique")
        path.write_text(text.replace(before, after))
        classes = scratch / "classes"
        sources = sorted((scratch / "kit").rglob("*.java"))
        subprocess.run([javac, "--release", "21", "-d", str(classes), *map(str, sources)], check=True)
        result = subprocess.run([java, "-cp", str(classes), "dev.flixw.sdk.kit.KitTest"], capture_output=True, text=True, timeout=35)
        output = result.stdout + result.stderr
        if result.returncode == 0 or reason not in output:
            raise SystemExit(name + ": did not fail for intended reason\n" + output)
        print(name + ": rejected (" + reason + ")", flush=True)
        passed += 1
print("mutations: " + str(passed) + " rejected, 0 survived")
