#!/usr/bin/env python3
"""Targeted opt-in mutations of flix-bridge and its scripts, each in a temporary copy of the repository.

Every mutation must make its command exit with the expected status and print the intended reason.
MUT_ONLY=<name> runs one. Needs the compiler jars (scripts/fetch-flix.sh); never edits the worktree.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
B = "flix-bridge/src/dev/flixw/sdk/bridge/"
DATA = "flix-bridge/resources/dev/flixw/sdk/bridge/versions.json"
UNIT = ["./mill", "--no-server", "flix-bridge.test.unit"]
cases = [
    # (name, file, before, after, command, expected status, reason in output)
    ("verified-includes-rejected", B + "VersionData.java",
     'release.status().equals("verified"))\n            .map(Release::version)', 'true)\n            .map(Release::version)',
     UNIT, 1, "fourteen verified releases"),
    ("duplicate-family", B + "VersionData.java",
     'if (!ids.add(family.id())) throw bad("duplicate family " + family.id());', 'ids.add(family.id());',
     UNIT, 1, "accepted: duplicate family"),
    ("duplicate-release", B + "VersionData.java",
     'if (!seen.add(release.version())) throw bad("duplicate release " + release.version());', 'seen.add(release.version());',
     UNIT, 1, "accepted: duplicate release"),
    ("cache-family-field", B + "VersionData.java",
     'fields.put("adapterFamily", familyId);', '',
     UNIT, 1, "explicit cache fields"),
    ("warning-newest", B + "VersionData.java",
     'return versions.stream().max(Comparator.comparing(VersionData::key)).orElse("none");',
     'return versions.stream().findFirst().orElse("none");',
     UNIT, 1, "the notice names the release, the newest verified one"),
    ("nested-classes", B + "AdapterAbi.java",
     'if (type.startsWith(adapter + "$")) pending.add(type);', '',
     UNIT, 1, "reference that only a nested class makes"),
    ("name-prefix", B + "AdapterAbi.java",
     'if (type.startsWith(adapter + "$")) pending.add(type);', 'if (type.startsWith(adapter)) pending.add(type);',
     UNIT, 1, "shares the adapter's name prefix"),
    ("descriptor", B + "AdapterAbi.java",
     '&& descriptor(method).equals(reference.descriptor())) return;', ') return;',
     UNIT, 1, "wrong JVM descriptor"),
    ("descriptor-field", B + "AdapterAbi.java",
     ' && descriptor(field.getType()).equals(reference.descriptor())) return;', ') return;',
     UNIT, 1, "a field of the same name with another type is missing"),
    ("descriptor-constructor", B + "AdapterAbi.java",
     'if (descriptor(constructor).equals(reference.descriptor())) return;', 'if (true) return;',
     UNIT, 1, "a constructor with other parameter types is missing"),
    ("runtime-class", B + "CompilerCapabilities.java",
     'if (!present(compiler, "dev.flix.runtime.Global")) {', 'if (false) {',
     UNIT, 1, "the gate requires the compiler runtime class"),
    ("select-linkage", B + "Adapters.java",
     'if (!AdapterAbi.missing(AdapterAbi.contract(name), compiler).isEmpty()) continue;', '',
     ["./mill", "--no-server", "flix-bridge.test.family", "--id", "flix0600"], 1, "the registry selects flix0600, not"),
    ("select-overload", B + "Adapters.java",
     '    /** What this build could support', '    public static Selection select(ClassLoader other) { return select(); }\n\n    /** What this build could support',
     UNIT, 1, "takes no ClassLoader"),
    ("data-family", DATA,
     '"version": "0.79.0", "sha256": "8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf", "status": "verified", "family": "flix0761"',
     '"version": "0.79.0", "sha256": "8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf", "status": "verified", "family": "flix0753"',
     ["./mill", "--no-server", "flix-bridge.test.family", "--id", "flix0753"], 1, "satisfies the flix0753 contract"),
    ("data-digest", DATA,
     '"sha256": "d8d9a3870e199c03ed6364ea9430f56f67bfd38c332c411628a6a7cb88b2b0b4"',
     '"sha256": "d8d9a3870e199c03ed6364ea9430f56f67bfd38c332c411628a6a7cb88b2b0b5"',
     ["./mill", "--no-server", "flix-bridge.test.family", "--id", "flix0753"], 1, "0.76.0: digest matches versions.json"),
    ("fetch-digest", "scripts/fetch-flix.sh",
     'if [ "$got" != "$expected" ]; then', 'if false; then',
     ["sh", "scripts/test-fetch-flix.sh"], 1, "did not exit 1"),
    ("layout-kit", "kit/src/dev/flixw/sdk/kit/ExitCodes.java",
     'package dev.flixw.sdk.kit;', 'package dev.flixw.sdk.kit;\nimport ca.uwaterloo.flix.api.Flix;',
     ["sh", "scripts/check-layout.sh"], 1, "kit references ca"),
    ("layout-matrix", "flix-bridge/docs/compatibility/README.md",
     '| 0.79.0 | `Flix0761Adapter` | no | yes | yes |', '| 0.79.0 | `Flix0753Adapter` | no | yes | yes |',
     ["sh", "scripts/check-layout.sh"], 1, "the compatibility matrix differs from versions.json"),
    ("layout-jar", "flix-bridge/test/fixtures/semantic/flix.toml",
     None, None, ["sh", "scripts/check-layout.sh"], 1, "a jar or class file is tracked"),
    ("docs-link", "flix-bridge/adapter/flix0761/src/dev/flixw/sdk/bridge/adapter/Flix0761Adapter.scala",
     '`dev.flixw.sdk.bridge.Adapters`', '[[dev.flixw.sdk.bridge.Adapters]]',
     ["sh", "scripts/check-docs.sh"], 1, "Could not find any member to link"),
    ("package-warning", "examples/BridgeExample.java",
     'VersionData.flixVersion(System.getenv(), context.compilerJar()).flatMap(', 'java.util.Optional.<String>empty().flatMap(',
     ["sh", "scripts/check-package.sh"], 1, "an unlisted release drew no warning"),
]
selected = os.environ.get("MUT_ONLY")
if selected and selected not in {case[0] for case in cases}:
    raise SystemExit("unknown MUT_ONLY=" + selected)
ignore = shutil.ignore_patterns("out", ".git", ".worktrees", "package-check", "lib")
done = 0
survivors = []
for name, filename, before, after, command, status, reason in cases:
    if selected and name != selected:
        continue
    with tempfile.TemporaryDirectory(prefix="sdk-bridge-mutation-") as directory:
        copy = Path(directory) / "repo"
        shutil.copytree(root, copy, ignore=ignore)
        (copy / "flix-bridge/lib").symlink_to(root / "flix-bridge/lib")
        subprocess.run(["git", "init", "-q"], cwd=copy, check=True)
        if before is None:  # a tracked jar: layout guard
            (copy / "flix-bridge/test/fixtures/semantic/extra.jar").write_bytes(b"PK")
        else:
            path = copy / filename
            text = path.read_text()
            if text.count(before) != 1:
                raise SystemExit(name + ": mutation target is not unique")
            path.write_text(text.replace(before, after))
        subprocess.run(["git", "add", "-A", "-f"], cwd=copy, check=True)
        result = subprocess.run(command, cwd=copy, capture_output=True, text=True, timeout=1500,
                                env={**os.environ, "TMPDIR": directory})
        output = result.stdout + result.stderr
        if result.returncode != status or reason not in output:
            print(name + ": NOT REJECTED for the intended reason: exit " + str(result.returncode)
                  + " (expected " + str(status) + "), reason missing: " + reason + "\n" + output[-1500:], flush=True)
            survivors.append(name)
            continue
        print(name + ": rejected, exit " + str(status) + " (" + reason + ")", flush=True)
        done += 1
print("mutations-bridge: " + str(done) + " rejected, " + str(len(survivors)) + " survived " + " ".join(survivors))
raise SystemExit(1 if survivors else 0)
