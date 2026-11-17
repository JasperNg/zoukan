package zoukan.desktop.app

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Deleting downloads throws when the storage drive is unplugged (StorageUnavailableException).
 * Fails when a screen deletes downloads in a function that neither launches it with
 * `launchReporting` nor catches the failure, so the user would see nothing and the error would go
 * to the crash report.
 */
class DeleteFailureReportedScanTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val deleteCall = Regex("""downloadManager\.(delete|deleteManga)\(""")
    private val function = Regex("""^\s*(?:(?:private|internal|override|public)\s+)*(?:suspend\s+)?fun\s""")

    @Test
    fun `every screen that deletes downloads reports a failure`() {
        val ui = File(root, "desktop/src/main/kotlin/zoukan/desktop/ui")
        check(ui.isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = ui.walk().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val lines = file.readLines()
            lines.withIndex().filter { (_, line) -> deleteCall.containsMatchIn(line) }.mapNotNull { (i, line) ->
                // The function the call is in, from its declaration down to the call
                val start = (i downTo 0).firstOrNull { function.containsMatchIn(lines[it]) } ?: 0
                val body = lines.subList(start, i + 1)
                // A suspend function hands the failure to its caller, which the screen tests check
                if (Regex("""\bsuspend\s+fun\s""").containsMatchIn(lines[start])) return@mapNotNull null
                val reported = body.any { "launchReporting" in it || "catch (" in it || "try {" in it.trim() }
                if (reported) null else "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}: ${line.trim()}"
            }
        }.toList()
        check(violations.isEmpty()) {
            "Launch these with launchReporting or catch the failure and show it:\n" + violations.joinToString("\n")
        }
    }
}
