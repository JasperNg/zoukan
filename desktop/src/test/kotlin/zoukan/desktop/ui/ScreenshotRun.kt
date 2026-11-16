package zoukan.desktop.ui

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

/** A screenshot that differs from its golden (or has none): reported in build/reports/screenshots/report.md. */
data class ScreenshotFinding(val variant: String, val key: String, val message: String, val dir: String)

/**
 * One run of the screenshot check, set by `-Pzoukan.screenshots=verify|record` (see [fromProperty]).
 *
 * - `record` writes the golden of each curated key to `src/test/screenshots/<os>/<variant>/<key>.png`.
 * - `verify` compares with those goldens, on Linux only: the fonts and the rasteriser differ on other
 *   systems, so there it saves the capture under `build/reports/screenshots/` and skips the comparison.
 *
 * A key with several checks (such as the two reader screens) is taken from the first only.
 */
class ScreenshotRun(
    private val verify: Boolean,
    private val os: String,
    private val moduleDir: File = File("."),
) {
    /** Verify compares only where the goldens were recorded. */
    val compares: Boolean get() = verify && os == LINUX

    private val taken = mutableSetOf<String>()
    private var announced = false

    /** Takes [image] for [key] in [variant] ("1280x820/dark"); returns a finding when it differs from the golden. */
    fun check(image: BufferedImage, variant: String, key: String, masks: List<Rectangle>): ScreenshotFinding? {
        if (!taken.add("$variant|$key")) return null
        val name = fileName(key)
        val golden = File(moduleDir, "src/test/screenshots/$os/${variantDir(variant)}/$name.png")
        val reportDir = File(moduleDir, "build/reports/screenshots/${variantDir(variant)}/$name")
        if (!verify) {
            golden.parentFile.mkdirs()
            ImageIO.write(image, "png", golden)
            return null
        }
        if (!compares) {
            if (!announced) {
                announced = true
                println("Screenshots: verify compares only on Linux (this is $os); saving captures in build/reports/screenshots, no comparison")
            }
            reportDir.mkdirs()
            ImageIO.write(image, "png", File(reportDir, "actual.png"))
            return null
        }
        if (!golden.isFile) {
            reportDir.mkdirs()
            ImageIO.write(image, "png", File(reportDir, "actual.png"))
            return ScreenshotFinding(variant, key, "no golden at ${golden.path}; record it with -Pzoukan.screenshots=record", reportDir.path)
        }
        val expected = ImageIO.read(golden)
        val result = ScreenshotDiff.compare(expected, image, masks = masks)
        if (result.passed) return null
        ScreenshotDiff.writeFailure(reportDir, expected, image, result)
        return ScreenshotFinding(variant, key, result.message, reportDir.path)
    }

    /**
     * Writes this run's section and rebuilds `build/reports/screenshots/report.md` from the sections of
     * every smoke JVM (the default and the small window run side by side), under a file lock.
     */
    fun writeReport(size: String, findings: List<ScreenshotFinding>) {
        val dir = File(moduleDir, "build/reports/screenshots").apply { mkdirs() }
        val section = buildString {
            appendLine("## $size")
            appendLine()
            val mode = when {
                !verify -> "record"
                compares -> "verify"
                else -> "verify (skipped on $os: it compares only on Linux)"
            }
            appendLine("Mode: $mode. ${taken.size} screenshots taken, ${findings.size} differ.")
            appendLine()
            if (findings.isNotEmpty()) {
                appendLine("| Variant | Key | Result | Images |")
                appendLine("|---|---|---|---|")
                findings.forEach { appendLine("| ${it.variant} | ${it.key} | ${it.message.replace("|", "\\|")} | `${it.dir}` |") }
                appendLine()
            }
        }
        File(dir, "report-$size.md").writeText(section)
        RandomAccessFile(File(dir, "report.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val sections = dir.listFiles { f -> f.name.startsWith("report-") && f.extension == "md" }.orEmpty().sortedBy { it.name }
                File(dir, "report.md").writeText("# Screenshot report\n\n" + sections.joinToString("\n") { it.readText() })
            }
        }
    }

    companion object {
        const val LINUX = "linux"

        /** Null when the property is unset: no capture, no cost. */
        fun fromProperty(
            value: String? = System.getProperty("zoukan.screenshots"),
            osName: String = System.getProperty("os.name"),
            moduleDir: File = File("."),
        ): ScreenshotRun? {
            if (value.isNullOrBlank()) return null
            require(value == "verify" || value == "record") { "zoukan.screenshots must be verify or record, not \"$value\"" }
            return ScreenshotRun(value == "verify", osOf(osName), moduleDir)
        }

        fun osOf(osName: String): String = when {
            osName.contains("linux", ignoreCase = true) -> LINUX
            osName.contains("windows", ignoreCase = true) -> "windows"
            osName.contains("mac", ignoreCase = true) -> "macos"
            else -> osName.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        }

        /** `tab:Library` is `tab_Library.png`: the key with `:` and `/` replaced by `_`. */
        fun fileName(key: String): String = key.replace(':', '_').replace('/', '_')

        /** `1280x820/dark` is the directory `1280x820-dark`. */
        fun variantDir(variant: String): String = variant.replace('/', '-')
    }
}
