# Privacy, rotation and camera review fixes

Validated all three findings against the original source and fixed them without changing the core tests. Everything ran locally in workspace-write. No network workaround, dependency download, server operation, publishing, APK installation, Git staging/commit/push or subagent work occurred. All 14 original numbered evidence logs are byte-for-byte unchanged.

## Changes

1. **Sharing:** `MainActivity.share()` and `ShareProvider` use `SharedPngStore`. Each operation snapshots the payload, renders its own bitmap and writes a random UUID-named PNG using `CREATE_NEW`. Files become read-only before their URI is returned. The provider validates the complete raw URI, rejects alternate schemes/authorities, queries/fragments, encoded paths, dot segments and legacy static paths, and checks canonical containment and symlinks. The private `filesDir/shared-png` directory avoids cache eviction of live shares. Access expires seven days after creation; the next share revokes expired grants before deleting only expired files. An already open descriptor still reads the old inode. Live shared content is never rewritten by this API.
2. **Rotation:** `RetainedWork` survives through the framework's non-configuration instance. Tasks use input snapshots and application context, and completion callbacks have the current `MainActivity` as their receiver. `onPause`/state saving detach the owner; `onResume` drains completed work exactly once. Destruction on rotation leaves work running; final destruction closes it and disposes undelivered bitmaps. Older preview restoration checks payload/grid and cannot replace a newer code or hide a retained decode error. Camera input uses separate validated private files; failed/undelivered decoding preserves them, with a saved retry filename and a Retry last photo action. Successful delivery removes the corresponding input.
3. **Camera:** Still requests carry unique sequence tags. Request callbacks supply sensor timestamps; `CaptureMatcher` accepts a JPEG only when its image timestamp matches the active tagged request. It supports both callback orders with a two-frame buffer, rejects zero timestamps and stale request callbacks, and clears matching state on timeout/failure/pause. A late A JPEG cannot complete retry B. Matching JPEGs are saved under unique capture names returned to MainActivity, replacing the shared `capture.jpg` path.

Only CAMERA remains in the source and packaged manifests; no Internet permission was added. The provider stays unexported, grant-based and read-only.

## Exact results

| Evidence | Exit | Result |
| --- | ---: | --- |
| [01-gradle-offline.txt](01-gradle-offline.txt) | 1 | Real pinned Gradle 8.13 / supplied JDK 17: `:core:test :app:assembleDebug` cannot start before project configuration: `Could not determine a usable wildcard IP for this machine.` No Gradle task ran. |
| [02-host-red.txt](02-host-red.txt) | 1 | 16 tests, 12 assertion failures against helpers reproducing the legacy behaviors. This was a JVM extraction, not execution of an Android Activity/provider/camera. [Helper source snapshot](02-legacy-helper-sources.txt) and [initial test source snapshot](02-red-test-sources.txt) preserve the red setup. |
| [03-host-green.txt](03-host-green.txt) | 0 | The same 16 regressions pass after fixing the helpers. |
| [04-android-sdk-build.txt](04-android-sdk-build.txt) | 0 | First integrated SDK compilation/package/signature verification succeeds. |
| [05-core-tests.txt](05-core-tests.txt) | 0 | `scripts/test-core.sh`: all 48 existing JUnit tests pass; separate JVM PNG encode/decode and all three maximum-capacity PNG round trips pass. |
| [06-host-final.txt](06-host-final.txt) | 0 | 23 app-helper JVM regressions pass, including seven additional storage, repeated-rotation, cleanup and real camera retry-file cases. No tests were removed or relaxed. |
| [07-packaged-core.txt](07-packaged-core.txt) | 0 | 48 tests pass against the first SDK build's production classes/runtime. |
| [08-compiled-callbacks.txt](08-compiled-callbacks.txt) | 0 | Initial compiled callback signatures inspected: worker functions take snapshots/application resolver/context; completion functions accept an Activity receiver. Inspection, not a device test. |
| [09-android-sdk-final.txt](09-android-sdk-final.txt) | 0 | Final Android/core compile, Android 36 resource linking, dex, APK alignment and v2/v3 debug signature verification succeed. Packaged permissions list only CAMERA. |
| [10-packaged-app-host.txt](10-packaged-app-host.txt) | 0 | All 23 app-helper tests also pass with the final production classes fed to D8 first on the JVM classpath, using the APK's Kotlin 2.0.21 runtime. |
| [11-packaged-core-final.txt](11-packaged-core-final.txt) | 0 | All 48 core tests pass again against those final production classes/runtime. |
| [12-validation.txt](12-validation.txt) | 0 | Shell syntax, manifest permission check, preservation checks and artifact hashes. |

The final SDK build reports one compiler warning: **Kotlin API version 2.0 is deprecated and support will be removed in a future version**. The existing offline build deliberately uses language/metadata 2.2 and API/runtime 2.0 to remain compatible with installed D8. No D8 metadata-rewrite or JDK native-access warnings appear in this JDK 17 build. It is an independent SDK build, not a successful Gradle build.

The APK is `app/build/sdk-offline/colorcode-debug.apk`; it has not been installed or executed on Android/ART. JVM helper tests validate production logic, not framework lifecycle ordering, grant enforcement or the Camera2/HAL timestamp contract. Those checks remain pending in [DEVICE-TESTS.md](../../docs/DEVICE-TESTS.md). Configuration retention is not durable execution after process death; imports may need reopening, while a camera retry filename can be restored when its activity bundle survives. Failed camera retry files remain private until successful delivery or app-data removal.

## Commands

From the repository root, using only preinstalled tools/caches:

```bash
export JAVA_HOME=/home/evan/.local/jdks/colorcode-jdk17/jdk-17.0.21+9
export ANDROID_HOME=/home/evan/.local/share/android-sdk

env GRADLE_USER_HOME="$PWD/.gradle-home" \
  GRADLE_RO_DEP_CACHE=/home/evan/.gradle/caches \
  /home/evan/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --offline --no-daemon --console=plain :core:test :app:assembleDebug

scripts/test-core.sh
scripts/test-app-host.sh
scripts/build-android-offline.sh
scripts/test-packaged-core.sh
bash -n scripts/*.sh
```

The Gradle executable above is the preinstalled distribution matching the project's wrapper pin. It avoids asking the wrapper to fetch anything into the writable Gradle home. Gradle's socket-related startup blocker was not bypassed.

The final app-helper run against APK dex-input classes used:

```bash
IFS=: read -r tests_jar compiler_stdlib junit hamcrest < core/build/offline/classpath.txt
"$JAVA_HOME/bin/java" -cp \
  "app/build/sdk-offline/classes.jar:app/build/host-tests/tests.jar:/home/evan/.gradle/wrapper/dists/gradle-8.14-bin/38aieal9i53h9rfe7vjup95b9/gradle-8.14/lib/kotlin-stdlib-2.0.21.jar:$junit:$hamcrest" \
  org.junit.runner.JUnitCore dev.colorcode.app.SharedPngStoreTest \
  dev.colorcode.app.RetainedWorkTest dev.colorcode.app.CaptureMatcherTest dev.colorcode.app.CameraFilesTest
```

[13-review.patch](13-review.patch) records the source/script/documentation changes against the workspace snapshot taken before this review. All changes remain unstaged and uncommitted.
