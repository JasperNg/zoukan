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
 *
 * [localRoot] holds the caches that only make sense on this computer (Chromium's natives and
 * profile, Coil's image cache, FFmpeg's natives, the chapter cache). It is [root] everywhere except
 * the default Windows data folder, where it is `%LOCALAPPDATA%\Zoukan-local`, so a roaming profile
 * does not carry them ([CacheFolders] moves an older version's folders there). Covers stay in
 * [root]: `cache/covers/custom` holds the user's custom covers.
 *
 * @param keptInRoot caches (paths relative to both roots, see [LOCAL_CACHES]) that could not move
 * to [localRoot] and stay in [root] for this run
 */
class ZoukanDirs(
    val root: File,
    val localRoot: File = root,
    private val keptInRoot: Set<String> = emptySet(),
) {
    val database = File(root, "zoukan.db")
    val extensions = File(root, "extensions")
    val android = File(root, "android")
    val cookies = File(root, "cookies.json")

    /** Chromium's natives, downloaded by `ChromiumNatives` */
    val browser = local(BROWSER_NATIVES)

    /** Chromium's profile, shared by the browser windows and extensions' WebViews */
    val browserCache = local(BROWSER_PROFILE)
    val reports = File(root, "reports")
    val secrets = File(root, "secrets")
    val backups = File(root, "backups")
    val covers = File(root, "cache/covers")

    /** Coil's disk cache: images fetched by address (extension icons, tracker covers, ...) */
    val images = local(IMAGES)

    /** FFmpeg's natives (AVIF and HEIF images), unpacked by JavaCPP */
    val javacppCache = local(JAVACPP)

    /** The chapter cache: unpacked RAR, 7z and TAR chapters */
    val archives = local(ARCHIVES)

    // Phase 7: the log file, crash reports and downloaded updates
    val logs = File(root, "logs")
    val crashes = File(root, "crashes")
    val updates = File(root, "updates")

    fun create(): ZoukanDirs = apply {
        listOf(root, extensions, android, browser, browserCache, reports).forEach { it.mkdirs() }
    }

    /** Whether the caches have a folder of their own, outside [root]. */
    val hasLocalRoot: Boolean get() = localRoot.absoluteFile.normalize() != root.absoluteFile.normalize()

    /** These folders, with [relative] caches (of [LOCAL_CACHES]) kept in [root]. */
    fun keepingInRoot(relative: Set<String>): ZoukanDirs = ZoukanDirs(root, localRoot, keptInRoot + relative)

    private fun local(relative: String) = File(if (relative in keptInRoot) root else localRoot, relative)

    companion object {
        const val BROWSER_NATIVES = "browser"
        const val BROWSER_PROFILE = "cache/browser"
        const val IMAGES = "cache/images"
        const val JAVACPP = "cache/javacpp"
        const val ARCHIVES = "cache/archives"

        /** The caches that live in [localRoot], by their path relative to it (and to [root]). */
        val LOCAL_CACHES = listOf(BROWSER_NATIVES, BROWSER_PROFILE, IMAGES, JAVACPP, ARCHIVES)

        fun default(): ZoukanDirs = default(
            Platform.current,
            System.getProperty("zoukan.dataDir"),
            System::getenv,
            System.getProperty("user.home"),
        )

        /**
         * The data folder for [platform]: [dataDirProperty] (`-Dzoukan.dataDir`) or the
         * `ZOUKAN_DATA_DIR` variable, else the platform's default. Only the default Windows folder
         * gets a [localRoot] of its own; an overridden folder keeps everything inside it.
         */
        internal fun default(platform: Platform, dataDirProperty: String?, env: (String) -> String?, home: String): ZoukanDirs {
            val override = dataDirProperty ?: env("ZOUKAN_DATA_DIR")
            if (!override.isNullOrBlank()) return ZoukanDirs(File(override).absoluteFile)
            return when (platform) {
                Platform.WINDOWS -> ZoukanDirs(
                    root = File(env("APPDATA") ?: "$home\\AppData\\Roaming", "Zoukan"),
                    localRoot = File(env("LOCALAPPDATA").orEmpty().ifBlank { "$home\\AppData\\Local" }, "Zoukan-local"),
                )
                Platform.MACOS -> ZoukanDirs(File(home, "Library/Application Support/Zoukan"))
                Platform.LINUX -> ZoukanDirs(File(env("XDG_DATA_HOME") ?: "$home/.local/share", "zoukan"))
            }
        }

        /**
         * The harness profile, outside the data folder (and on Windows outside the roaming profile).
         * With a data-folder override it is the sibling `<name>-harness`. Its caches stay inside it.
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
