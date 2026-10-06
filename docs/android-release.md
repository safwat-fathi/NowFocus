# Android release (Google Play)

One-time: create the upload key and back it up somewhere off this machine (password manager). Play App Signing holds the real signing key; this one can be reset via Play support if lost.

    keytool -genkey -v -keystore ~/nowfocus-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload

Point the build at it with `apps/android/keystore.properties` (gitignored):

    storeFile=/Users/<you>/nowfocus-upload.jks
    storePassword=...
    keyAlias=upload
    keyPassword=...

or the env vars `NOWFOCUS_KEYSTORE`, `NOWFOCUS_KEYSTORE_PASSWORD`, `NOWFOCUS_KEY_ALIAS`, `NOWFOCUS_KEY_PASSWORD`.

Each upload:

1. Bump `versionCode` (must increase every upload) and `versionName` in `apps/android/app/build.gradle.kts`.
2. `cd apps/android && ./gradlew :app:bundleRelease`
3. Upload `app/build/outputs/bundle/release/app-release.aab` in Play Console.

Without a key configured the bundle is built unsigned, which Play rejects.
