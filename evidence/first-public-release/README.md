# First public release and phone update — 2026-11-08

## Publication

- With explicit user approval, changed `evan6509/colorcode` to public and merged PR #3 into `main` at `4cfe7770bbf773793f9df8b53b23539a30e98cae`.
- Configured the five release-signing Actions secrets with a dedicated RSA-4096 signing key. The key and its credentials have a verified local backup outside the repository; no key or credentials are committed.
- Workflow run [42542545820](https://github.com/evan6509/colorcode/actions/runs/42542545820) passed release-numbering tests, core and app unit tests, lint, debug build, release signing verification and publication.
- Published [v0.1.0](https://github.com/evan6509/colorcode/releases/tag/v0.1.0), Android version code 1. Downloaded its APK without authentication and verified its signature and GitHub asset SHA-256.
- APK SHA-256: `875fc8bb32d11b03a6a2e58c19ae8c10d5395d76527517db5593118cc075dd8f`.
- Release certificate SHA-256: `B4:30:41:74:44:52:D9:0E:95:FF:11:4B:1C:52:97:2E:BC:A7:4A:3A:DF:B3:46:1E:7C:0F:43:79:AF:2D:7C:1E`.

## Pixel 7 migration and update test

- Refreshed and hash-verified a backup of all seven private saved files (16,986,648 bytes), the legacy APK and package metadata. Also saved a durable copy outside ignored build outputs. Recorded ColorCode Dev's file hashes separately.
- With explicit user approval, uninstalled the legacy debug app under `dev.colorcode.app` because it used a different signing identity.
- Used a temporary, local-only debug bridge signed with the permanent release key to restore the seven saved files. All restored SHA-256 hashes matched. Restored the camera permission previously granted to the legacy installation.
- The first bridge package was rejected because its resource table was compressed. Repackaged the temporary bridge and bootstrap with their resource table stored uncompressed and aligned, verified them and installed successfully. The published APK was unaffected.
- Replaced the bridge with a local-only, non-debuggable bootstrap marked `0.0.0`, signed with the same permanent key, to exercise updating to the real release without publishing a test version. These temporary APKs were never uploaded.
- On the phone, ColorCode detected v0.1.0 and displayed **Update available**. Tapped Download; Download Manager reported completion. Opened that notification, enabled installation access specifically for ColorCode, and approved Android's update prompt.
- Google Play Protect scanned the APK, reported **This app looks safe**, and allowed installation. Android then reported **App updated**.
- The installed app is non-debuggable, reports version `0.1.0` / code 1, and its APK is byte-for-byte identical to the published GitHub asset. The update retained the restored app UID and credential-encrypted data-directory inode.
- Opened the public app and verified the **Check for updates** button is visible. Tapping it reported **ColorCode is up to date**. ColorCode retains app-specific installation access for future approved updates.
- ColorCode Dev remains installed separately as `dev.colorcode.app.dev`; its saved-file hashes were unchanged by migration and the update test.

The phone finishes on the actual published release, with no temporary test build installed under the public package. Camera reading, image import/export and broader device coverage were not retested here. APKs, data backups, hashes, package snapshots and UI dumps remain under ignored build directories or the user's local backup directory.
