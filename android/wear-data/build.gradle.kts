plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.github.shiroel.aries.weardata"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(21) }
dependencies {
    api(libs.play.services.wearable)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
