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
 *
 * The harness keeps its own profile elsewhere, see [harness]: `%LOCALAPPDATA%\Zoukan\harness` on Windows.
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

    /** Coil's disk cache: images fetched by address (extension icons, tracker covers, ...) */
    val images = File(root, "cache/images")

    /** FFmpeg's natives (AVIF and HEIF images), unpacked by JavaCPP */
    val javacppCache = File(root, "cache/javacpp")

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

        /**
         * The harness profile, outside the data folder (and on Windows outside the roaming profile).
         * With a data-folder override it is the sibling `<name>-harness`.
         */
        fun harness(): ZoukanDirs {
            val override = System.getProperty("zoukan.dataDir") ?: System.getenv("ZOUKAN_DATA_DIR")
            if (!override.isNullOrBlank()) {
                val data = File(override).absoluteFile
                return ZoukanDirs(File(data.parentFile, data.name + "-harness"))
            }
            val home = System.getProperty("user.home")
            val root = when (Platform.current) {
                Platform.WINDOWS -> File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local", "Zoukan\\harness")
                Platform.MACOS -> File(home, "Library/Application Support/Zoukan-harness")
                Platform.LINUX -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "zoukan-harness")
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
