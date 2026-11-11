package zoukan.desktop

import java.io.File

/**
 * Where Zoukan keeps its files.
 *
 * - Windows: `%APPDATA%\Zoukan`
 * - macOS: `~/Library/Application Support/Zoukan`
 * - Linux (development only): `$XDG_DATA_HOME/zoukan` or `~/.local/share/zoukan`
 *
 * `-Dzoukan.dataDir=<path>` or the `ZOUKAN_DATA_DIR` environment variable overrides it, which the
 * harness and tests use to keep a separate profile.
 */
class ZoukanDirs(val root: File) {
    val database = File(root, "zoukan.db")
    val extensions = File(root, "extensions")
    val android = File(root, "android")
    val cookies = File(root, "cookies.json")
    val browser = File(root, "browser")
    val browserCache = File(root, "cache/browser")
    val reports = File(root, "reports")
    val secrets = File(root, "secrets")
    val backups = File(root, "backups")
    val covers = File(root, "cache/covers")

    // Phase 7: the log file, crash reports and downloaded updates
    val logs = File(root, "logs")
    val crashes = File(root, "crashes")
    val updates = File(root, "updates")

    fun create(): ZoukanDirs = apply {
        listOf(root, extensions, android, browser, browserCache, reports).forEach { it.mkdirs() }
    }

    companion object {
        fun default(): ZoukanDirs {
            val override = System.getProperty("zoukan.dataDir") ?: System.getenv("ZOUKAN_DATA_DIR")
            if (!override.isNullOrBlank()) return ZoukanDirs(File(override).absoluteFile)
            val home = System.getProperty("user.home")
            val root = when (Platform.current) {
                Platform.WINDOWS -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "Zoukan")
                Platform.MACOS -> File(home, "Library/Application Support/Zoukan")
                Platform.LINUX -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "zoukan")
            }
            return ZoukanDirs(root)
        }
    }
}

enum class Platform {
    WINDOWS,
    MACOS,
    LINUX,
    ;

    companion object {
        val current: Platform by lazy {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.startsWith("windows") -> WINDOWS
                os.startsWith("mac") -> MACOS
                else -> LINUX
            }
        }

        /** For example windows-x64 or macos-arm64, as the Phase 0 reports name platforms. */
        val id: String by lazy {
            val arch = when (val a = System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                else -> a
            }
            "${current.name.lowercase()}-$arch"
        }
    }
}
