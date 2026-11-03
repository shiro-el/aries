import java.io.File
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val repoRoot: File = rootProject.projectDir.parentFile

android {
    namespace = "io.github.shiroel.aries"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.shiroel.aries"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += rustAbis() }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging { jniLibs.useLegacyPackaging = false }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation("${libs.jna.get()}@aar")

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

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindings::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdk::outputDir)
    }
}
