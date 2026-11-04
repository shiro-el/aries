plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.licensee)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "io.github.shiroel.aries.wear"
    compileSdk = 37
    defaultConfig { minSdk = 33; targetSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
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
    implementation("androidx.wear:wear-input:1.1.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.wear.compose:compose-material:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.complications)
    implementation(libs.wear.remote)
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
