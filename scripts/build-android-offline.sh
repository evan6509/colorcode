#!/usr/bin/env bash
# Independent SDK build when Gradle/AGP cannot run in a restricted offline sandbox.
# No downloads, dependency resolution, network calls, device access or installation.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/evan/.local/jdks/temurin-25.0.4.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-/home/evan/.local/share/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"
# The newest cached compiler runs on JDK 25. Target language/metadata 2.2 and API
# 2.0 with a compatible runtime: build-tools 36.0.0's D8 understands metadata <=2.2.
compiler=$(find "$HOME/.gradle/wrapper/dists" -name 'kotlin-compiler-embeddable-*.jar' | sort -V | tail -1)
KOTLIN_LIB_DIR="${KOTLIN_LIB_DIR:-$(dirname "$compiler")}"
stdlib="${KOTLIN_STDLIB_JAR:-$(find "$HOME/.gradle/wrapper/dists" -name 'kotlin-stdlib-2.[012].*.jar' | sort -V | tail -1)}"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
build_tools="$ANDROID_HOME/build-tools/36.0.0"
[[ -f "$platform" && -f "$stdlib" && -x "$build_tools/aapt2" ]] || { echo 'Requires local Android 36 platform/build-tools and cached Kotlin compiler.' >&2; exit 2; }
out=app/build/sdk-offline
mkdir -p "$out/dex" "$out/generated"
mapfile -t sources < <(find core/src/main/kotlin app/src/main/kotlin -name '*.kt' | sort)
echo 'Compiling real core and Android sources against android-36/android.jar'
java -cp "$KOTLIN_LIB_DIR/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -language-version 2.2 -api-version 2.0 -jvm-target 17 -classpath "$platform:$stdlib" \
    -d "$out/classes.jar" "${sources[@]}"
echo 'Compiling and linking Android resources'
"$build_tools/aapt2" compile --dir app/src/main/res -o "$out/resources.zip"
python3 - <<'PY'
from pathlib import Path
source = Path('app/src/main/AndroidManifest.xml').read_text()
# Match Gradle's debug identity while retaining the production Kotlin namespace.
source = source.replace('${appLabel}', 'ColorCode Dev').replace('${applicationId}', 'dev.colorcode.app.dev')
for component in ('MainActivity', 'CameraActivity', 'ShareProvider'):
    source = source.replace(f'android:name=".{component}"', f'android:name="dev.colorcode.app.{component}"')
Path('app/build/sdk-offline/AndroidManifest.xml').write_text(source.replace('<manifest ', '<manifest package="dev.colorcode.app.dev" ', 1))
PY
"$build_tools/aapt2" link -o "$out/resources.apk" -I "$platform" \
    --manifest "$out/AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 36 \
    --debug-mode --version-code 1 --version-name 0.1.0-dev --java "$out/generated" "$out/resources.zip"
echo 'Dexing application and Kotlin runtime'
"$build_tools/d8" --lib "$platform" --min-api 26 --output "$out/dex" "$out/classes.jar" "$stdlib"
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
out = Path('app/build/sdk-offline')
with ZipFile(out / 'resources.apk') as resources, ZipFile(out / 'unsigned.apk', 'w', ZIP_DEFLATED) as apk:
    for item in resources.infolist():
        apk.writestr(item, resources.read(item.filename))
    for dex in sorted((out / 'dex').glob('*.dex')):
        apk.write(dex, dex.name)
PY
"$build_tools/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
# Disposable local debug identity only, kept under ignored build outputs.
if [[ ! -f "$out/debug.keystore" ]]; then
    keytool -genkeypair -keystore "$out/debug.keystore" -storepass android -keypass android \
        -alias androiddebugkey -dname 'CN=Local Offline Debug' -keyalg RSA -keysize 2048 -validity 3650
fi
"$build_tools/apksigner" sign --ks "$out/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --out "$out/colorcode-debug.apk" "$out/aligned.apk"
"$build_tools/apksigner" verify --verbose "$out/colorcode-debug.apk"
"$build_tools/aapt2" dump badging "$out/colorcode-debug.apk"
echo "Built $out/colorcode-debug.apk (local debug build; not installed or device-tested)"
