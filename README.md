# ColorCode for Android

ColorCode is an Android app for creating and reading a custom, QR-like color code. It stores UTF-8 text or arbitrary binary data. Standard QR readers cannot read it.

## Features

- Create four-color codes from text or files; choose a grid and see the byte capacity.
- Save or share lossless PNGs. Import an image or capture a photo to read a code.
- Verify decoded data with error correction and a checksum, then display text or a hex preview, or export the original bytes.
- Read both four-color format 1 and existing five-color format 2 codes. New codes use format 1.
- Keep payloads on-device. Release builds use the internet to check for GitHub updates and download an APK when requested.

The camera uses a capture-and-read flow, not continuous scanning. Camera access requires permission; file access uses Android's document picker.

## Install and build

ColorCode requires Android 8.0 (API 26) or newer and targets API 36. Debug builds install separately as **ColorCode Dev**, with their own data and sharing permissions. Update checks run only in release builds. See [release setup and legacy-install migration](.github/RELEASING.md).

For development, install JDK 17 and Android SDK platform 36, then set `JAVA_HOME` and `ANDROID_HOME`. The project uses Gradle 8.13, Android Gradle Plugin 8.13.0, and Kotlin 2.2.21.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Add `--offline` when the wrapper and dependencies are cached. The debug APK is under `app/build/outputs/apk/debug/`. Building does not install it.

### Offline fallback

The scripts below use installed tools and cached dependencies without downloads. They were written for a Linux environment: override their `/home/evan/...` defaults with `JAVA_HOME` and `ANDROID_HOME`. They also require Bash `mapfile` and `sort -V`, which default macOS tools do not provide. The SDK build requires platform 36 and build-tools 36.0.0. See the [Mac adaptations used for verification](evidence/fresh-launch/README.md).

```bash
scripts/test-core.sh
scripts/test-app-host.sh
scripts/build-android-offline.sh
scripts/test-packaged-core.sh
```

- Core tests include separate-process PNG round trips and maximum-capacity measurements.
- App host tests cover storage, retained work, share handling, and camera matching; they do not execute Android APIs.
- The SDK build compiles and signs a debug APK at `app/build/sdk-offline/colorcode-debug.apk`, using a disposable key in the ignored build directory.
- The final script tests the packaged core classes on the JVM; it does not run the Android UI.

The fallback uses cached Kotlin compiler, JUnit, and Hamcrest files. `KOTLIN_LIB_DIR` and `KOTLIN_STDLIB_JAR` can override compiler and runtime locations. Choose a JDK compatible with the cached compiler. The SDK build uses language/metadata 2.2 and API 2.0 with a compatible Kotlin 2.0 runtime for build-tools 36.0.0 D8.

## Using the app

- **Make:** Enter text or select a file, choose a grid, and create a code. Save PNG for a permanent copy.
- **Read:** Import an image or take a photo. A failed camera decode offers **Retry last photo**.
- **Share:** Each share creates a separate private PNG. Links expire after seven days; a later share removes expired files and revokes their grants.
- **Updates:** Release builds check GitHub on launch and offer a manual check. Download and installation require user action; Android may ask for installation permission.

On the development branch, fresh launches restore the last successfully created code and grid, or show a sample if none is available. The editor starts with its default input. This preview restoration was added after public release v0.1.0.

Encoding, imports, decoding, and exports retain their work across activity rotation, but not process death. Camera captures use separate retry files, and JPEGs are matched to capture timestamps. A retry filename can be restored from the activity bundle.

## Capacity and format

| Grid | Format 1 payload capacity |
|---|---:|
| 57 × 57 | 599 bytes |
| 89 × 89 | 1,591 bytes |
| 129 × 129 | 3,484 bytes |

See [format 1](docs/FORMAT.md) for layout, framing, CRC, masking, interleaving, and Reed–Solomon details. [Format 2](docs/FORMAT-V2.md) describes the five-color palette, packing, and capacities. Both use the Square 1 appearance; unknown versions are rejected. The Kotlin core has no Android, AWT, or third-party runtime dependency.

### Digital density comparison

Each ColorCode payload below was encoded to a PNG, reloaded, and decoded byte-for-byte. Images use eight pixels per module and four quiet modules per side. QR-L figures are standard byte-mode budgets, not measurements from a QR image encoder or a camera.

| Grid | Image pixels | ColorCode | QR-L budget | Ratio |
|---|---|---:|---:|---:|
| 57 × 57 | 520 × 520 | 599 bytes | 271 bytes (v10) | 2.210× |
| 89 × 89 | 776 × 776 | 1,591 bytes | 718 bytes (v18) | 2.216× |
| 129 × 129 | 1096 × 1096 | 3,484 bytes | 1,528 bytes (v28) | 2.280× |

The correction budgets differ: ColorCode uses 32 parity bytes per block, while these QR-L versions use 18, 30, and 30. Greater digital capacity does not establish equivalent optical robustness. See the [measurement and its limitations](evidence/11-core-final.txt).

## Verification and limits

- Automated core tests cover capacity boundaries, binary and text payloads, error correction, malformed data, PNG reloads, transformations, synthetic perspective, blur, color cast, and a JPEG conversion.
- The first public release was installed through the update flow on a Pixel 7; a manual update check reported it was up to date. See the [release record](evidence/first-public-release/README.md).
- A later Dev build showed the sample on fresh launch and restored custom text through three force-stop relaunches. The previews decoded to the exact bytes. See the [fresh-launch record](evidence/fresh-launch/README.md).
- An earlier five-color development build was installed and produced a preview. See its [verification record](evidence/magenta/README.md).

The fresh-launch change passed the adapted SDK fallback and JVM tests. Its Gradle tasks did not run in that environment because the installed JDK 25 was incompatible with Gradle 8.13. Earlier Gradle results and other records are in [build evidence](evidence/README.md).

Broader lifecycle, file-picker and sharing flows, camera behavior, and device image processing still need testing. Camera and printer reliability, low-light performance, color-blind accessibility, arbitrary-scene recognition, and QR interoperability are not established. Reed–Solomon protects data and headers, not damaged finder markers. See the [device test steps](docs/DEVICE-TESTS.md).
