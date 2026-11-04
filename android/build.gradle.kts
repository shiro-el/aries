import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Pins the Kotlin Gradle plugin that AGP's built-in Kotlin uses, so it
    // matches the Compose compiler plugin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.licensee) apply false
}

// Both APKs must have the same final applicationId and signing certificate.
// Keep the shared ~/.android/debug.keystore for debug and dogfood variants.
subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            defaultConfig {
                applicationId = "io.github.shiroel.aries"
                versionCode = (findProperty("aries.versionCode") as String?)?.toInt() ?: 1
                versionName = findProperty("aries.versionName") as String? ?: "0.1.0-dev"
            }
            signingConfigs {
                uploadKey()?.let { key ->
                    create("upload") {
                        storeFile = key.storeFile
                        storePassword = key.storePassword
                        keyAlias = key.keyAlias
                        keyPassword = key.keyPassword
                        if (key.storeFile.extension.lowercase() in setOf("p12", "pfx")) storeType = "pkcs12"
                    }
                }
            }

            buildTypes {
                // Debug builds install next to a release (or legacy) Aries on the same device.
                getByName("debug") { applicationIdSuffix = ".dev" }
                // Without an upload key the release APK is left unsigned.
                getByName("release") { signingConfigs.findByName("upload")?.let { signingConfig = it } }
                create("dogfood") {
                    initWith(getByName("release"))
                    isDebuggable = false
                    isMinifyEnabled = true
                    isShrinkResources = true
                    signingConfig = signingConfigs.getByName("debug")
                    applicationIdSuffix = null
                    matchingFallbacks += "release"
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
            }
        }
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                if (variant.buildType in setOf("dogfood", "baselineCapture")) {
                    // The already-published debug APK is .dev. Set its complete ID,
                    // without a build-type suffix, so dogfood upgrades it in place.
                    variant.applicationId.set("io.github.shiroel.aries.dev")
                }
            }
        }
    }
}

class UploadKey(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun Project.uploadKey(): UploadKey? {
    val env = System.getenv()
    env["ARIES_UPLOAD_KEYSTORE"]?.takeIf { it.isNotBlank() }?.let { path ->
        fun need(name: String) = env[name]?.takeIf { it.isNotEmpty() } ?: error("$name is not set")
        return UploadKey(
            file(path), need("ARIES_UPLOAD_STORE_PASSWORD"),
            need("ARIES_UPLOAD_KEY_ALIAS"), need("ARIES_UPLOAD_KEY_PASSWORD"),
        )
    }
    val propsPath = env["ARIES_UPLOAD_KEYSTORE_PROPERTIES"]?.takeIf { it.isNotBlank() } ?: return null
    val props = Properties().apply { file(propsPath).inputStream().use { load(it) } }
    fun need(name: String) = props.getProperty(name) ?: error("$propsPath has no $name")
    return UploadKey(file(need("storeFile")), need("storePassword"), need("keyAlias"), need("keyPassword"))
}
