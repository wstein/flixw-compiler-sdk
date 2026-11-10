#!/bin/sh
# Put the Flix compiler jars named in versions.json into flix-bridge/lib and check every digest.
#
# Usage: sh scripts/fetch-flix.sh [version ...]   (default: every release in the data file)
# Environment (all optional):
#   FLIXW_VERSIONS_JSON    data file, default flix-bridge/resources/dev/flixw/sdk/bridge/versions.json
#   FLIXW_BRIDGE_LIB       target directory, default flix-bridge/lib (ignored by git)
#   FLIXW_BRIDGE_JAR_CACHE directory of earlier downloads named flix-<version>.jar; read only,
#                          copied after its digest matches
#   FLIXW_FLIX_BASE_URL    default https://github.com/flix/flix/releases/download
#
# The jars are compile and test inputs. They are never committed, never bundled and never
# published. A jar whose digest differs from the data file is refused, wherever it came from.
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
data=${FLIXW_VERSIONS_JSON:-$root/flix-bridge/resources/dev/flixw/sdk/bridge/versions.json}
lib=${FLIXW_BRIDGE_LIB:-$root/flix-bridge/lib}
cache=${FLIXW_BRIDGE_JAR_CACHE:-}
base=${FLIXW_FLIX_BASE_URL:-https://github.com/flix/flix/releases/download}

command -v jq >/dev/null 2>&1 || { echo "fetch-flix: jq is required" >&2; exit 2; }
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi
digest() {
  # shellcheck disable=SC2086  # $sum is a command plus flags, deliberately split
  $sum "$1" | cut -d' ' -f1
}

if [ "$#" -gt 0 ]; then versions=$*; else versions=$(jq -r '.releases[].version' "$data"); fi
mkdir -p "$lib"
for version in $versions; do
  expected=$(jq -r --arg v "$version" '.releases[] | select(.version == $v) | .sha256' "$data")
  [ -n "$expected" ] || { echo "fetch-flix: $version is not in $data" >&2; exit 1; }
  jar=$lib/flix-$version.jar
  if [ -f "$jar" ]; then
    [ "$(digest "$jar")" = "$expected" ] && continue
    echo "fetch-flix: $jar is not Flix $version; replacing it" >&2
    rm -f "$jar"
  fi
  if [ -n "$cache" ] && [ -f "$cache/flix-$version.jar" ] \
      && [ "$(digest "$cache/flix-$version.jar")" = "$expected" ]; then
    cp "$cache/flix-$version.jar" "$jar.part"
  else
    echo "fetch-flix: downloading Flix $version" >&2
    curl -fsSL --retry 3 --retry-delay 5 --connect-timeout 30 --max-time 600 -o "$jar.part" "$base/v$version/flix.jar" \
      || { rm -f "$jar.part"; echo "fetch-flix: cannot download Flix $version" >&2; exit 1; }
  fi
  got=$(digest "$jar.part")
  if [ "$got" != "$expected" ]; then
    rm -f "$jar.part"
    echo "fetch-flix: digest mismatch for Flix $version" >&2
    echo "            expected $expected" >&2
    echo "            actual   $got" >&2
    exit 1
  fi
  mv "$jar.part" "$jar"
  echo "fetch-flix: Flix $version verified" >&2
done
