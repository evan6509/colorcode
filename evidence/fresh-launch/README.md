# Fresh-launch preview fix — 2026-11-08

The Make preview previously depended on `onSaveInstanceState` containing `made` bytes. A fresh launch has no such bundle and never generated a sample, leaving the preview empty.

Created codes now save their exact payload and grid in private app storage before successful completion. Writes use a same-directory atomic replacement. Fresh launches load and render that snapshot; absent or invalid saved state falls back to the default sample. Existing activity-bundle restoration and retained background delivery remain in place. The fresh-launch editor still uses its default input, independently of Last created code.

Verified locally:

- 28 app-helper JVM tests passed, including four new storage regressions covering all grid capacities and byte-for-byte preview decoding, replacement/empty payloads, missing/truncated/corrupt state, and rejection of oversized writes without replacing a prior code.
- 67 core JVM tests passed; separate-process PNG round trips and all maximum-capacity PNG round trips passed.
- The SDK fallback compiled the real Android/core sources, linked resources, generated dex, aligned the debug APK, and verified its v2/v3 signature.
- The same 67 core tests passed against the exact core classes used for the APK with its Kotlin 2.0.21 runtime.
- `git diff --check` passed.

As with the existing Mac build evidence, temporary ignored script copies replaced Bash `mapfile` and selected installed SDK platform 37.0. The compiler was cached Kotlin 2.4 with language 2.2/API 2.0 for the APK, using Java 25 and build-tools 36.0.0. Target SDK stays 36 and minimum SDK 26; project SDK settings were not changed.

The pinned Gradle 8.13 build could not configure under either installed Java 25 runtime (`Unsupported class file major version 69`). Gradle unit-test/build/lint tasks did not run.

## Pixel 7 verification

Installed and tested on the connected Pixel 7 on 2026-11-08 after the user requested device verification. The Dev APK was signed with this Mac's existing Android debug identity and installed as an in-place update of **ColorCode Dev** (`dev.colorcode.app.dev`), without uninstalling or clearing data. The fallback build's disposable signing identity did not match the installed app; that first update attempt was rejected before installation. The original public ColorCode app was not updated.

- With no private `last-code` file, a cold launch displayed the sample code automatically, before tapping Create code. Its full preview and enabled export controls were visually inspected.
- Entered `Pixel_cold_launch_test_20261108`, selected the 89 × 89 grid, and tapped Create code. Verified the saved file's exact 31-byte payload, grid, and checksum.
- Force-stopped and relaunched Dev three times. Android reported a cold launch each time. The preview appeared and Save PNG/Share PNG were enabled after each relaunch.
- Cropped each full visible preview from its captured screen using the actual ImageView bounds. The production core classes packaged into the APK decoded all four screenshots (created plus three restored) to the exact test bytes, grid 89, with zero corrected bytes. Restored previews matched the created preview pixel-for-pixel.
- Pressed Home and reopened Dev; its saved bytes remained unchanged.
- The one existing Dev shared PNG retained its SHA-256 hash; only `files/last-code` was added. Private files in the public release app were inaccessible to `run-as`, so no public-app file hash comparison is claimed.

The updated Dev app remains installed on the Pixel. APKs, private-file backups, UI dumps, screenshots, and temporary verification helpers remain under ignored `app/build/launch-checks/device/`. Device rotation, binary-input restoration, and actual Save PNG/Share PNG actions were not exercised in this test.
