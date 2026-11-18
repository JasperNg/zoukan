package zoukan.desktop.contract

import java.io.File

/**
 * The repository's own source files, for the tests that check the desktop code against the
 * Android sources and the contract documents (`desktop/PARITY.md`, `desktop/THREAT-MODEL.md`).
 * Gradle runs the desktop tests with the desktop folder as working directory, so the repository
 * root is its parent (as `AppSharedRewritesTest` and `ProjectOutputs` assume).
 */
internal object RepositorySources {

    val root: File by lazy {
        val dir = File(System.getProperty("user.dir")).absoluteFile.parentFile
        check(File(dir, "settings.gradle.kts").isFile && File(dir, "desktop/PARITY.md").isFile) {
            "The repository root was expected at $dir (the parent of the test's working directory)"
        }
        dir
    }

    /** Folders never walked: build output, VCS and IDE state. */
    private val SKIPPED = setOf("build", ".git", ".gradle", ".idea", "node_modules", ".kotlin")

    /** Every file under the root, as a path relative to it with `/` separators, build output left out. */
    val allPaths: List<String> by lazy {
        root.walkTopDown()
            .onEnter { it == root || it.name !in SKIPPED }
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
    }

    /** Every folder under the root, as [allPaths] gives files. */
    val allDirs: Set<String> by lazy {
        allPaths.flatMap { path ->
            val parts = path.split('/').dropLast(1)
            parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
        }.toSet()
    }

    fun text(path: String): String = File(root, path).readText()

    /** The Kotlin files under [dir] (relative to the root), build output left out. */
    fun kotlinFiles(dir: String): List<String> = allPaths.filter { it.startsWith("$dir/") && it.endsWith(".kt") }

    /** Main (not test) source files of every module: Kotlin, Java and SQLDelight. */
    val mainSourcePaths: List<String> by lazy {
        allPaths.filter { path ->
            (path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".sq") || path.endsWith(".sqm")) &&
                "/src/" in path && !isTestSource(path)
        }
    }

    /** Test source files of every module (src/test, src/commonTest, src/desktopTest, ...). */
    fun isTestSource(path: String): Boolean = Regex("""/src/[A-Za-z]*[tT]est[A-Za-z]*/""").containsMatchIn(path)
}

/** Fails listing every problem, one per line, so one run shows all of them. */
internal fun assertNoProblems(what: String, problems: List<String>) {
    if (problems.isNotEmpty()) {
        throw AssertionError("$what (${problems.size}):\n" + problems.joinToString("\n") { "  - $it" })
    }
}
