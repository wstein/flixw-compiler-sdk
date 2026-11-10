#!/bin/sh
# Packaged check: build the kit and flix-bridge jars, embed them in a small example plugin jar as a
# plugin would, and run it the way flixw does against real compiler jars from flix-bridge/lib
# (sh scripts/fetch-flix.sh first). Also checks what the jars contain and that they are reproducible.
set -eu
umask 022
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?set JAVA_HOME to Java 21}"
lib=flix-bridge/lib
out=package-check
rm -rf "$out"
mkdir -p "$out/classes" "$out/unpacked"
fail() { echo "check-package: $1" >&2; exit 1; }

build() {
  ./mill --no-server kit.jar + flix-bridge.jar >/dev/null
  python3 -I scripts/normalize-jar.py out/kit/jar.dest/out.jar "$out/kit-$1.jar"
  python3 -I scripts/normalize-jar.py out/flix-bridge/jar.dest/out.jar "$out/bridge-$1.jar"
}
build 1
# Reproducibility: a build from nothing gives the same bytes once entry order and timestamps are fixed.
./mill --no-server clean >/dev/null
build 2
cmp -s "$out/kit-1.jar" "$out/kit-2.jar" || fail "kit.jar is not reproducible"
cmp -s "$out/bridge-1.jar" "$out/bridge-2.jar" || fail "flix-bridge.jar is not reproducible"
cp "$out/kit-1.jar" "$out/kit.jar"; cp "$out/bridge-1.jar" "$out/bridge.jar"

contents=$("$JAVA_HOME/bin/jar" tf "$out/bridge.jar")
for entry in dev/flixw/sdk/bridge/versions.json META-INF/LICENSE-flixw-plugin-sdk \
    dev/flixw/sdk/bridge/Adapters.class dev/flixw/sdk/bridge/adapter/Flix0600Adapter.class \
    dev/flixw/sdk/bridge/adapter/Flix0761Adapter.class; do
  printf '%s\n' "$contents" | grep -qx "$entry" || fail "flix-bridge.jar lacks $entry"
done
for adapter in 0600 0610 0661 0672 0680 0753 0761; do
  printf '%s\n' "$contents" | grep -qx "dev/flixw/sdk/bridge/adapter/Flix${adapter}Adapter.class" \
    || fail "flix-bridge.jar lacks the Flix$adapter adapter"
done
printf '%s\n' "$contents" | grep -q '^ca/uwaterloo/' && fail "flix-bridge.jar contains compiler classes"
printf '%s\n' "$contents" | grep -q '^scala/' && fail "flix-bridge.jar contains the Scala library"
printf '%s\n' "$contents" | grep -q '\.jar$' && fail "flix-bridge.jar contains a jar"
cmp -s kit/resources/META-INF/LICENSE-flixw-plugin-sdk flix-bridge/resources/META-INF/LICENSE-flixw-plugin-sdk \
  || fail "the bridge license differs from the kit license"
"$JAVA_HOME/bin/jar" tf "$out/kit.jar" | grep -qx 'dev/flixw/sdk/bridge/Adapters.class' \
  && fail "kit.jar contains bridge classes"

(cd "$out/unpacked" && "$JAVA_HOME/bin/jar" xf "../kit.jar" && "$JAVA_HOME/bin/jar" xf "../bridge.jar")
"$JAVA_HOME/bin/javac" --release 21 -cp "$out/unpacked" -d "$out/classes" examples/BridgeExample.java
cp -R "$out/unpacked/." "$out/classes/"
"$JAVA_HOME/bin/jar" --create --file "$out/example-plugin.raw.jar" --main-class BridgeExample \
  -C "$out/classes" .
python3 -I scripts/normalize-jar.py "$out/example-plugin.raw.jar" "$out/example-plugin.jar"

run() {
  version=$1
  project=$PWD/$out/project-$version  # absolute, as flixw passes it
  rm -rf "$project"; cp -R flix-bridge/test/fixtures/$2 "$project"
  case $version in
    0.78.*|0.79.*) # these releases reject the package keys that older releases require
      grep -v -E '^(name|description|authors|license|modules)[[:space:]]*=' "$project/flix.toml" >"$project/flix.toml.new"
      mv "$project/flix.toml.new" "$project/flix.toml" ;;
  esac
  FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT=$project FLIXW_COMPILER_JAR=$PWD/$lib/flix-$version.jar \
    FLIXW_COMPILER_VERSION=$version FLIXW_JAVA_HOME=$JAVA_HOME \
    "$JAVA_HOME/bin/java" -jar "$out/example-plugin.jar" >"$out/$version.out" 2>"$out/$version.err"
}
expect() {
  version=$1 fixture=$2 family=$3
  run "$version" "$fixture" || fail "example plugin failed on Flix $version"
  grep -q "family=$family cache={.*} defs=6$" "$out/$version.out" \
    && grep -q "sdkVersion=1" "$out/$version.out" && grep -q "adapterFamily=$family" "$out/$version.out" \
    || { cat "$out/$version.out" >&2; fail "Flix $version did not select $family with the cache fields"; }
  ! grep -q 'warning:' "$out/$version.err" || fail "verified Flix $version drew a warning: $(grep 'warning:' "$out/$version.err")"
}
expect 0.60.0 semantic-validation flix0600
expect 0.68.0 semantic flix0680
expect 0.76.0 semantic flix0753
expect 0.79.0 semantic flix0761
# An unverified release is measured but noticed once, with the plugin's name.
cp "$lib/flix-0.77.0.jar" "$out/flix-0.80.0.jar"
status=0; FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT=$PWD/$out/project-0.79.0 FLIXW_COMPILER_JAR=$PWD/$out/flix-0.80.0.jar \
  FLIXW_COMPILER_VERSION=0.80.0 FLIXW_JAVA_HOME=$JAVA_HOME "$JAVA_HOME/bin/java" -jar "$out/example-plugin.jar" \
  >"$out/0.80.0.out" 2>"$out/0.80.0.err" || status=$?
[ "$status" = 0 ] || fail "an unlisted release was refused (exit $status)"
grep -q 'example: warning: Flix 0.80.0 has no compatibility checkpoint' "$out/0.80.0.err" || fail "an unlisted release drew no warning"
for version in 0.59.0 0.66.0; do
  status=0; run "$version" semantic || status=$?
  [ "$status" = 2 ] || fail "rejected Flix $version exited $status, not 2"
  grep -q 'is not supported; missing' "$out/$version.err" || fail "rejected Flix $version was not explained"
done
echo 'check-package: ok (4 verified releases, 1 unlisted, 2 rejected; jars reproducible)'
