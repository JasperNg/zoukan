package zoukan.desktop.network

import org.junit.jupiter.api.Test
import java.io.File

/**
 * The app's HTTP must follow the proxy set in the OS: the run task and the installers start the
 * JVM with `java.net.useSystemProxies=true`, and no client may pick a proxy of its own (such as
 * `Proxy.NO_PROXY`, which would send traffic around the user's proxy).
 */
class SystemProxySourceScanTest {
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
        Regex("""\bNO_PROXY\b"""),
        Regex("""\bProxy\.Type\.DIRECT\b"""),
        Regex("""\.proxy\s*\("""),
        Regex("""\.proxySelector\s*\("""),
        Regex("""\bProxySelector\.setDefault\s*\("""),
        Regex("""\buseSystemProxies\b"""),
        Regex("""\b(http|https|socks)\.?[pP]roxy(Host|Port)\b"""),
    )

    @Test
    fun `no app client chooses its own proxy`() {
        check(File(root, "desktop/src/main/kotlin").isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = sourceDirs.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
            .flatMap { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                code(file.readText()).lineSequence().withIndex().flatMap { (i, line) ->
                    forbidden.filter { it.containsMatchIn(line) }.map { "$path:${i + 1}: ${line.trim()}" }
                }.toList()
            }
        check(violations.isEmpty()) {
            "HTTP must follow the OS proxy settings (java.net.useSystemProxies in desktop/build.gradle.kts):\n" +
                violations.joinToString("\n")
        }
    }

    @Test
    fun `the run task and the installers use the OS proxy settings`() {
        val build = File(root, "desktop/build.gradle.kts").readText()
        val jvmFlags = Regex("""val jvmFlags = listOf\((.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
            .find(build)?.groupValues?.get(1)
        checkNotNull(jvmFlags) { "No jvmFlags list in desktop/build.gradle.kts" }
        check("\"-Djava.net.useSystemProxies=true\"" in jvmFlags) {
            "jvmFlags (the run task's and the installers' JVM options) must set java.net.useSystemProxies=true"
        }
        check("applicationDefaultJvmArgs = jvmFlags" in build) { "The run task no longer uses jvmFlags" }
        check("extra[\"zoukanJvmFlags\"] = jvmFlags" in build) { "The installers no longer get jvmFlags" }
        val packaging = File(root, "desktop/packaging.gradle.kts").readText()
        check(Regex("""zoukanJvmFlags\b.*--java-options""").containsMatchIn(packaging)) {
            "packaging.gradle.kts no longer passes zoukanJvmFlags to jpackage as --java-options"
        }
    }

    /** [text] with comments blanked out, keeping line numbers. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence()
        .joinToString("\n") { line -> line.replace(Regex("""(^|\s)//.*$"""), "") }
}
