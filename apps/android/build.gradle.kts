plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Matches the Kotlin version above exactly - KSP versions are pinned per Kotlin release.
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
