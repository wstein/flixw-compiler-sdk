#!/bin/sh
# Source checks only. Mutations are opt-in: --mutations or MUTATIONS=1.
# --kit-only skips everything that needs the Flix compiler jars (scripts/fetch-flix.sh).
set -eu
umask 022
cd "$(dirname "$0")/.."
case "${1-}" in
  --help) echo 'Usage: sh scripts/check.sh [--kit-only] [--mutations] (MUT_ONLY=name selects a mutation)'; exit 0 ;;
  --mutations) MUTATIONS=1 ;;
  --kit-only) KIT_ONLY=1 ;;
  '') : ;;
  *) echo 'unknown option' >&2; exit 2 ;;
esac
./mill --no-server kit.test.check
sh scripts/check-example.sh
sh scripts/check-layout.sh
if [ "${KIT_ONLY-0}" != 1 ]; then
  sh scripts/test-fetch-flix.sh
  sh scripts/fetch-flix.sh
  sh scripts/check-docs.sh
  ./mill --no-server flix-bridge.test.check
  sh scripts/check-package.sh
fi
if [ "${MUTATIONS-0}" = 1 ]; then
  python3 scripts/mutations.py
  if [ "${KIT_ONLY-0}" != 1 ]; then python3 scripts/mutations-bridge.py; fi
else
  echo 'mutations: not run (opt-in)'
fi
