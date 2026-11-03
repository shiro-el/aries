import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
}

val repoRoot: File = rootProject.projectDir.parentFile

android {
    namespace = "io.github.shiroel.aries"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.shiroel.aries"
        minSdk = 28
        targetSdk = 36
        // Release builds pass both from the tag (scripts/release-version.sh).
        versionCode = (findProperty("aries.versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("aries.versionName") as String? ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += rustAbis() }
        // Push: the Relay this build talks to, and Firebase from google-services.json.
        buildConfigField("String", "ARIES_RELAY_URL", "\"${relayUrl()}\"")
        firebaseValues("io.github.shiroel.aries").forEach { (name, value) -> resValue("string", name, value) }
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
        debug { applicationIdSuffix = ".dev" }
        // Without an upload key the release APK is left unsigned.
        release { signingConfigs.findByName("upload")?.let { signingConfig = it } }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The per-app language list (Android 13+) comes from the res/values-* dirs.
    androidResources { generateLocaleConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging { jniLibs.useLegacyPackaging = false }
}

kotlin { jvmToolchain(21) }

// Third-party notices. Android dependencies: the build fails on a license
// outside this list, and the report ships in the APK as
// assets/app/cash/licensee/artifacts.json. Rust crates: `thirdPartyNotices`.
// Add a permissive license here when a new dependency needs it.
licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
    // Firebase Cloud Messaging pulls in Google Play services artifacts, which
    // ship under the Android SDK license. Push (FCM) cannot work without them.
    allowUrl("https://developer.android.com/studio/terms.html") {
        because("Google Play services / Firebase, required for FCM push")
    }
    bundleAndroidAsset.set(true)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation("${libs.jna.get()}@aar")
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// ---- Push ------------------------------------------------------------------
// The Relay URL comes from the ARIES_RELAY_URL repo variable (CI) or env, or
// -Paries.relayUrl; without one the app says push is not available.
// Firebase is configured from android/app/google-services.json (CI writes it
// from GOOGLE_SERVICES_JSON_BASE64; never committed) as the resource values
// the google-services plugin would generate; without it FCM is unavailable.

fun relayUrl(): String =
    (findProperty("aries.relayUrl") as String?) ?: System.getenv("ARIES_RELAY_URL") ?: ""

@Suppress("UNCHECKED_CAST")
fun firebaseValues(packageName: String): Map<String, String> {
    val file = file("google-services.json")
    if (!file.isFile) return emptyMap()
    val root = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
    val project = root["project_info"] as Map<String, Any?>
    val clients = root["client"] as List<Map<String, Any?>>
    fun pkg(c: Map<String, Any?>) =
        ((c["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"]
    val client = clients.firstOrNull { pkg(it) == packageName } ?: return emptyMap()
    val appId = (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String
    val apiKey = ((client["api_key"] as List<Map<String, Any?>>).first())["current_key"] as String
    return mapOf(
        "google_app_id" to appId,
        "google_api_key" to apiKey,
        "gcm_defaultSenderId" to project["project_number"].toString(),
        "project_id" to project["project_id"].toString(),
    )
}

// ---- Upload key --------------------------------------------------------------
// The release signing key (the upload key; Play re-signs with the app key, and
// GitHub Releases APKs ship signed with it). CI sets the ARIES_UPLOAD_* env
// vars from the ANDROID_UPLOAD_* secrets; locally ARIES_UPLOAD_KEYSTORE_PROPERTIES
// can point at a properties file with storeFile/storePassword/keyAlias/keyPassword.

class UploadKey(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun uploadKey(): UploadKey? {
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

// ---- Rust core -------------------------------------------------------------
// The Kotlin bindings are generated from the host build of the core
// (uniffi library mode); the Android .so files come from cargo-ndk.

/** ABIs to build the core for: `-Paries.abis=arm64-v8a,x86_64` (default both). */
fun rustAbis(): List<String> =
    (findProperty("aries.abis") as String? ?: "arm64-v8a,x86_64").split(',').map { it.trim() }

abstract class UniffiBindings : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val workspace: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        val root = workspace.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        exec.exec { workingDir = root; commandLine("cargo", "build", "-q", "-p", "aries-core") }
        val ext = if (System.getProperty("os.name").startsWith("Mac")) "dylib" else "so"
        exec.exec {
            workingDir = root
            commandLine(
                "cargo", "run", "-q", "-p", "uniffi-bindgen", "--", "generate",
                "--library", "target/debug/libaries_core.$ext",
                "--language", "kotlin", "--no-format", "--out-dir", out.absolutePath,
            )
        }
    }
}

abstract class CargoNdk : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val workspace: DirectoryProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val profile: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val args = mutableListOf("cargo", "ndk", "--platform", "28")
        abis.get().forEach { args += listOf("-t", it) }
        args += listOf("-o", outputDir.get().asFile.absolutePath, "build", "-p", "aries-core")
        if (profile.get() == "release") args += "--release"
        exec.exec { workingDir = workspace.get().asFile; commandLine(args) }
    }
}

val rustSources = files(
    fileTree(repoRoot.resolve("crates")) { include("**/*.rs", "**/Cargo.toml", "**/uniffi.toml") },
    repoRoot.resolve("Cargo.toml"),
    repoRoot.resolve("Cargo.lock"),
)

val uniffiBindings = tasks.register<UniffiBindings>("uniffiBindings") {
    sources.from(rustSources)
    workspace.set(repoRoot)
    outputDir.set(layout.buildDirectory.dir("generated/uniffi"))
}

val cargoNdk = tasks.register<CargoNdk>("cargoNdk") {
    sources.from(rustSources)
    workspace.set(repoRoot)
    abis.set(rustAbis())
    profile.set(findProperty("aries.rustProfile") as String? ?: "release")
    outputDir.set(layout.buildDirectory.dir("rustJniLibs"))
}

/** Notices of the Rust crates in libaries_core.so (cargo-about), as assets/licenses/rust-crates.txt. */
abstract class RustNotices : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val workspace: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile.resolve("licenses").apply { deleteRecursively(); mkdirs() }
        exec.exec {
            workingDir = workspace.get().asFile
            commandLine(
                "cargo", "about", "generate", "--locked", "--fail",
                "-m", "crates/core/Cargo.toml", "-c", "licenses/cargo-about.toml",
                "-o", out.resolve("rust-crates.txt").absolutePath, "licenses/cargo-about.hbs",
            )
        }
    }
}

val thirdPartyNotices = tasks.register<RustNotices>("thirdPartyNotices") {
    sources.from(rustSources, repoRoot.resolve("licenses"))
    workspace.set(repoRoot)
    outputDir.set(layout.buildDirectory.dir("generated/notices"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindings::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdk::outputDir)
        // Release only, so debug builds don't need cargo-about installed.
        if (variant.buildType == "release") {
            variant.sources.assets?.addGeneratedSourceDirectory(thirdPartyNotices, RustNotices::outputDir)
        }
    }
}
