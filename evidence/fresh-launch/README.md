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

The pinned Gradle 8.13 build could not configure under either installed Java 25 runtime (`Unsupported class file major version 69`). Gradle unit-test/build/lint tasks did not run. No installation or device lifecycle verification was performed. Fresh-launch, force-stop, export, and rotation checks remain listed in `docs/DEVICE-TESTS.md`.
