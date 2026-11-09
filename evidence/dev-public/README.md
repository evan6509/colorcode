# Separate public and Dev apps — 2026-11-08

- Debug builds use `dev.colorcode.app.dev`, the label **ColorCode Dev**, and a `-dev` version suffix. Release builds retain `dev.colorcode.app`, the label **ColorCode**, and GitHub update checks.
- Manifest sharing authorities and production share URI handling use each installed app's package name. The host regression checks custom-authority creation, reading, rejection of the other variant's authority, literal authority matching, and expiry revocation.
- All 24 app helper JVM tests passed, including the new authority regression.
- Compiled the actual Android and core sources, linked resources, generated dex, aligned the APK, and verified its signature using the installed SDK fallback. This Mac has platform 37.0 and JBR 25; temporary script adaptations selected that platform and replaced Bash `mapfile`. Target SDK remains 36 and minimum SDK 26. A full Gradle build and CI run for this change were not performed.
- Installed the Dev APK on the connected Pixel 7 with this Mac's existing Android debug identity. Both `dev.colorcode.app` and `dev.colorcode.app.dev` remain installed. Cold launch succeeded; the UI showed **ColorCode Dev**, four data colors, and the default sample.
- Created the default code and opened the Android image share chooser. Android recorded its URI grant under `content://dev.colorcode.app.dev.share/`. No recipient was selected and nothing was sent.
- All seven saved files in the original ColorCode installation retained their SHA-256 hashes after installing and using Dev. No original app data was removed or migrated.
- The original ColorCode installation remains the earlier debug build under the legacy public package name. This check did not create, sign, install or publish an official public release, or test a GitHub APK update end to end.

Generated APKs, UI dumps, package inspection and saved-file hash records remain under ignored `app/build/` directories. Signing keys and generated artifacts are not committed.
