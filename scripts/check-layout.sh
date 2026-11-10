#!/bin/sh
# Source, layout, license and matrix guards. Needs git and jq; reads only tracked files.
set -eu
umask 022
cd "$(dirname "$0")/.."
data=flix-bridge/resources/dev/flixw/sdk/bridge/versions.json
fail() { echo "check-layout: $1" >&2; exit 1; }
tracked() { git ls-files -- "$@"; }

# kit stays compiler-neutral: no bridge, Flix, Scala or plugin references.
for pattern in 'dev\.flixw\.sdk\.bridge' 'ca\.uwaterloo' 'scala\.' 'dev\.flixw\.metrics'; do
  tracked kit | grep -E '\.(java|mill)$' | xargs grep -l -E "$pattern" 2>/dev/null \
    && fail "kit references $pattern"
done
# the bridge Java code holds no compiler code and nothing of a plugin; Scala only in adapter modules.
tracked flix-bridge/src flix-bridge/test/src | xargs grep -n -E 'import ca\.uwaterloo|dev\.flixw\.metrics' 2>/dev/null \
  | grep -v 'dev\.flix\.runtime' && fail "flix-bridge Java sources reference compiler classes or a plugin" || true
tracked | grep -E '\.scala$' | grep -v '^flix-bridge/adapter/flix[0-9]*/src/dev/flixw/sdk/bridge/adapter/' \
  && fail "Scala outside the adapter modules"
# one adapter module per family in versions.json, and nothing else.
for family in $(jq -r '.families[].id' "$data"); do
  class=Flix$(printf '%s' "$family" | cut -c5-)Adapter
  [ -f "flix-bridge/adapter/$family/src/dev/flixw/sdk/bridge/adapter/$class.scala" ] \
    || fail "family $family has no $class.scala"
  [ "$(jq -r --arg f "$family" '.families[] | select(.id == $f) | .adapter' "$data")" = "dev.flixw.sdk.bridge.adapter.$class" ] \
    || fail "versions.json names another adapter class for $family"
  [ -f "flix-bridge/test/src/dev/flixw/sdk/bridge/${class}Test.java" ] || fail "family $family has no ${class}Test"
done
[ "$(tracked flix-bridge/adapter | grep -c '\.scala$')" = "$(jq '.families | length' "$data")" ] \
  || fail "an adapter module is not listed in versions.json"
# no compiler or other jar is committed.
tracked | grep -E '\.(jar|class)$' && fail "a jar or class file is tracked"
# license: the same text in the repository root and in both artifacts' resources.
cmp -s LICENSE kit/resources/META-INF/LICENSE-flixw-plugin-sdk || fail "kit license differs from LICENSE"
cmp -s LICENSE flix-bridge/resources/META-INF/LICENSE-flixw-plugin-sdk || fail "bridge license differs from LICENSE"
grep -q 'Apache License' LICENSE || fail "LICENSE is not Apache-2.0"
# versions.json: sorted, unique, digest-shaped, every verified release recorded.
jq -e '[.releases[].version] | (. == (map(split(".") | map(tonumber)) | sort_by(.) | map(join("."))))' "$data" >/dev/null \
  || fail "releases are not sorted oldest first"
jq -e '[.releases[].sha256] | all(test("^[0-9a-f]{64}$")) and (unique | length) == length' "$data" >/dev/null \
  || fail "digests are malformed or repeated"
readme=flix-bridge/docs/compatibility/README.md
# The release matrix: the first table lists exactly the releases of versions.json with their adapter classes.
expected=$(jq -r '.releases[] | select(.status == "verified") | "| \(.version) | `Flix\(.family[4:])Adapter` |"' "$data")
actual=$(sed -n '/^| Flix | Adapter family | Source build/,/^$/p' "$readme" | grep '^| 0\.' | cut -d'|' -f1-3 | sed 's/ *$/ |/')
[ "$expected" = "$actual" ] || { printf 'expected:\n%s\nactual:\n%s\n' "$expected" "$actual" >&2; fail "the compatibility matrix differs from versions.json"; }
for version in $(jq -r '.releases[] | select(.status == "verified") | .version' "$data"); do
  grep -q "^| $version | " "$readme" || fail "no matrix row for $version"
done
echo "check-layout: ok ($(jq '.families | length' "$data") families, $(jq '[.releases[] | select(.status == "verified")] | length' "$data") verified releases)"
