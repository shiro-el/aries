plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.licensee)
}
android {
    namespace = "io.github.shiroel.aries.wear"
    compileSdk = 37
    defaultConfig { minSdk = 33; targetSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(21) }
licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
    allowUrl("https://developer.android.com/studio/terms.html") {
        because("Google Play services, required for the companion Data Layer")
    }
    bundleAndroidAsset.set(true)
}
dependencies {
    implementation(project(":wear-data"))
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications)
    implementation(libs.wear.remote)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
