package zoukan.desktop.download

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.text.Normalizer

/**
 * The downloaded-chapter index: "is it downloaded", "where is it", the counts and the deletes see
 * the same files, whatever the case or Unicode form on disk, and see files added from outside.
 */
class DownloadIndexTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()

    private fun kit() = DownloadKit(temp).also { kits += it }

    @AfterEach
    fun waitForSaves() = kits.forEach { it.awaitBackground() }

    /** The two ways of asking always agree. */
    private fun DownloadKit.downloaded(manga: Manga, chapter: Chapter): Boolean {
        val found = manager.findChapter(manga, chapter)
        manager.isDownloaded(manga, chapter) shouldBe (found != null)
        manager.downloadedIds(manga, listOf(chapter)) shouldBe setOfNotNull(chapter.id.takeIf { found != null })
        return found != null
    }

    @Test
    fun `a name that differs only in case is found where the disk ignores case, by every check alike`() {
        val kit = kit()
        val entry = kit.manga(1, "Entry")
        val file = kit.write("A (EN)/Entry/chapter 1.cbz")
        // What the disk says: true on macOS and Windows, false on most Linux disks
        val diskIgnoresCase = File(file.parentFile, "Chapter 1.cbz").exists()

        kit.downloaded(entry, kit.chapter(1, 1)) shouldBe diskIgnoresCase
        kit.manager.downloadCount(entry) shouldBe 1
        if (diskIgnoresCase) kit.manager.findChapter(entry, kit.chapter(1, 1))!!.name shouldBe "chapter 1.cbz"
    }

    @Test
    fun `a folder named in NFD is found for a title and chapter in NFC`() = runBlocking<Unit> {
        val kit = kit()
        val nfc = Normalizer.normalize("Café", Normalizer.Form.NFC)
        val nfd = Normalizer.normalize("Café", Normalizer.Form.NFD)
        val entry = kit.manga(1, nfc)
        kit.write("A (EN)/$nfd/Chapitre $nfd.cbz")
        val chapter = kit.chapter(1, 1, name = "Chapitre $nfc")

        // The names as the system reports them (ext4 and NTFS keep NFD apart from NFC; the JDK on
        // macOS may hand them back in NFC): found under those, never under a name made from the title
        val onDisk = File(kit.downloads, "A (EN)").list()!!.single()
        val chapterOnDisk = File(kit.downloads, "A (EN)/$onDisk").list()!!.single()
        kit.downloaded(entry, chapter) shouldBe true
        val found = kit.manager.findChapter(entry, chapter)!!
        found.parentFile.name shouldBe onDisk
        found.name shouldBe chapterOnDisk
        found.isFile shouldBe true
        kit.manager.downloadCount(entry) shouldBe 1

        // And it is deleted from where it is, with no second folder made
        kit.manager.delete(entry, listOf(chapter))
        File(kit.downloads, "A (EN)").list().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `empty folders, unfinished downloads and empty CBZs are not downloads`() {
        val kit = kit()
        val entry = kit.manga(1, "Entry")
        kit.write("A (EN)/Entry/Chapter 1/")
        kit.write("A (EN)/Entry/Chapter 2_tmp/001.png")
        kit.write("A (EN)/Entry/Chapter 3.cbz_tmp")
        kit.write("A (EN)/Entry/Chapter 4.cbz", ByteArray(0))
        kit.write("A (EN)/Entry/cover.jpg")
        kit.write("A (EN)/Entry/notes.pdf")

        (1L..4L).forEach { kit.downloaded(entry, kit.chapter(it, 1)) shouldBe false }
        kit.manager.downloadCount(entry) shouldBe 0
    }

    @Test
    fun `a chapter kept both as a folder and as a CBZ counts once`() {
        val kit = kit()
        val entry = kit.manga(1, "Entry")
        kit.chapterFolder("A (EN)/Entry/Chapter 1")
        kit.write("A (EN)/Entry/Chapter 1.cbz")
        kit.write("A (EN)/Entry/Chapter 2.cbz")

        kit.manager.downloadCount(entry) shouldBe 2
    }

    @Test
    fun `a file copied in from outside is seen once the folder's time changed`() {
        var now = System.currentTimeMillis()
        val index = DownloadIndex(recheckMillis = 3_000, clock = { now })
        val root = File(temp, "downloads").apply { mkdirs() }
        val dir = File(root, "A (EN)/Entry").apply { mkdirs() }
        dir.setLastModified(System.currentTimeMillis() - 60_000)
        index.findValid(root, dir, listOf("Chapter 1.cbz")).shouldBeNull()

        File(dir, "Chapter 1.cbz").writeBytes(byteArrayOf(1))
        dir.setLastModified(System.currentTimeMillis())
        // Within the recheck time the listing is trusted
        index.findValid(root, dir, listOf("Chapter 1.cbz")).shouldBeNull()
        now += 3_000
        index.findValid(root, dir, listOf("Chapter 1.cbz")) shouldBe "Chapter 1.cbz"
        val reads = index.listingsRead

        // Nothing changed: one stat, no new listing
        now += 3_000
        dir.setLastModified(System.currentTimeMillis() - 30_000)
        index.findValid(root, dir, listOf("Chapter 1.cbz")) shouldBe "Chapter 1.cbz"
        val settled = index.listingsRead
        now += 3_000
        index.findValid(root, dir, listOf("Chapter 1.cbz")) shouldBe "Chapter 1.cbz"
        index.listingsRead shouldBe settled
        (settled - reads <= 1) shouldBe true
    }

    @Test
    fun `with renewing set to manual only Reindex reads the folders again`() {
        val kit = kit()
        kit.downloadPreferences.downloadCacheRenewInterval().set(-1)
        val entry = kit.manga(1, "Entry")
        val dir = File(kit.downloads, "A (EN)/Entry").apply { mkdirs() }
        val old = System.currentTimeMillis() - 60_000
        dir.setLastModified(old)
        kit.downloaded(entry, kit.chapter(1, 1)) shouldBe false
        val changes = kit.manager.changes.value

        // A change the folder's time does not show
        File(dir, "Chapter 1.cbz").writeBytes(byteArrayOf(1))
        dir.setLastModified(old)
        kit.downloaded(entry, kit.chapter(1, 1)) shouldBe false
        kit.manager.changes.value shouldBe changes

        kit.manager.invalidateCache()
        kit.downloaded(entry, kit.chapter(1, 1)) shouldBe true
        (kit.manager.changes.value > changes) shouldBe true
    }

    @Test
    fun `a chapter downloaded under any combination of the two file name settings is found`() {
        val combinations = listOf(false to false, false to true, true to false, true to true)
        combinations.forEach { (ascii, hash) ->
            combinations.forEach { (nowAscii, nowHash) ->
                val dir = File(temp, "c-$ascii-$hash-$nowAscii-$nowHash").apply { mkdirs() }
                val kit = DownloadKit(dir).also { kits += it }
                val entry = kit.manga(1, "Entry")
                val chapter = kit.chapter(1, 1, name = "Chapitre été")
                val name = DownloadPaths.chapterDirName(chapter.name, chapter.scanlator, chapter.url, ascii, hash)
                kit.write("A (EN)/Entry/$name.cbz")
                kit.libraryPreferences.disallowNonAsciiFilenames().set(nowAscii)
                kit.downloadPreferences.includeChapterUrlHash().set(nowHash)

                kit.downloaded(entry, chapter) shouldBe true
            }
        }
    }

    @Test
    fun `an entry folder named under the other non-ASCII setting is found and deleted from`() = runBlocking<Unit> {
        val kit = kit()
        val entry = kit.manga(1, "Été")
        val chapter = kit.chapter(1, 1)
        val asciiName = FileNames.buildValidFilename("Été", disallowNonAscii = true)
        val file = kit.write("A (EN)/$asciiName/Chapter 1.cbz")

        kit.downloaded(entry, chapter) shouldBe true
        kit.manager.downloadCount(entry) shouldBe 1

        kit.manager.delete(entry, listOf(chapter))
        file.exists() shouldBe false
        kit.downloaded(entry, chapter) shouldBe false
    }
}
