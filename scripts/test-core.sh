#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Uses existing tools only. All generated files stay in this repository.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-amd64 /home/evan/.local/jdks/temurin-*; do
    if [[ -x "$candidate/bin/java" ]]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [[ -z "${KOTLIN_LIB_DIR:-}" ]]; then
  compiler=$(find "$HOME/.gradle/wrapper/dists" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | sort -V | tail -1)
  [[ -n "$compiler" ]] || { echo 'Set KOTLIN_LIB_DIR to an existing Kotlin compiler lib directory.' >&2; exit 2; }
  KOTLIN_LIB_DIR=$(dirname "$compiler")
fi
stdlib=$(find "$KOTLIN_LIB_DIR" -maxdepth 1 -name 'kotlin-stdlib-[0-9]*.jar' | head -1)
junit=$(find "$HOME/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2" -name '*.jar' 2>/dev/null | head -1)
hamcrest=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3" -name '*.jar' 2>/dev/null | head -1)
[[ -f "$junit" && -f "$hamcrest" ]] || { echo 'JUnit 4.13.2 and Hamcrest 1.3 must already be cached (or use Gradle).' >&2; exit 2; }
mkdir -p core/build/offline
mapfile -t sources < <(find core/src/main/kotlin core/src/test/kotlin -name '*.kt' | sort)
"$JAVA_BIN" -cp "$KOTLIN_LIB_DIR/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlib:$junit:$hamcrest" \
  -d core/build/offline/tests.jar "${sources[@]}"
test_cp="core/build/offline/tests.jar:$stdlib:$junit:$hamcrest"
printf '%s\n' "$test_cp" > core/build/offline/classpath.txt
"$JAVA_BIN" -Djava.awt.headless=true -cp "$test_cp" org.junit.runner.JUnitCore \
  dev.colorcode.core.CodecTest dev.colorcode.core.ProtocolTest \
  dev.colorcode.core.ImageReaderTest dev.colorcode.core.CapacityTest dev.colorcode.core.FiveColorTest
# Separate JVMs: no in-memory encoder object can help the decoder.
"$JAVA_BIN" -Djava.awt.headless=true -cp "$test_cp" dev.colorcode.core.FixtureCliKt encode core/build/offline/fresh.png
"$JAVA_BIN" -Djava.awt.headless=true -cp "$test_cp" dev.colorcode.core.FixtureCliKt decode core/build/offline/fresh.png
"$JAVA_BIN" -Djava.awt.headless=true -cp "$test_cp" dev.colorcode.core.FixtureCliKt encode core/build/offline/fresh-v2.png 2
"$JAVA_BIN" -Djava.awt.headless=true -cp "$test_cp" dev.colorcode.core.FixtureCliKt decode core/build/offline/fresh-v2.png 2
"$JAVA_BIN" -Djava.awt.headless=true -cp "$test_cp" dev.colorcode.core.CapacityCliKt core/build/offline
