# flixw-compiler-sdk

Shared library for the flixw plugins `flixw-metrics` and `flixw-mutate`.
Each plugin is one JAR and embeds a fixed SDK version; plugins do not depend on each other at run time.

## Status

Design phase, no code yet. The implementation follows in
[flixw-metrics#21](https://github.com/wstein/flixw-metrics/issues/21) to
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
