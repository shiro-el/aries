plugins {
    alias(libs.plugins.android.application) apply false
    // Pins the Kotlin Gradle plugin that AGP's built-in Kotlin uses, so it
    // matches the Compose compiler plugin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
