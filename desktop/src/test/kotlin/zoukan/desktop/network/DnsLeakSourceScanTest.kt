package zoukan.desktop.network

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Fails when desktop code makes an HTTP client or connection of its own. Such a client resolves
 * names through the system's DNS, which leaks every host name when DNS over HTTPS is on; clients
 * must come from NetworkHelper (`client`, `plainClient` or their `newBuilder()`).
 */
class DnsLeakSourceScanTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** The sources the desktop app compiles (Android-only source sets left out). */
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "desktop/build/generated/app-shared",
        "core/common/src/commonMain",
        "core/common/src/desktopMain",
        "data/src/commonMain",
        "data/src/desktopMain",
        "source-api/src/commonMain",
        "source-api/src/desktopMain",
        "domain/src/commonMain",
        "extension-jvm/src/main",
        "android-compat/src/main/java/xyz",
        "android-compat/src/main/java/suwayomi",
    )

    private val forbidden = listOf(
        Regex("""\bOkHttpClient\(\)"""),
        Regex("""\bOkHttpClient\.Builder\(\)"""),
        Regex("""\bDns\.SYSTEM\b"""),
        Regex("""\bHttpURLConnection\b"""),
        Regex("""\bHttpsURLConnection\b"""),
        Regex("""\bjava\.net\.http\."""),
        Regex("""\bHttpClient\.new(HttpClient|Builder)\b"""),
        Regex("""\.openConnection\("""),
        Regex("""\bURL\([^)]*\)\.(openStream|readText|readBytes)\("""),
        Regex("""\bNetHttpTransport\b"""),
    )

    /** File (relative to the root) to the patterns it may use, with the reason. */
    private val allowed = mapOf(
        // The one place clients are built, with DNS over HTTPS
        "core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt" to setOf("""\bOkHttpClient\.Builder\(\)"""),
        // The fallback for self-hosted servers' names, after DNS over HTTPS
        "core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/network/DohProviders.kt" to setOf("""\bDns\.SYSTEM\b"""),
        // What extensions' own clients become (BytecodeEditor): the app's DNS, the system's only
        // while the app has set none
        "android-compat/src/main/java/xyz/nulldev/androidcompat/network/ExtensionHttp.java" to
            setOf("""\bOkHttpClient\.Builder\(\)""", """\bDns\.SYSTEM\b"""),
        // A jar held in memory, no network
        "extension-jvm/src/main/kotlin/zoukan/extension/ExtensionClassLoader.kt" to setOf("""\.openConnection\("""),
    )

    @Test
    fun `no HTTP client or connection bypasses NetworkHelper`() {
        val dirs = sourceDirs.map { File(root, it) }
        check(File(root, "desktop/src/main/kotlin").isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = dirs.filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
            .flatMap { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val ok = allowed[path].orEmpty()
                code(file.readText()).lineSequence().withIndex().flatMap { (i, line) ->
                    forbidden.filter { it.pattern !in ok && it.containsMatchIn(line) }
                        .map { "$path:${i + 1}: ${line.trim()}" }
                }.toList()
            }
        check(violations.isEmpty()) {
            "Use NetworkHelper.client or plainClient (or their newBuilder()) instead, so DNS over HTTPS applies:\n" +
                violations.joinToString("\n")
        }
    }

    /** [text] with comments blanked out, keeping line numbers. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence()
        .joinToString("\n") { line -> line.replace(Regex("""(^|\s)//.*$"""), "") }
}
