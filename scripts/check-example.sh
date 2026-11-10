#!/bin/sh
# Compile and exercise a source-only example with the SDK embedded on its bridge classpath.
set -eu
umask 022
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?set JAVA_HOME to Java 21}"
mkdir -p out/example
"$JAVA_HOME/bin/javac" --release 21 -d out/example kit/src/dev/flixw/sdk/kit/*.java examples/Shell.java
run_example() {
  FLIXW_ABI_VERSION="$1" FLIXW_PROJECT_ROOT="$PWD" FLIXW_COMPILER_JAR="$PWD/out/example/compiler.jar" \
    FLIXW_JAVA_HOME="$JAVA_HOME" "$JAVA_HOME/bin/java" -cp out/example Shell "$2"
}
run_example 1 --success > out/example/success.txt
grep -qx 'example bridge' out/example/success.txt
status=0
run_example 1 --finding > out/example/finding.txt || status=$?
[ "$status" = 1 ]
status=0
run_example 2 --success > out/example/error.txt 2>&1 || status=$?
[ "$status" = 2 ]
grep -qx 'requires flixw ABI version 1' out/example/error.txt
echo 'ExampleShell: 3 passed, 0 failed'
