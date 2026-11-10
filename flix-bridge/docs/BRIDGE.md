# flix-bridge

Adapters between a plugin and an unchanged Flix compiler, the ABI check driver and the version
data. Package `dev.flixw.sdk.bridge`; depends on `kit`; contains no compiler code.

## Version data

`flix-bridge/resources/dev/flixw/sdk/bridge/versions.json` is the one place a release is named: version,
SHA-256 of the compiler jar, status (`verified` or `rejected`), adapter family and test fixture;
per family its adapter class, the oldest release it is compiled against (`floor`), the release its
test runs against (`testVersion`) and its capabilities. Everything else derives from it:

- `VersionData` gives the verified list, the single warning line for an unverified compiler and
  the Flix version (`FLIXW_COMPILER_VERSION`, else the jar file name).
- `Adapters` builds its registry, newest family first, from the family list.
- `scripts/fetch-flix.sh` fetches the jars and refuses any digest that differs.
- The build, the conformance suite and the compatibility matrix check read the same file.

The jars are compile and test inputs in `flix-bridge/lib`. They are not committed, bundled or published.

## Selection and cache fields

`Adapters.select()` returns the first family whose bytecode-derived contract links against the
compiler visible to the bridge's class loader, with `cacheFields(family)`: `sdkVersion` and `adapterFamily`. A plugin
adds both to its cache key as explicit fields.

## Capabilities

Chartroom note "Plugins share one SDK" (Q3/Q4) asks for one class per capability and Flix family
with a model per capability. Only the Metrics capability exists, so the current reading is: the
interface `CompilerModel` is the Metrics capability and one class per family (`Flix0761Adapter`
and so on) implements it. `Capability` names Metrics, ProgramLoader, TestRunner and SolverHook; a
further capability is a new interface and one class per family, with nothing renamed or removed.
Capability selection and skipped-adapter headers follow in
[flixw-metrics#23](https://github.com/wstein/flixw-metrics/issues/23).

## Checks

`sh scripts/fetch-flix.sh`, then `sh scripts/check.sh`: kit tests, example shell, version-data and
fetch tests, layout and matrix guards, `./mill flix-bridge.test.check` (unit tests, ABI test, per-family
conformance, rejected releases, adapter tests) and `scripts/check-package.sh` (packaged example plugin).
