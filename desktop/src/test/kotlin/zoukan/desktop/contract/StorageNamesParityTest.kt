package zoukan.desktop.contract

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The file and folder names the desktop uses in the storage folder (downloads, the local
 * source, automatic backups) are the Android app's, each read from where the Android source
 * and the desktop source define it. A name that differs on purpose, or is waiting for a
 * decision, is a [KnownNameDifference]; it must still differ exactly as recorded.
 *
 * The chapter and entry folder names themselves (DiskUtil.buildValidFilename, the scanlator
 * prefix and the URL hash) are behaviour, not constants, and are checked by the download tests.
 * Covers and custom covers live in the app's cache, not the storage folder, so they are not here.
 */
class StorageNamesParityTest {

    @Test
    fun `the storage folder names are Android's`() {
        check(PAIRS.size >= 14) { "Only ${PAIRS.size} names are compared" }
        assertNoProblems("Storage folder names that differ from Android", StorageNames.problems(PAIRS))
    }

    // --- Self-tests on text in memory ---

    private val androidText = """private const val DOWNLOADS_PATH = "downloads""""

    @Test
    fun `self-test - equal names pass and a wrong name is caught`() {
        fun pair(desktop: String, known: KnownNameDifference? = null) = NamePair(
            "Downloads folder",
            StorageNames.from("Android.kt", androidText, Regex("""DOWNLOADS_PATH = "([^"]+)"""")),
            StorageNames.from("Desktop.kt", desktop, Regex("""const val DOWNLOADS = "([^"]+)"""")),
            known,
        )
        StorageNames.problems(listOf(pair("""const val DOWNLOADS = "downloads""""))).shouldBeEmpty()
        StorageNames.problems(listOf(pair("""const val DOWNLOADS = "download""""))).single() shouldContain
            "desktop \"download\", Android \"downloads\""
        // A recorded difference passes while it holds, and is stale once the names agree
        val known = KnownNameDifference("download", "downloads", reviewed = true, why = "test")
        StorageNames.problems(listOf(pair("""const val DOWNLOADS = "download"""", known))).shouldBeEmpty()
        StorageNames.problems(listOf(pair("""const val DOWNLOADS = "downloads"""", known))).single() shouldContain "stale"
    }

    @Test
    fun `self-test - a definition that moved or appears twice fails instead of passing`() {
        val missing = NamePair("x", StorageNames.from("A.kt", androidText, Regex("""NOPE = "([^"]+)"""")), { "downloads" }, null)
        StorageNames.problems(listOf(missing)).single() shouldContain "expected one match"
        assertThrows<IllegalStateException> { StorageNames.from("A.kt", "$androidText\n$androidText", Regex("""DOWNLOADS_PATH = "([^"]+)""""))() }
    }

    @Test
    fun `self-test - every source path still exists`() {
        PAIRS.forEach { pair ->
            pair.android()
            pair.desktop()
        }
        PAIRS.map { it.what }.toSet().size shouldBe PAIRS.size
    }

    private companion object {
        const val STORAGE_MANAGER = "domain/src/androidMain/kotlin/tachiyomi/domain/storage/service/StorageManager.kt"
        const val ZOUKAN_STORAGE = "desktop/src/main/kotlin/zoukan/desktop/storage/ZoukanStorage.kt"
        const val DOWNLOADER = "app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt"
        const val DOWNLOAD_PATHS = "desktop/src/main/kotlin/zoukan/desktop/download/DownloadPaths.kt"
        const val CHAPTER_DOWNLOADER = "desktop/src/main/kotlin/zoukan/desktop/download/ChapterDownloader.kt"
        const val COMIC_INFO = "core-metadata/src/main/java/tachiyomi/core/metadata/comicinfo/ComicInfo.kt"
        const val COMIC_INFO_XML = "desktop/src/main/kotlin/zoukan/desktop/download/ComicInfoXml.kt"
        const val ANDROID_LOCAL_SOURCE = "source-local/src/androidMain/kotlin/tachiyomi/source/local/LocalSource.kt"
        const val ANDROID_LOCAL_COVERS = "source-local/src/androidMain/kotlin/tachiyomi/source/local/image/LocalCoverManager.kt"
        const val DESKTOP_LOCAL_SOURCE = "desktop/src/main/kotlin/zoukan/desktop/local/LocalSource.kt"
        const val BACKUP_CREATOR = "app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt"
        const val DESKTOP_BACKUPS = "desktop/src/main/kotlin/zoukan/desktop/backup/DesktopBackups.kt"
        const val DISK_UTIL = "core/common/src/androidMain/kotlin/eu/kanade/tachiyomi/util/storage/DiskUtil.kt"

        fun file(path: String, regex: String) = StorageNames.from(path, null, Regex(regex))

        val PAIRS = listOf(
            NamePair("Downloads folder", file(STORAGE_MANAGER, """DOWNLOADS_PATH = "([^"]+)""""), file(ZOUKAN_STORAGE, """const val DOWNLOADS = "([^"]+)"""")),
            NamePair("Local source folder", file(STORAGE_MANAGER, """LOCAL_SOURCE_PATH = "([^"]+)""""), file(ZOUKAN_STORAGE, """const val LOCAL = "([^"]+)"""")),
            NamePair(
                "Automatic backups folder",
                file(STORAGE_MANAGER, """AUTOMATIC_BACKUPS_PATH = "([^"]+)""""),
                file(ZOUKAN_STORAGE, """const val AUTOMATIC_BACKUPS = "([^"]+)""""),
            ),
            NamePair("Unfinished download suffix", file(DOWNLOADER, """TMP_DIR_SUFFIX = "([^"]+)""""), file(DOWNLOAD_PATHS, """TMP_SUFFIX = "([^"]+)"""")),
            NamePair(
                "Downloaded chapter archive extension",
                file(DOWNLOADER, """zip\.renameTo\("\${'$'}dirname\.(\w+)"\)"""),
                file(CHAPTER_DOWNLOADER, """File\(mangaDir, "\${'$'}chapterName\.(\w+)"\)"""),
            ),
            NamePair("ComicInfo file", file(COMIC_INFO, """const val COMIC_INFO_FILE = "([^"]+)""""), file(COMIC_INFO_XML, """const val FILE = "([^"]+)"""")),
            NamePair(
                "Local source ComicInfo archive",
                file(ANDROID_LOCAL_SOURCE, """const val COMIC_INFO_ARCHIVE = "([^"]+)""""),
                file(DESKTOP_LOCAL_SOURCE, """const val COMIC_INFO_ARCHIVE = "([^"]+)""""),
            ),
            NamePair(
                "Local source encrypted cover",
                file(ANDROID_LOCAL_COVERS, """const val COVER_ARCHIVE_NAME = "([^"]+)""""),
                file(DESKTOP_LOCAL_SOURCE, """const val COVER_ARCHIVE = "([^"]+)""""),
            ),
            NamePair(
                "Local source cover base name (any cover.* is the cover)",
                file(ANDROID_LOCAL_COVERS, """nameWithoutExtension\.equals\("(\w+)", ignoreCase = true\)"""),
                file(DESKTOP_LOCAL_SOURCE, """nameWithoutExtension\.equals\("(\w+)", ignoreCase = true\)"""),
            ),
            NamePair(
                "New local source cover file",
                file(ANDROID_LOCAL_COVERS, """const val DEFAULT_COVER_NAME = "([^"]+)""""),
                file(DESKTOP_LOCAL_SOURCE, """File\(dir, "(cover\.[^"]+)"\)"""),
                KnownNameDifference(
                    desktop = "cover.\$ext",
                    android = "cover.jpg",
                    reviewed = false,
                    why = "Unreviewed mismatch found when the test was written. Android writes a local entry's " +
                        "new cover as cover.jpg whatever the image is; the desktop names it after the image type " +
                        "(cover.png, cover.webp, ...). Both apps take any cover.* as the cover, so each reads the other's",
                ),
            ),
            NamePair(
                "Automatic backup date format",
                file(BACKUP_CREATOR, """SimpleDateFormat\("([^"]+)""""),
                file(DESKTOP_BACKUPS, """ofPattern\("([^"]+)"\)"""),
            ),
            NamePair(
                "Automatic backup extension",
                file(BACKUP_CREATOR, """"\$\{BuildConfig\.APPLICATION_ID\}_\${'$'}date\.(\w+)""""),
                file(DESKTOP_BACKUPS, """ofPattern\("[^"]+"\)\)\}\.(\w+)""""),
            ),
            NamePair(
                "Automatic backup prefix",
                file(BACKUP_CREATOR, """return "(\$\{[^}]+\})_\${'$'}date"""),
                file(DESKTOP_BACKUPS, """"([^_"]+)_\$\{LocalDateTime"""),
                KnownNameDifference(
                    desktop = "zoukan",
                    android = "\${BuildConfig.APPLICATION_ID}",
                    reviewed = false,
                    why = "Unreviewed mismatch found when the test was written (not listed in PARITY.md, though " +
                        "DesktopBackups and THREAT-MODEL.md say it is on purpose): Android names a backup " +
                        "app.komikku_<date>.tachibk, the desktop zoukan_<date>.tachibk, so AutoBackup prunes only " +
                        "its own and never the Android app's",
                ),
            ),
            NamePair(
                "No-media marker in the downloads folder",
                {
                    val manager = RepositorySources.text(STORAGE_MANAGER)
                    check(Regex("""createDirectory\(DOWNLOADS_PATH\)\.also \{\s*DiskUtil\.createNoMediaFile""").containsMatchIn(manager)) {
                        "$STORAGE_MANAGER no longer creates the no-media file in the downloads folder; update the test"
                    }
                    ConstantResolver.only(RepositorySources.text(DISK_UTIL), Regex("""const val NOMEDIA_FILE = "([^"]+)""""), DISK_UTIL)
                },
                {
                    val writers = RepositorySources.kotlinFiles("desktop/src/main").filter { "nomedia" in RepositorySources.text(it).lowercase() }
                    if (writers.isEmpty()) "(none)" else "named in ${writers.joinToString()}"
                },
                KnownNameDifference(
                    desktop = "(none)",
                    android = ".nomedia",
                    reviewed = false,
                    why = "Unreviewed mismatch found when the test was written. Android creates downloads/.nomedia " +
                        "(StorageManager) so the gallery skips downloaded pages; the desktop never creates it, so a " +
                        "storage folder made on the desktop and copied to Android has none until Android re-creates it",
                ),
            ),
        )
    }
}

/** A name in the storage folder: how to read Android's and the desktop's. */
internal class NamePair(
    val what: String,
    val android: () -> String,
    val desktop: () -> String,
    val known: KnownNameDifference? = null,
)

/** A name that differs from Android: the two values as they are now, and why. */
internal class KnownNameDifference(val desktop: String, val android: String, val reviewed: Boolean, val why: String)

internal object StorageNames {

    /** Group 1 of the one match of [regex] in [text], or in the repository file [path] when [text] is null. */
    fun from(path: String, text: String?, regex: Regex): () -> String = {
        ConstantResolver.only(text ?: RepositorySources.text(path), regex, path)
    }

    fun problems(pairs: List<NamePair>): List<String> = pairs.mapNotNull { pair ->
        val values = runCatching { pair.desktop() to pair.android() }
        val (desktop, android) = values.getOrElse { return@mapNotNull "${pair.what}: ${it.message}" }
        val known = pair.known
        when {
            known == null && desktop != android -> "${pair.what}: desktop \"$desktop\", Android \"$android\""
            known != null && desktop == android -> "${pair.what}: the recorded difference is stale, both are \"$desktop\"; remove it"
            known != null && (known.desktop != desktop || known.android != android) ->
                "${pair.what}: recorded as desktop \"${known.desktop}\", Android \"${known.android}\", but now desktop \"$desktop\", Android \"$android\""
            else -> null
        }
    }
}
