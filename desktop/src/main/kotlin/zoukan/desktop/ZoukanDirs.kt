package zoukan.desktop

import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
 * On macOS the disposable folders ([disposableFolders]: logs, crash reports, reports and the caches
 * that hold fetched pages and images) end in `.noindex`, so Spotlight does not index them. A profile
 * that has them under the old name gets them renamed on first use, see [DataFolderPrivacy.resolve].
 * Folders with the user's data keep their names. macOS has no separate [localRoot], so
 * [CacheFolders] never moves a `.noindex` folder.
 *
 * @param keptInRoot caches (paths relative to both roots, see [LOCAL_CACHES]) that could not move
 * to [localRoot] and stay in [root] for this run
 */
class ZoukanDirs internal constructor(
    val root: File,
    val localRoot: File = root,
    private val keptInRoot: Set<String> = emptySet(),
    /** Whether the disposable folders take the `.noindex` names: on macOS. */
    internal val hidesFromSpotlight: Boolean,
    /**
     * Whether [root] is Zoukan's own per-platform folder, not one the user chose with the data
     * folder override or passed in. Only then, or when Zoukan made it ([rootIsNew]), is it made
     * private, see [DataFolderPrivacy.protect].
     */
    internal val isDefaultRoot: Boolean = false,
    /** Whether [root] did not exist when this process first resolved it, see [default]. */
    private val rootWasMissing: Boolean = false,
) {
    constructor(root: File, localRoot: File = root) :
        this(root, localRoot, emptySet(), hidesFromSpotlight = Platform.current == Platform.MACOS)

    private val folderNotes = Collections.synchronizedList(mutableListOf<String>())

    /** A disposable folder [name] in [parent], resolved (and renamed if needed) on first use. */
    private fun disposable(parent: File, name: String): Lazy<File> = lazy { resolved(File(parent, name)) }

    /** A disposable cache in [localRoot] (or [root], see [keptInRoot]), resolved on first use. */
    private fun disposableLocal(relative: String): Lazy<File> = lazy { resolved(local(relative)) }

    /** [plain], or on macOS its `.noindex` name, see [DataFolderPrivacy.resolve]. */
    private fun resolved(plain: File): File = if (hidesFromSpotlight) {
        DataFolderPrivacy.resolve(plain.parentFile, plain.name, plain.name + DataFolderPrivacy.NOINDEX, folderNotes::add)
    } else {
        plain
    }

    val database = File(root, "zoukan.db")
    val extensions = File(root, "extensions")
    val android = File(root, "android")
    val cookies = File(root, "cookies.json")

    /** Chromium's natives, downloaded by `ChromiumNatives` */
    val browser = local(BROWSER_NATIVES)

    /** Chromium's profile, shared by the browser windows and extensions' WebViews */
    val browserCache = local(BROWSER_PROFILE)
    val reports: File by disposable(root, "reports")
    val secrets = File(root, "secrets")
    val backups = File(root, "backups")

    /** Covers: `custom/` holds the user's own covers, so this one keeps its name. */
    val covers = File(root, "cache/covers")

    /** Coil's disk cache: images fetched by address (extension icons, tracker covers, ...) */
    val images: File by disposableLocal(IMAGES)

    /** FFmpeg's natives (AVIF and HEIF images), unpacked by JavaCPP */
    val javacppCache: File by disposableLocal(JAVACPP)

    /** The chapter cache: unpacked RAR, 7z and TAR chapters */
    val archives: File by disposableLocal(ARCHIVES)

    /**
     * The app's temporary folder ([AppTempFiles]: decrypted or downloaded files for a moment),
     * beside the chapter cache, wherever that is kept
     */
    val tempFiles: File by lazy { resolved(File(local(ARCHIVES).parentFile, "tmp")) }

    /** The emulated Android app's cache folder (extensions' own cache files) */
    val androidCache: File by disposable(File(android, "android-compat/appdata"), "cache")

    // Phase 7: the log file, crash reports and downloaded updates
    val logs: File by disposable(root, "logs")
    val crashes: File by disposable(root, "crashes")
    val updates = File(root, "updates")

    /** Folders whose files can be lost without harm and that may hold private activity. */
    internal val disposableFolders: List<File>
        get() = listOf(images, archives, tempFiles, javacppCache, androidCache, logs, crashes, reports)

    /** Keeps [note] for [takeFolderNotes]. */
    internal fun recordFolderNote(note: String) {
        folderNotes.add(note)
    }

    /** What resolving the folders could not do (a rename that failed), to log once logging is set up. */
    internal fun takeFolderNotes(): List<String> = synchronized(folderNotes) {
        folderNotes.toList().also { folderNotes.clear() }
    }

    @Volatile
    private var createdRoot: Boolean = false

    /**
     * Whether Zoukan made [root] on this start: it was missing when this process first resolved it
     * (the one-instance lock may create it before [create] runs), or [createRoot] made it.
     */
    internal val rootIsNew: Boolean
        get() = rootWasMissing || createdRoot

    /** Creates [root] alone, noting whether it was made now ([rootIsNew]). */
    internal fun createRoot(): ZoukanDirs = apply {
        if (root.mkdirs()) createdRoot = true
    }

    fun create(): ZoukanDirs = apply {
        createRoot()
        listOf(extensions, android, browser, browserCache, reports).forEach { it.mkdirs() }
    }

    /** Whether the caches have a folder of their own, outside [root]. */
    val hasLocalRoot: Boolean get() = localRoot.absoluteFile.normalize() != root.absoluteFile.normalize()

    /** These folders, with [relative] caches (of [LOCAL_CACHES]) kept in [root]. */
    fun keepingInRoot(relative: Set<String>): ZoukanDirs =
        ZoukanDirs(root, localRoot, keptInRoot + relative, hidesFromSpotlight, isDefaultRoot, rootWasMissing = rootIsNew)
            .also { copy -> takeFolderNotes().forEach(copy::recordFolderNote) }

    private fun local(relative: String) = File(if (relative in keptInRoot) root else localRoot, relative)

    companion object {
        const val BROWSER_NATIVES = "browser"
        const val BROWSER_PROFILE = "cache/browser"
        const val IMAGES = "cache/images"
        const val JAVACPP = "cache/javacpp"
        const val ARCHIVES = "cache/archives"

        /** The caches that live in [localRoot], by their path relative to it (and to [root]). */
        val LOCAL_CACHES = listOf(BROWSER_NATIVES, BROWSER_PROFILE, IMAGES, JAVACPP, ARCHIVES)

        /** Per absolute path: whether the folder was missing the first time this process looked. */
        private val missingAtFirstLook = ConcurrentHashMap<String, Boolean>()

        /** A root the user chose with the data folder override. */
        private fun chosen(root: File, hidesFromSpotlight: Boolean = Platform.current == Platform.MACOS): ZoukanDirs {
            val missing = try {
                missingAtFirstLook.computeIfAbsent(root.path) { !root.exists() }
            } catch (e: Exception) {
                false
            }
            return ZoukanDirs(root, hidesFromSpotlight = hidesFromSpotlight, isDefaultRoot = false, rootWasMissing = missing)
        }

        fun default(): ZoukanDirs = default(
            Platform.current,
            System.getProperty("zoukan.dataDir"),
            System::getenv,
            System.getProperty("user.home"),
        )

        /**
         * The data folder for [platform]: [dataDirProperty] (`-Dzoukan.dataDir`) or the
         * `ZOUKAN_DATA_DIR` variable (a folder the user chose), else the platform's default (Zoukan's
         * own). Only the default Windows folder gets a [localRoot] of its own; an overridden folder
         * keeps everything inside it.
         */
        internal fun default(platform: Platform, dataDirProperty: String?, env: (String) -> String?, home: String): ZoukanDirs {
            val override = dataDirProperty ?: env("ZOUKAN_DATA_DIR")
            val hides = platform == Platform.MACOS
            if (!override.isNullOrBlank()) return chosen(File(override).absoluteFile, hides)
            return when (platform) {
                Platform.WINDOWS -> ZoukanDirs(
                    root = File(env("APPDATA") ?: "$home\\AppData\\Roaming", "Zoukan"),
                    localRoot = File(env("LOCALAPPDATA").orEmpty().ifBlank { "$home\\AppData\\Local" }, "Zoukan-local"),
                    hidesFromSpotlight = hides,
                    isDefaultRoot = true,
                )
                Platform.MACOS -> ZoukanDirs(File(home, "Library/Application Support/Zoukan"), hidesFromSpotlight = hides, isDefaultRoot = true)
                Platform.LINUX -> ZoukanDirs(File(env("XDG_DATA_HOME") ?: "$home/.local/share", "zoukan"), hidesFromSpotlight = hides, isDefaultRoot = true)
            }
        }

        /**
         * The harness profile, outside the data folder (and on Windows outside the roaming profile).
         * With a data-folder override it is the sibling `<name>-harness`, which counts as chosen by
         * the user (made private only when it is created); without one it is Zoukan's own folder.
         * Its caches stay inside it.
         */
        fun harness(): ZoukanDirs {
            val override = System.getProperty("zoukan.dataDir") ?: System.getenv("ZOUKAN_DATA_DIR")
            if (!override.isNullOrBlank()) {
                val data = File(override).absoluteFile
                return chosen(File(data.parentFile, data.name + "-harness"))
            }
            val home = System.getProperty("user.home")
            val root = when (Platform.current) {
                Platform.WINDOWS -> File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local", "Zoukan\\harness")
                Platform.MACOS -> File(home, "Library/Application Support/Zoukan-harness")
                Platform.LINUX -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "zoukan-harness")
            }
            return ZoukanDirs(root, hidesFromSpotlight = Platform.current == Platform.MACOS, isDefaultRoot = true)
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
