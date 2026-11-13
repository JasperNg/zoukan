package zoukan.desktop.app

import java.util.Properties

/** Zoukan's own version and where its releases are published, written by the build (zoukanBuildInfo). */
object AppVersion {
    private val properties by lazy {
        Properties().apply {
            AppVersion::class.java.getResourceAsStream("/zoukan-build.properties")?.use { load(it) }
        }
    }

    /** For example 1.0.0; 0.0.0 when the build info is missing. */
    val name: String get() = properties.getProperty("version") ?: "0.0.0"

    val version: Version get() = Version.parse(name) ?: Version(listOf(0, 0, 0))

    /** The GitHub repository whose releases the update check reads, owner/name. */
    val releaseRepo: String get() = properties.getProperty("releaseRepo") ?: "JasperNg/zoukan"

    /** Releases of the desktop app are tagged with this prefix and the version. */
    const val TAG_PREFIX = "desktop-v"
}

/** A dotted version such as 1.2.3, compared part by part (missing parts count as 0). */
data class Version(val parts: List<Int>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString() = parts.joinToString(".")

    companion object {
        private val pattern = Regex("""^v?(\d+(?:\.\d+){0,3})$""")

        /** Null when [text] is not a version, including a part too long for an Int. */
        fun parse(text: String): Version? {
            val digits = pattern.find(text.trim())?.groupValues?.get(1) ?: return null
            return Version(digits.split('.').map { it.toIntOrNull() ?: return null })
        }
    }
}
