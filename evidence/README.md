# Verification evidence

The latest privacy/lifecycle/camera fixes and JDK 17 verification are recorded separately in [review-fixes/README.md](review-fixes/README.md). They add 23 passing app-helper JVM tests and a new real Gradle 8.13 attempt blocked by the sandbox's wildcard-IP restriction. All original numbered logs below are preserved.

The original red log is preserved byte-for-byte. All logs are from local sandbox execution; no device installation, camera test, printer test, DNS workaround or network dependency download occurred.

Final results:

- [11-core-final.txt](11-core-final.txt): 48 passing JUnit tests, separate-process PNG round trip and maximum-capacity PNG reload/decodes at all three grids. Includes measured payload hashes and explicit QR reference-budget limitations.
- [12-android-final.txt](12-android-final.txt): actual Android/core Kotlin compilation, resource linking, dex generation, debug APK packaging and successful APK Signature Scheme v2/v3 verification. JDK native-access/deprecation and Kotlin API-version warnings remain; there are no D8 metadata-rewrite warnings in this final build.
- [13-packaged-core.txt](13-packaged-core.txt): all 48 tests also pass with the exact production core classes from the SDK APK build placed first on the JVM classpath, using the same compatible Kotlin runtime as the APK. This verifies the dex input classes, not execution on Android/ART.
- [14-artifacts.txt](14-artifacts.txt): final APK and original evidence hashes, ZIP contents and artifact sizes.

Earlier results and resolved failures:

- `01-core-red.txt`: original 24 expected failures from the previous scaffold run.
- `02-core-first.txt`: all original 24 tests passing, separate-process PNG round trip.
- `03-gradle-offline.txt`: Gradle fails before project configuration: its file-lock service cannot determine a usable wildcard IP. No DNS or sandbox workaround was attempted. The inspected cache also lacked AGP.
- `04-android-sdk-build.txt`: first Android compilation found a missing explicit correction-count argument in restored state; corrected.
- `05-android-sdk-build.txt`: SDK AAPT required a manifest package; fixed by generating a temporary package-qualified manifest inside ignored build output while retaining the namespace-based Gradle source manifest.
- `06-core-extended.txt`: expanded tests caught an incorrect QR v10 reference block data-length entry; corrected from total-block lengths to actual data lengths. All geometry tests passed in that run.
- `07-android-sdk-build.txt`: first successful APK, but the latest cached Kotlin 2.4 metadata was too new for installed D8. This large raw log is retained; it is superseded by the compatible final build.
- `08-core-green.txt`: all 48 expanded tests passing and the first complete density report.
- `09-android-sdk-build.txt`: older Kotlin compiler fails to parse installed JDK 25 version. Resolved by using the newer compiler with supported language/metadata 2.2 and API/runtime 2.0.
- `10-android-sdk-build.txt`: compatible SDK build succeeds, before final lifecycle fixes.

Commands used for final checks (from repository root):

```bash
scripts/test-core.sh > evidence/11-core-final.txt 2>&1
scripts/build-android-offline.sh > evidence/12-android-final.txt 2>&1
scripts/test-packaged-core.sh > evidence/13-packaged-core.txt 2>&1
bash -n scripts/*.sh
```

Gradle attempt, using an existing cached executable and a writable local Gradle home:

```bash
env JAVA_HOME=/home/evan/.local/jdks/temurin-25.0.4.1+1 \
  GRADLE_USER_HOME="$PWD/.gradle-home" \
  /home/evan/.gradle/wrapper/dists/gradle-9.7.1-bin/1w1c7tv4s851m17nbqdsro2tv/gradle-9.7.1/bin/gradle \
  --offline --no-daemon --console=plain :core:test :app:assembleDebug
```

This used a cached JDK-25-compatible Gradle executable, not the project's pinned 8.13 wrapper. The original Gradle files remain available; Gradle itself has not successfully configured the project in this sandbox. The independent installed-SDK build provides the compiled artifact instead.

PNG fixtures and generated density report are in `core/build/offline/`. The signed APK is `app/build/sdk-offline/colorcode-debug.apk`. Generated files under `build/` are ignored by Git; source, scripts, documentation and evidence remain uncommitted for review. No Git add/commit/push was attempted.
