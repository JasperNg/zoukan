package zoukan.desktop.harness

import zoukan.desktop.Platform
import java.io.File
import java.time.OffsetDateTime

/** A Markdown report, printed as it is written and saved at the end. */
class Report(private val title: String) {
    private val text = StringBuilder()

    init {
        line("# $title")
        line()
        line("- Date: ${OffsetDateTime.now()}")
        line("- Platform: ${Platform.id} (${System.getProperty("os.name")} ${System.getProperty("os.version")})")
        line("- Java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
        line()
    }

    fun line(s: String = "") {
        text.append(s).append('\n')
        println(s)
    }

    fun table(header: List<String>, rows: List<List<String>>) {
        fun row(cells: List<String>) = cells.joinToString(" | ", "| ", " |") { it.replace("|", "\\|").replace("\n", " ") }
        line(row(header))
        line(header.joinToString(" | ", "| ", " |") { "---" })
        rows.forEach { line(row(it)) }
        line()
    }

    fun save(file: File) {
        file.parentFile.mkdirs()
        file.writeText(text.toString())
    }
}
