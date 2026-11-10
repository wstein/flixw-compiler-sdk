#!/bin/sh
# Generate the Javadoc and Scaladoc of every module; any documentation warning fails the check.
set -eu
umask 022
cd "$(dirname "$0")/.."
log=$(mktemp "${TMPDIR:-/tmp}/sdk-docs.XXXXXX")
trap 'rm -f "$log"' EXIT
./mill --no-server clean >/dev/null 2>&1 || true
./mill --no-server kit.docJar + flix-bridge.docJar + 'flix-bridge.adapter.__.docJar' >"$log" 2>&1 \
  || { cat "$log" >&2; echo "check-docs: documentation generation failed" >&2; exit 1; }
# Javadoc's missing-tag lints (no @param, no @return, no comment) are not counted; broken links,
# unresolved references and every Scaladoc warning are.
problems=$(grep -E 'warning|error' "$log" | grep -v -E 'warning: no (@param|@return|@throws|comment)|[0-9]+ warnings?$' || true)
if [ -n "$problems" ]; then
  printf '%s\n' "$problems" >&2
  echo "check-docs: documentation warnings" >&2
  exit 1
fi
echo "check-docs: ok (kit, flix-bridge, 7 adapter modules)"
