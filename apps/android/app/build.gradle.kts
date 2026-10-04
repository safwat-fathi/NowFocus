plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.getnowfocus.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.getnowfocus.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6"
        // The sync server. Point a debug build at a local one with `-PsyncBaseUrl=http://10.0.2.2:3000` (emulator).
        val syncBaseUrl = (project.findProperty("syncBaseUrl") as String?) ?: "https://api.nowfocus.online"
        buildConfigField("String", "SYNC_BASE_URL", "\"$syncBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        // Release-like build for measuring: optimized, non-debuggable, signed with the debug key so
        // it installs over the debug build and `adb shell` can still profile it (see src/perf).
        // ponytail: not for distribution; a real release build type comes with real signing.
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Fingerprint lock on the Account screen. 1.1.0 is the latest stable, but it pulls fragment 1.2.5 (2020),
    // which is skewed against activity 1.9.3 / lifecycle 2.8.7 above; fragment 1.8.5 is the same release train.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.5")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Session history: the DataStore above only ever holds one row (see
    // SessionRepository's own comment) - Stats needs real history, so it gets
    // its own Room database rather than a growing JSON blob in Preferences.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    // Sync client: HTTP + the realtime WebSocket (services/api, see its WIRE_FORMAT.md).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM; the sync mapping is JSON all the way down.
    testImplementation("org.json:json:20240303")
}
