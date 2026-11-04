package io.github.shiroel.aries.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import java.util.Locale

/** Shared Host file-name resolver for Files, Chat and future file search results.
 * Material Icon Theme precedence: exact name, longest compound extension, extension, default.
 * Paths are Host paths, so resolution never probes the phone's filesystem. */
@DrawableRes
internal fun fileTypeIcon(path: String): Int {
    val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
    fileNames[name]?.let { return it }
    if (name.startsWith("readme")) return R.drawable.file_type_readme
    if (name.startsWith("license") || name.startsWith("licence")) return R.drawable.file_type_license
    var dot = name.indexOf('.')
    while (dot >= 0) {
        fileExtensions[name.substring(dot + 1)]?.let { return it }
        dot = name.indexOf('.', dot + 1)
    }
    return R.drawable.file_type_file
}

private val fileNames = mapOf(
    "dockerfile" to R.drawable.file_type_docker,
    "containerfile" to R.drawable.file_type_docker,
    "dockerfile.dev" to R.drawable.file_type_docker,
    "dockerfile.development" to R.drawable.file_type_docker,
    "dockerfile.prod" to R.drawable.file_type_docker,
    "dockerfile.production" to R.drawable.file_type_docker,
    "dockerfile.test" to R.drawable.file_type_docker,
    "dockerfile.testing" to R.drawable.file_type_docker,
    "dockerfile.ci" to R.drawable.file_type_docker,
    "dockerfile.local" to R.drawable.file_type_docker,
    "docker-compose.yml" to R.drawable.file_type_docker,
    "docker-compose.yaml" to R.drawable.file_type_docker,
    "compose.yml" to R.drawable.file_type_docker,
    "compose.yaml" to R.drawable.file_type_docker,
    "makefile" to R.drawable.file_type_makefile,
    "gnumakefile" to R.drawable.file_type_makefile,
    "package.json" to R.drawable.file_type_nodejs,
    "package-lock.json" to R.drawable.file_type_nodejs,
    "cargo.toml" to R.drawable.file_type_rust,
    "cargo.lock" to R.drawable.file_type_rust,
    ".gitignore" to R.drawable.file_type_git,
    ".gitattributes" to R.drawable.file_type_git,
    ".gitmodules" to R.drawable.file_type_git,
    ".editorconfig" to R.drawable.file_type_editorconfig,
    "build.gradle.kts" to R.drawable.file_type_gradle,
    "settings.gradle.kts" to R.drawable.file_type_gradle,
    "gradle.properties" to R.drawable.file_type_gradle,
    "gradlew" to R.drawable.file_type_gradle,
    "gradlew.bat" to R.drawable.file_type_gradle,
    "tsconfig.json" to R.drawable.file_type_tsconfig,
    ".env" to R.drawable.file_type_settings,
    ".env.local" to R.drawable.file_type_settings,
    ".env.development" to R.drawable.file_type_settings,
    ".env.production" to R.drawable.file_type_settings,
    ".env.test" to R.drawable.file_type_settings,
    ".env.example" to R.drawable.file_type_settings,
    "androidmanifest.xml" to R.drawable.file_type_android,
)

private val fileExtensions = mapOf(
    "kt" to R.drawable.file_type_kotlin, "kts" to R.drawable.file_type_kotlin,
    "java" to R.drawable.file_type_java, "rs" to R.drawable.file_type_rust,
    "toml" to R.drawable.file_type_toml,
    "ts" to R.drawable.file_type_typescript, "cts" to R.drawable.file_type_typescript, "mts" to R.drawable.file_type_typescript,
    "d.ts" to R.drawable.file_type_typescript_def, "d.cts" to R.drawable.file_type_typescript_def, "d.mts" to R.drawable.file_type_typescript_def,
    "test.ts" to R.drawable.file_type_test_ts, "spec.ts" to R.drawable.file_type_test_ts,
    "test.tsx" to R.drawable.file_type_test_jsx, "spec.tsx" to R.drawable.file_type_test_jsx,
    "test.js" to R.drawable.file_type_test_js, "spec.js" to R.drawable.file_type_test_js,
    "test.jsx" to R.drawable.file_type_test_jsx, "spec.jsx" to R.drawable.file_type_test_jsx,
    "tsx" to R.drawable.file_type_react_ts, "jsx" to R.drawable.file_type_react,
    "js" to R.drawable.file_type_javascript, "mjs" to R.drawable.file_type_javascript, "cjs" to R.drawable.file_type_javascript,
    "json" to R.drawable.file_type_json, "jsonc" to R.drawable.file_type_json,
    "md" to R.drawable.file_type_markdown, "markdown" to R.drawable.file_type_markdown,
    "py" to R.drawable.file_type_python, "pyi" to R.drawable.file_type_python,
    "go" to R.drawable.file_type_go,
    "sh" to R.drawable.file_type_console, "bash" to R.drawable.file_type_console, "zsh" to R.drawable.file_type_console,
    "yaml" to R.drawable.file_type_yaml, "yml" to R.drawable.file_type_yaml,
    "html" to R.drawable.file_type_html, "htm" to R.drawable.file_type_html,
    "css" to R.drawable.file_type_css, "scss" to R.drawable.file_type_sass, "sass" to R.drawable.file_type_sass,
    "sql" to R.drawable.file_type_database, "xml" to R.drawable.file_type_xml,
    "gradle" to R.drawable.file_type_gradle, "gradle.kts" to R.drawable.file_type_gradle,
    "swift" to R.drawable.file_type_swift,
    "c" to R.drawable.file_type_c, "h" to R.drawable.file_type_h,
    "cpp" to R.drawable.file_type_cpp, "cc" to R.drawable.file_type_cpp, "cxx" to R.drawable.file_type_cpp, "hpp" to R.drawable.file_type_hpp,
    "lock" to R.drawable.file_type_lock,
    "png" to R.drawable.file_type_image, "jpg" to R.drawable.file_type_image, "jpeg" to R.drawable.file_type_image,
    "gif" to R.drawable.file_type_image, "webp" to R.drawable.file_type_image, "ico" to R.drawable.file_type_image,
    "svg" to R.drawable.file_type_svg,
    "pdf" to R.drawable.file_type_pdf,
    "zip" to R.drawable.file_type_zip, "gz" to R.drawable.file_type_zip, "tar" to R.drawable.file_type_zip,
    "bz2" to R.drawable.file_type_zip, "xz" to R.drawable.file_type_zip, "7z" to R.drawable.file_type_zip,
    "txt" to R.drawable.file_type_document,
    "env" to R.drawable.file_type_settings, "log" to R.drawable.file_type_log,
    "tsconfig.json" to R.drawable.file_type_tsconfig,
    "dockerfile" to R.drawable.file_type_docker,
)

@DrawableRes
internal fun folderTypeIcon(path: String, expanded: Boolean = false): Int {
    val name = path.trimEnd('/').substringAfterLast('/').lowercase(Locale.ROOT)
    val icons = folderNames[name] ?: (R.drawable.file_type_folder to R.drawable.file_type_folder_open)
    return if (expanded) icons.second else icons.first
}

private val folderNames = buildMap {
    fun folder(closed: Int, open: Int, vararg names: String) { names.forEach { put(it, closed to open) } }
    folder(R.drawable.file_type_folder_src, R.drawable.file_type_folder_src_open, "src", "srcs", "source", "sources", "code")
    folder(R.drawable.file_type_folder_test, R.drawable.file_type_folder_test_open, "test", "tests", "__tests__", "spec", "specs")
    folder(R.drawable.file_type_folder_docs, R.drawable.file_type_folder_docs_open, "doc", "docs", "documentation")
    folder(R.drawable.file_type_folder_github, R.drawable.file_type_folder_github_open, ".github", "github")
    folder(R.drawable.file_type_folder_android, R.drawable.file_type_folder_android_open, "android")
    folder(R.drawable.file_type_folder_lib, R.drawable.file_type_folder_lib_open, "crates", "lib", "libs", "library", "vendor")
    folder(R.drawable.file_type_folder_scripts, R.drawable.file_type_folder_scripts_open, "script", "scripts", "scripting", "xtask")
    folder(R.drawable.file_type_folder_resource, R.drawable.file_type_folder_resource_open, "assets", "res", "resources", "resource")
    folder(R.drawable.file_type_folder_node, R.drawable.file_type_folder_node_open, "node_modules")
}

/** Decorative: the adjacent file name is the accessible label. Image preserves upstream colors. */
@Composable
internal fun FileTypeIcon(path: String, modifier: Modifier = Modifier, directory: Boolean = false, expanded: Boolean = false) {
    val icon = if (directory) folderTypeIcon(path, expanded) else fileTypeIcon(path)
    // Aries can override the system theme; choose upstream light artwork from
    // the actual surface rather than Android's system-night resource qualifier.
    val themed = if (MaterialTheme.colorScheme.surface.luminance() > .5f) when (icon) {
        R.drawable.file_type_toml -> R.drawable.file_type_toml_light
        else -> icon
    } else icon
    Image(painterResource(themed),
        contentDescription = null, modifier = modifier.size(20.dp))
}
