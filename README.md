# flixw-plugin-sdk

Shared library for the flixw plugins `flixw-metrics` and `flixw-mutate`.
Each plugin is one JAR and embeds a fixed SDK version; plugins do not depend on each other at run time.

## Status

The Java 21 `kit` module is available as source. The compiler bridge follows in
[flixw-metrics#22](https://github.com/wstein/flixw-metrics/issues/22) and
[#23](https://github.com/wstein/flixw-metrics/issues/23).

## Modules

- `kit` is the plugin shell. It reads the flixw context, launches the plugin, stores cache entries,
  collects provenance and reports, and defines the shared exit codes. It has no knowledge of Flix.
  Package: `dev.flixw.sdk.kit`.
- `flix-bridge` holds the adapters between the plugins and unchanged Flix compilers, the ABI check
  driver, the version data (`versions.json`, with a digest per compiler jar) and the stable model.
  It depends on `kit`. It contains no compiler code. Package: `dev.flixw.sdk.bridge`.

Rules, smells, operators, engines and report formats stay in the plugins.

## Constraint

Everything runs against unchanged stock Flix compilers. The owner has no access to the Flix
upstream repository, and upstream changes are not expected. The per-capability linkage check is the
protection against compiler changes.

## Sources

- Issues: [flixw-metrics#20](https://github.com/wstein/flixw-metrics/issues/20) to
  [#23](https://github.com/wstein/flixw-metrics/issues/23),
  [flixw-mutate#1](https://github.com/wstein/flixw-mutate/issues/1) and
  [#2](https://github.com/wstein/flixw-mutate/issues/2).
- Design: [`docs/design/SDK.md`](https://github.com/wstein/flixw-metrics/blob/main/docs/design/SDK.md) in `wstein/flixw-metrics`.

## License

Apache-2.0, see `LICENSE`.

## Kit development and embedding

Java 21 is required. Run `sh scripts/check.sh` for the dependency-free public API tests;
`./mill --no-server kit.test.check` is the underlying Mill command. Mill wrapper and version
1.3.1 are copied unchanged from `wstein/flixw-metrics` at
`7d8c919d021364fb4dc9c452f676324e0ae115c2`; the extraction does not upgrade the toolchain.
Targeted mutations are opt-in: `sh scripts/check.sh --mutations`, or
`MUT_ONLY=abi sh scripts/check.sh --mutations` for one case. The Python standard-library
script only copies, edits and runs the Java contract tests.

The public API is in `dev.flixw.sdk.kit`: `Context`, `Usage`, `Launcher`,
`InspectionLoader`, `AtomicOutput`, `KeyedStore`, `ProvenanceData`, and `ExitCodes`.
The caller supplies its plugin artifact and main class to the launcher, its deterministic
source list and artifact to the cache, and its opaque cache payload. `ProvenanceData`
collects data only; plugins own JSON and other rendering. Optional cache absence and cache
I/O failures disable caching. Exit codes are success 0, plugin policy finding 1, and
shell/compiler error 2; plugins retain their own exception and policy boundaries.

`examples/Shell.java` shows a two-phase shell. To try it after the source checks:

```sh
mkdir -p out/example
javac --release 21 -d out/example kit/src/dev/flixw/sdk/kit/*.java examples/Shell.java
FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT="$PWD" FLIXW_COMPILER_JAR="$PWD/compiler.jar" \
  FLIXW_JAVA_HOME="$JAVA_HOME" java -cp out/example Shell
```

The example only demonstrates the shell; it does not load compiler classes. When embedding
compiled kit classes or resources in a plugin JAR, include
`kit/resources/META-INF/LICENSE-flixw-plugin-sdk` in that JAR. The kit has no Flix, Scala,
Metrics or third-party Java dependency. ABI inspection, adapters, capability selection,
extra headers and cache-key fields remain outside this extraction.
