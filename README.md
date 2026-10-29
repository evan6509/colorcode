# ColorCode for Android

An offline Android maker and custom reader for **ColorCode v1**, a nonstandard four-color code with a QR-like square appearance. It stores the bytes themselves, including UTF-8 text and arbitrary binary data. Standard QR readers cannot read it.

The app includes text/file input, explicit grid selection, byte counts, a preview, lossless PNG saving/sharing, image import, Camera2 photo acquisition, checksum-verified results, strict UTF-8 display or a hex preview, and exact binary export. The camera is a **capture-and-read** flow, not continuous scanning. It needs camera permission; file access uses Android's document picker. There is no Internet permission, backend or external payload store.

Each share now creates a separate immutable PNG in private app storage with a random UUID token. The provider accepts only that exact URI shape and checks filesystem containment. Share links expire after seven days; expired grants are revoked and files removed on a subsequent share. Live files are never truncated or removed by this cleanup. Use Save PNG for a permanent copy. Legacy static-filename URIs are rejected.

Encoding, file import, image decode and exports retain their work queue across activity rotation and deliver results to the resumed activity. Camera JPEGs must match their tagged request's sensor timestamp, including when image and metadata callbacks arrive out of order. Each camera capture is a separate private retry file, removed only after a verified decode is delivered; a failed decode offers **Retry last photo**. Work retention covers configuration changes, not durable execution after process death. A saved camera retry filename can be restored from the activity bundle.

## Run the checks offline

```bash
export JAVA_HOME=/home/evan/.local/jdks/colorcode-jdk17/jdk-17.0.21+9
export ANDROID_HOME=/home/evan/.local/share/android-sdk
scripts/test-core.sh
scripts/test-app-host.sh
scripts/build-android-offline.sh
scripts/test-packaged-core.sh
```

These scripts use installed tools and local caches without downloads. The core script compiles and runs 48 JUnit tests, uses separate JVM processes to write/read a PNG, and measures maximum-size PNG round trips. The SDK build compiles the actual app and core against Android 36, packages resources, dexes the app with Kotlin's runtime, aligns the APK and verifies its debug signature. It does not install the APK. The last script reruns the tests against the exact core classes and Kotlin runtime used as dex inputs, on the JVM; it does not run the Android UI.

The app host script runs 23 additional JVM regressions against the production storage, retained-work and camera-matching helpers. These cover unchanged bytes at the first shared URI after a second share, traversal and symlink rejection, expiry/revocation, rotation completion delivery, camera retry input and stale capture callbacks. They do not execute Android lifecycle, URI permission or Camera2 APIs. See [review-fix evidence](evidence/review-fixes/README.md) for the preserved 12-failure red run, 16-test green run and final results.

The local debug APK is `app/build/sdk-offline/colorcode-debug.apk`. Its disposable signing key is under the same ignored build directory; this is not a release build. Minimum Android API is 26, target API is 36.

Defaults for the supplied environment:

- Android SDK: `/home/evan/.local/share/android-sdk`; override with `ANDROID_HOME`.
- Java: `/home/evan/.local/jdks/temurin-25.0.4.1+1`; override with `JAVA_HOME`.
- Kotlin compiler and JUnit/Hamcrest: existing Gradle caches. Override `KOTLIN_LIB_DIR` if necessary.
- The SDK fallback uses the newer cached compiler with language/metadata 2.2, API 2.0 and a compatible cached Kotlin 2.0 runtime (`KOTLIN_STDLIB_JAR` can override it). This avoids the installed D8's inability to parse Kotlin 2.4 metadata. The older cached compiler itself cannot run on JDK 25.

The normal Gradle project uses Gradle 8.13, AGP 8.13.0 and Kotlin 2.2.21. Normal commands are `./gradlew --offline :core:test :app:assembleDebug`; use these only when the wrapper distribution and dependencies are cached. The latest sandbox attempt used the preinstalled pinned Gradle 8.13 and supplied JDK 17 with local dependency caches. It failed before project configuration because the file-lock service could not determine a usable wildcard IP. No network workaround was attempted; Gradle tasks have not run. The independent installed-SDK build succeeds. See [build evidence](evidence/README.md).

## Measured payload density

Every color capacity below was encoded to a real lossless PNG, reloaded, decoded and compared byte-for-byte. Same grid, eight pixels per module and four quiet modules on each side:

| Grid | Image pixels | ColorCode payload | Standard QR-L byte-mode budget | Ratio |
|---|---|---:|---:|---:|
| 57 × 57 | 520 × 520 | 599 bytes | 271 bytes (v10) | 2.210× |
| 89 × 89 | 776 × 776 | 1,591 bytes | 718 bytes (v18) | 2.216× |
| 129 × 129 | 1096 × 1096 | 3,484 bytes | 1,528 bytes (v28) | 2.280× |

The QR figures are pinned standard reference budgets with byte-mode headers, length counts, terminators and padding tested locally. A complete QR image encoder/decoder was unavailable offline, so this is a **digital payload comparison**, not a measured QR camera comparison. See [the measurement](evidence/11-core-final.txt), including payload hashes, parity overhead and the reference table link.

The correction budgets differ: ColorCode uses 32 parity bytes per block (16 unknown byte errors correctable), while these QR-L versions use 18/30/30 parity bytes per block (9/15/15 errors). ColorCode spends approximately 12.8–13.7% of its protected stream on parity, versus 20.0–20.8% for those QR versions. More digital capacity and these block counts do **not** establish equivalent optical robustness. Color discrimination, module size, focus, illumination and image processing matter. The capacity gain includes both two bits per colored module and different structural/ECC overhead. PNG file size is unrelated to the payload comparison.

## Format and extension

[FORMAT.md](docs/FORMAT.md) specifies the module layout, byte framing, CRC, mask, interleaving, Reed–Solomon field and version rules. Version 1 has one appearance, **Square 1**. Appearance is separate from the framing version and is protected in the header. New appearances must get new IDs and renderer/reader implementations; existing Square 1 images must retain their original meaning. Unknown versions are rejected, never silently reinterpreted.

The pure Kotlin `core` has no Android, AWT or third-party runtime dependency. AWT/ImageIO is used only by JVM tests. `ColorCode.encode`, `SquareRenderer.render`, and `ColorCode.decode` form the public byte-to-image-to-byte path. The reader detects four finders, solves a projective transform, calibrates four color patches, corrects byte errors and verifies framing/CRC before returning bytes.

## What has and has not been verified

Automated tests cover all original 24 cases, capacity edges, empty/Unicode/NUL/all-byte payloads, fixed RS parity and an independent polynomial reference, random RS corrections, interleaved bursts, malformed and unsupported headers with valid ECC, bad CRC/padding, invalid dimensions, alpha/grayscale/noise, PNG reloads, quarter turns, reflection, translation, cropped borders, a small arbitrary rotation, synthetic perspective at all sizes, mild blur, a moderate color cast and one high-quality JPEG conversion.

The actual Android sources compile, resources link, dex is generated, and APK signatures verify. The APK has **not** been installed or run on an emulator or phone. The retained-work and camera-matching helpers pass JVM regressions; actual activity lifecycle, permission/file-picker/share flows, camera preview orientation/focus/capture and device image processing still need device testing. No camera or printer reliability, color-blind accessibility, low-light performance, general arbitrary-scene recognition or standard QR interoperability is claimed. Structural markers are checked strictly; RS protects data and header bytes, not damaged finders. See [device test steps](docs/DEVICE-TESTS.md).

All prior files and red-test evidence are preserved. Changes are deliberately uncommitted; no Git staging, commit, push, publication, installation or server changes were performed.
