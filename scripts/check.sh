#!/bin/sh
# Source checks only. Mutations are opt-in: --mutations or MUTATIONS=1.
set -eu
umask 022
cd "$(dirname "$0")/.."
case "${1-}" in
  --help) echo 'Usage: sh scripts/check.sh [--mutations] (MUT_ONLY=name selects a mutation)'; exit 0 ;;
  --mutations) MUTATIONS=1 ;;
  '') : ;;
  *) echo 'unknown option' >&2; exit 2 ;;
esac
./mill --no-server kit.test.check
sh scripts/check-example.sh
if [ "${MUTATIONS-0}" = 1 ]; then
  python3 scripts/mutations.py
else
  echo 'mutations: not run (opt-in)'
fi
