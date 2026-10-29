#!/usr/bin/env bash
# JVM regressions for the production Android-independent storage/lifecycle/camera helpers.
# Does not run an Android Activity, ContentProvider or Camera2 device.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/evan/.local/jdks/colorcode-jdk17/jdk-17.0.21+9}"
compiler=$(find "$HOME/.gradle/wrapper/dists" -name 'kotlin-compiler-embeddable-*.jar' | sort -V | tail -1)
KOTLIN_LIB_DIR="${KOTLIN_LIB_DIR:-$(dirname "$compiler")}"
stdlib=$(find "$KOTLIN_LIB_DIR" -maxdepth 1 -name 'kotlin-stdlib-[0-9]*.jar' | head -1)
junit=$(find "$HOME/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2" -name '*.jar' | head -1)
hamcrest=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3" -name '*.jar' | head -1)
mkdir -p app/build/host-tests
mapfile -t tests < <(find app/src/test/kotlin -name '*.kt' | sort)
"$JAVA_HOME/bin/java" -cp "$KOTLIN_LIB_DIR/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlib:$junit:$hamcrest" \
    -d app/build/host-tests/tests.jar \
    app/src/main/kotlin/dev/colorcode/app/SharedPngStore.kt \
    app/src/main/kotlin/dev/colorcode/app/RetainedWork.kt \
    app/src/main/kotlin/dev/colorcode/app/CameraFiles.kt \
    app/src/main/kotlin/dev/colorcode/app/CaptureMatcher.kt "${tests[@]}"
"$JAVA_HOME/bin/java" -cp "app/build/host-tests/tests.jar:$stdlib:$junit:$hamcrest" \
    org.junit.runner.JUnitCore dev.colorcode.app.SharedPngStoreTest \
    dev.colorcode.app.RetainedWorkTest dev.colorcode.app.CaptureMatcherTest dev.colorcode.app.CameraFilesTest
