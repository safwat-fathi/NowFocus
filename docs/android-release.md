# Android release (Google Play)

**Test status (2026-10-07).** Account sync with the Windows PC was tested by the owner on a real Android phone against production, together with the Windows 11 test in `docs/windows-release.md`. Per-test results were not written down.

One-time: create the upload key and back it up somewhere off this machine (password manager). Play App Signing holds the real signing key; this one can be reset via Play support if lost.

    keytool -genkey -v -keystore ~/nowfocus-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload

Point the build at it with `apps/android/keystore.properties` (gitignored):

    storeFile=/Users/<you>/nowfocus-upload.jks
    storePassword=...
    keyAlias=upload
    keyPassword=...

or the env vars `NOWFOCUS_KEYSTORE`, `NOWFOCUS_KEYSTORE_PASSWORD`, `NOWFOCUS_KEY_ALIAS`, `NOWFOCUS_KEY_PASSWORD`.

Each upload:

1. Bump `versionCode` (must increase every upload) and bump the patch version in `versionName` (e.g. `0.8.0` &rarr; `0.8.1`) in `apps/android/app/build.gradle.kts`. Per [Versioning Policy](file:///Users/safwat/Coding/Projects/side-projects/now-focus/docs/versioning.md), only bump the patch number during Alpha; do not change the minor version.
2. `cd apps/android && ./gradlew :app:bundleRelease`
3. Upload `app/build/outputs/bundle/release/app-release.aab` in Play Console.

Without a key configured the bundle is built unsigned, which Play rejects.
