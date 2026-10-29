#!/usr/bin/env bash
# Verify the exact JVM core classes fed into D8, using the APK's compatible runtime.
# Run test-core.sh and build-android-offline.sh first. This does not execute Android UI.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/evan/.local/jdks/temurin-25.0.4.1+1}"
IFS=: read -r tests_jar compiler_stdlib junit hamcrest < core/build/offline/classpath.txt
stdlib="${KOTLIN_STDLIB_JAR:-$(find "$HOME/.gradle/wrapper/dists" -name 'kotlin-stdlib-2.[012].*.jar' | sort -V | tail -1)}"
classes=app/build/sdk-offline/classes.jar
[[ -f "$classes" && -f "$tests_jar" && -f "$stdlib" ]] || { echo 'Run the core tests and SDK build first.' >&2; exit 2; }
echo "Testing packaged core: $classes, runtime: $stdlib"
# First entry wins for dev.colorcode.core production classes; test classes remain in tests.jar.
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -cp "$classes:$tests_jar:$stdlib:$junit:$hamcrest" \
    org.junit.runner.JUnitCore dev.colorcode.core.CodecTest dev.colorcode.core.ProtocolTest \
    dev.colorcode.core.ImageReaderTest dev.colorcode.core.CapacityTest
