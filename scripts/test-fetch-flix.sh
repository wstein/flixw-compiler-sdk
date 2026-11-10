#!/bin/sh
# Offline test of scripts/fetch-flix.sh with a throwaway data file and local file:// sources.
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/sdk-fetch-test.XXXXXX")
trap 'rm -rf "$work"' EXIT
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi
mkdir -p "$work/src/v1.0.0" "$work/src/v2.0.0" "$work/cache"
printf 'good one' > "$work/src/v1.0.0/flix.jar"
printf 'bad two' > "$work/src/v2.0.0/flix.jar"
good=$($sum "$work/src/v1.0.0/flix.jar" | cut -d' ' -f1)
printf '{"schema":1,"families":[],"releases":[{"version":"1.0.0","sha256":"%s"},{"version":"2.0.0","sha256":"%s"}]}' \
  "$good" "$(printf 'other' | $sum | cut -d' ' -f1)" > "$work/data.json"
run() {
  FLIXW_VERSIONS_JSON=$work/data.json FLIXW_BRIDGE_LIB=$work/lib FLIXW_FLIX_BASE_URL=file://$work/src \
    FLIXW_BRIDGE_JAR_CACHE=${CACHE-} sh "$root/scripts/fetch-flix.sh" "$@" 2>"$work/err"
}
fail() { echo "test-fetch-flix: $1" >&2; cat "$work/err" >&2; exit 1; }

run 1.0.0 || fail "a jar with the listed digest was refused"
[ -f "$work/lib/flix-1.0.0.jar" ] || fail "the verified jar was not placed"

status=0; run 2.0.0 || status=$?
[ "$status" = 1 ] || fail "a digest mismatch did not exit 1 (got $status)"
grep -q 'digest mismatch for Flix 2.0.0' "$work/err" || fail "the mismatch was not named"
[ ! -e "$work/lib/flix-2.0.0.jar" ] && [ ! -e "$work/lib/flix-2.0.0.jar.part" ] \
  || fail "a mismatching jar was kept"

printf 'tampered' > "$work/lib/flix-1.0.0.jar"
run 1.0.0 || fail "a tampered jar was not replaced"
[ "$($sum "$work/lib/flix-1.0.0.jar" | cut -d' ' -f1)" = "$good" ] || fail "the tampered jar stayed"

rm "$work/lib/flix-1.0.0.jar"
cp "$work/src/v1.0.0/flix.jar" "$work/cache/flix-1.0.0.jar"
rm -rf "$work/src/v1.0.0"
CACHE=$work/cache run 1.0.0 || fail "the local cache was not used"

printf 'tampered' > "$work/cache/flix-1.0.0.jar"
rm "$work/lib/flix-1.0.0.jar"
status=0; CACHE=$work/cache run 1.0.0 || status=$?
[ "$status" = 1 ] || fail "a tampered cache entry was accepted (got $status)"

status=0; run 9.9.9 || status=$?
[ "$status" = 1 ] || fail "an unlisted version did not exit 1 (got $status)"
grep -q '9.9.9 is not in' "$work/err" || fail "the unlisted version was not named"
echo 'test-fetch-flix: 7 passed, 0 failed'
