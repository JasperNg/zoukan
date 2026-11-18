package zoukan.desktop.storage

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.AutoBackup
import zoukan.desktop.download.DownloadKit
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.local.LocalSource
import zoukan.desktop.testing.FileSystemSandbox
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * A storage folder from the Android app ([AndroidStorageFolder]) works on the desktop as it is: the
 * downloads, the local source and the automatic backups are read through the app's own storage
 * code, and no file that came from Android is changed or deleted.
 */
class AndroidStorageFolderTest {

    @TempDir
    lateinit var temp: File

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/backups/$name")!!.use { it.readBytes() }

    /** Every file (by content hash) and folder under [root], by path relative to it. */
    private fun manifest(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { it != root.toPath() }.toList().associate { path ->
            val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
            relative to if (Files.isDirectory(path)) {
                "folder"
            } else {
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
        }
    }

    private fun manga(entry: AndroidStorageFolder.Entry): Manga = mockk<Manga>(relaxed = true) {
        every { id } returns entry.id
        every { source } returns entry.source.id
        every { title } returns entry.title
        every { ogTitle } returns entry.title
        every { favorite } returns true
    }

    private fun chapter(c: AndroidStorageFolder.Chapter): Chapter =
        Chapter.create().copy(id = c.id, mangaId = c.entry.id, url = c.url, name = c.name, scanlator = c.scanlator)

    @Test
    fun `the desktop reads an Android storage folder and changes none of its files`() = runBlocking<Unit> {
        FileSystemSandbox.create(temp).use { sandbox ->
            val fixture = AndroidStorageFolder(sandbox.storageFolder)
            fixture.write(newestBackupBytes = resource("komikku-real.tachibk"), olderBackupBytes = resource("komikku-empty.tachibk"))
            val before = manifest(sandbox.storageFolder)
            sandbox.record()

            // The app's storage code, pointed at the folder as the storage folder setting does
            val dirs = ZoukanDirs(sandbox.dataFolder)
            val storage = ZoukanStorage(dirs, InMemoryPreferenceStore())
            storage.folderPreference.set(sandbox.storageFolder.path)
            storage.isAvailable shouldBe true

            // Downloads: each chapter found under the name Android gave it
            val sources = fixture.sources.associate { it.id to DownloadKit.TestSource(it.name, it.id, it.lang) }
            val sourceManager: SourceManager = object : SourceManager by mockk<SourceManager>() {
                override fun get(sourceKey: Long) = sources[sourceKey]
                override fun getOrStub(sourceKey: Long): Source = sources.getValue(sourceKey)
                override fun getOnlineSources() = sources.values.toList()
            }
            val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
            val paths = DownloadPaths(storage, sourceManager, LibraryPreferences(InMemoryPreferenceStore()), downloadPreferences)
            val downloads = DownloadManager(
                dirs, paths, sourceManager, downloadPreferences, mockk(), mockk(), mockk(), mockk(relaxed = true), coOwners = { emptyList() },
            )
            for (c in fixture.chapters) {
                val found = downloads.findChapter(manga(c.entry), chapter(c))?.relativeTo(sandbox.storageFolder)?.invariantSeparatorsPath
                (c.name to found) shouldBe (c.name to fixture.chapterFile(c)?.relativeTo(sandbox.storageFolder)?.invariantSeparatorsPath)
            }
            fixture.entries.associate { it.title to downloads.downloadCount(manga(it)) } shouldBe mapOf(
                "Harbor Lights" to 3,
                "What If? / Part 2" to 2,
                "海の灯" to 1,
                "...Trailing dots..." to 1,
                "Lantern Days" to 1,
            )
            downloads.downloadedIds(manga(fixture.harbor), fixture.chapters.filter { it.entry == fixture.harbor }.map(::chapter)) shouldBe
                setOf(101L, 102L, 104L)

            // The local source: the entry, its cover, its ComicInfo.xml details and its chapters
            val local = LocalSource(folder = { storage.local })
            local.getPopularManga(1).mangas.map { it.title } shouldContainExactly listOf(fixture.localEntry, fixture.localBareEntry)
            val entry = SManga.create().apply {
                url = fixture.localEntry
                title = fixture.localEntry
            }
            val update = local.getMangaUpdate(entry, emptyList(), fetchDetails = true, fetchChapters = true)
            update.manga.title shouldBe "Harbor Atlas: Collected"
            update.manga.author shouldBe "Cora Mapmaker"
            update.manga.artist shouldBe "Dee Inkwell"
            update.manga.description shouldBe "Charts of an invented coast."
            update.manga.genre shouldBe "Adventure, Maps"
            update.manga.status shouldBe SManga.COMPLETED
            val cover = File(storage.local, "${fixture.localEntry}/cover.jpg")
            update.manga.thumbnail_url shouldBe cover.toURI().toString()
            ImageIO.read(cover).shouldNotBeNull()
            update.chapters.map { it.url } shouldContainExactly listOf("${fixture.localEntry}/Chapter 2.cbz", "${fixture.localEntry}/Chapter 1")
            update.chapters[0].name shouldBe "The Second Chart"
            update.chapters[0].chapter_number shouldBe 2f
            update.chapters[0].scanlator shouldBe "Atlas Team"
            update.chapters[1].name shouldBe "Chapter 1"
            update.chapters[1].chapter_number shouldBe 1f
            local.getPageList(update.chapters[0]).size shouldBe 3
            local.getPageList(update.chapters[1]).size shouldBe 2
            // An entry with no details or cover: Android and the desktop both note that it has no
            // ComicInfo.xml (.noxml) and take a cover from the first chapter
            val bare = SManga.create().apply {
                url = fixture.localBareEntry
                title = fixture.localBareEntry
            }
            local.getMangaUpdate(bare, emptyList(), fetchDetails = true, fetchChapters = true).chapters.map { it.url } shouldContainExactly
                listOf("${fixture.localBareEntry}/Sketch 1.cbz")

            // The automatic backups: the newest Android one is found and reads
            val autoBackup = AutoBackup(
                storage,
                dirs.android,
                BackupPreferences(InMemoryPreferenceStore()),
                InMemoryPreferenceStore().getString("written", ""),
            )
            autoBackup.latest() shouldBe fixture.newestBackup
            val backup = fixture.newestBackup.source().buffer().use { BackupCodec.decode(it) }
            backup.backupManga.size shouldBe 12
            backup.backupCategories.size shouldBe 2

            // Nothing from Android changed or deleted, inside the app's folders too
            val after = manifest(sandbox.storageFolder)
            val changed = before.filter { (path, hash) -> after[path] != hash }
                .map { (path, _) -> if (path in after) "changed $path" else "deleted $path" }
            check(changed.isEmpty()) { "Files from the Android storage folder were changed or deleted:\n  ${changed.joinToString("\n  ")}" }
            // Added only where the app is documented to write (the local source's .noxml and generated cover)
            val added = (after.keys - before.keys).sorted()
            check(added == EXPECTED_ADDED) { "Unexpected files added to the storage folder: $added (expected $EXPECTED_ADDED)" }
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    private companion object {
        val EXPECTED_ADDED = listOf("local/Sketchbook/.noxml", "local/Sketchbook/cover.png")
    }
}
