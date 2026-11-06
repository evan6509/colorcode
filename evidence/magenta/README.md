# Five-color verification — 2026-11-06

- Added format 2 with magenta (`#cc33cc`) as a fifth data color, preserving the original format 1 mapping (pinned by a SHA-256 cell fixture captured before editing).
- 67 core JUnit tests passed: all previous cases, both image-reader format candidates, maximum-capacity PNG reloads at all grids, independent base-5 stream unpacking, random payloads, damaged-group correction, palette and format guards.
- Separate JVM processes encoded and decoded the exact 588-byte fixture in both formats with zero corrections.
- Android sources compiled, resources linked, D8 produced dex, and the local debug APK passed signature verification.
- All 67 core tests also passed against the exact production classes used as dex inputs.
- Local build used the cached Kotlin 2.4 compiler with language 2.2/API 2.0, Kotlin runtime 2.0.21, Android Studio JBR 25 and installed SDK platform 37.0/build-tools 36.0.0. Target SDK remains 36 and minimum SDK 26. The configured compile SDK 36 is unavailable on this host, so a temporary copy of the offline build script used platform 37.0 and replaced Bash `mapfile` for macOS. No project SDK settings changed, and no Gradle build is claimed.

Generated APKs, caches, signing keys and device backups remain in ignored build directories.

## Pixel 7 installation

- After explicit approval, replaced the previously installed build because its signing key differed. The installed APK is `app/build/sdk-offline/colorcode-pixel-debug.apk`, signed with this Mac's existing Android debug identity. Signature verification passed before installation.
- Backed up all five existing private saved files (13,381,537 bytes) and the original APK under ignored `app/build/device-backup/`. Refreshed the backup immediately before replacement with the old app stopped. The restored files' SHA-256 hashes exactly matched the backup.
- ADB reported successful installation and a successful cold launch of `MainActivity`.
- Inspected the visible five-color maker, 664-byte capacity and magenta label. Tapped Create code with the default `Color is data. 🌈` text; the UI reported `Created 19 bytes • 57 × 57 • five colors. PNG is ready.` The on-device preview visibly contained magenta alongside blue, teal, red and yellow.
- Device checks cover installation, launch and code creation. Camera reading, PNG save/share and image import were not exercised in this device check. Android permissions and prior share grants were not restored by the file backup.
