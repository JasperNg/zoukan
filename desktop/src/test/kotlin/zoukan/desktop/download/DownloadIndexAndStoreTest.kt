package zoukan.desktop.download

import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.Await
import zoukan.desktop.tray.Notifications
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Collections

/**
 * The downloaded-chapter index: "is it downloaded", "where is it", the counts and the deletes see
 * the same files, whatever the case or Unicode form on disk, and see files added from outside.
 */
class DownloadIndexTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()

    private fun kit() = DownloadKit(temp).also { kits += it }

    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

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
        (nfc == nfd) shouldBe false
        val entry = kit.manga(1, nfc)
        val sourceDir = File(kit.downloads, "A (EN)").apply { mkdirs() }
        // Made the way that keeps NFD on this system: Java on Linux and Windows passes the name as it
        // is (ext4 and NTFS store it so); the JDK on macOS turns a name into NFC, so there mkdir makes it
        if (isMac) {
            ProcessBuilder("mkdir", "-p", "$nfd/Chapitre $nfd").directory(sourceDir).start().waitFor() shouldBe 0
        } else {
            File(sourceDir, "$nfd/Chapitre $nfd").mkdirs() shouldBe true
        }
        File(sourceDir, "$nfd/Chapitre $nfd/001.png").writeBytes(byteArrayOf(1))
        val chapter = kit.chapter(1, 1, name = "Chapitre $nfc")

        // The real names, as the system lists them
        val onDisk = sourceDir.list()!!.single()
        val chapterOnDisk = File(sourceDir, onDisk).list()!!.single()
        // Where the system lists NFD, the title's NFC name is not the folder's: only the lookup by key finds it
        if (!isMac) {
            onDisk shouldBe nfd
            chapterOnDisk shouldBe "Chapitre $nfd"
        }
        kit.downloaded(entry, chapter) shouldBe true
        val found = kit.manager.findChapter(entry, chapter)!!
        found.parentFile.name shouldBe onDisk
        found.name shouldBe chapterOnDisk
        found.isDirectory shouldBe true
        kit.manager.downloadCount(entry) shouldBe 1

        // And it is deleted from where it is, with no second folder made
        kit.manager.delete(entry, listOf(chapter))
        sourceDir.walk().map { it.relativeTo(sourceDir).path }.toList().filter { it.isNotEmpty() } shouldBe emptyList()
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

    // --- Case sensitivity & normalization ---

    @ParameterizedTest(name = "ignoring case: {0}")
    @ValueSource(booleans = [true, false])
    fun `a key is the NFC name, lowercased only where case is ignored`(ignoreCase: Boolean) {
        val index = DownloadIndex(ignoreCase = ignoreCase)
        val nfd = Normalizer.normalize("Café 1", Normalizer.Form.NFD)

        index.key(temp, nfd) shouldBe (if (ignoreCase) "café 1" else Normalizer.normalize("Café 1", Normalizer.Form.NFC))
        (index.key(temp, "Chapter 1") == index.key(temp, "chapter 1")) shouldBe ignoreCase
    }

    @ParameterizedTest(name = "ignoring case: {0}")
    @ValueSource(booleans = [true, false])
    fun `matching finds a name in another case only where case is ignored, under its name on disk`(ignoreCase: Boolean) {
        val index = DownloadIndex(ignoreCase = ignoreCase)
        val dir = File(temp, "dir").apply { mkdirs() }
        File(dir, "chapter 1.cbz").writeText("x")
        File(dir, "Chapter 10.cbz").writeText("x")

        index.matching(temp, dir, listOf("Chapter 1.cbz")) shouldBe (if (ignoreCase) listOf("chapter 1.cbz") else emptyList())
        index.matching(temp, dir, listOf("Chapter 10.cbz")) shouldBe listOf("Chapter 10.cbz")
    }

    @ParameterizedTest(name = "ignoring case: {0}")
    @ValueSource(booleans = [true, false])
    fun `a delete finds the chapter in another case only where case is ignored, and nothing else goes`(ignoreCase: Boolean) = runBlocking<Unit> {
        val kit = DownloadKit(temp, ignoreCase = ignoreCase).also { kits += it }
        val entry = kit.manga(1, "Entry")
        // The entry folder and the chapter in another case, as copied from a disk that ignores case
        val other = kit.write("A (EN)/entry/chapter 1.cbz")
        val neighbour = kit.write("A (EN)/entry/Chapter 10.cbz")

        kit.manager.delete(entry, listOf(kit.chapter(1, 1)))

        // Where case is ignored, the same chapter; where not, another file that is never touched
        other.exists() shouldBe !ignoreCase
        neighbour.exists() shouldBe true
        if (ignoreCase) {
            kit.manager.downloadCount(entry) shouldBe 1
            kit.manager.findChapter(entry, kit.chapter(10, 1))!!.parentFile.name shouldBe "entry"
        }
    }
}

/**
 * How long the library's download checks take on a big downloads folder: 500 entries of 200
 * downloaded chapters each. Runs only with `ZOUKAN_BENCHMARK=1` in the environment.
 */
class DownloadIndexBenchmarkTest {

    @TempDir
    lateinit var temp: File

    private class FakeSource : HttpSource() {
        override val name = "Bench"
        override val id = 1L
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()
    }

    @Test
    fun `cold load and warm refresh of 500 entries x 200 chapters`() {
        assumeTrue(System.getenv("ZOUKAN_BENCHMARK") != null, "set ZOUKAN_BENCHMARK=1 to run")
        val entries = 500
        val perEntry = 200
        val source = FakeSource()
        // Real objects, not mocks: a mocked call costs more than the disk work being measured
        val sourceManager = object : SourceManager by mockk<SourceManager>() {
            override fun get(sourceKey: Long) = source
            override fun getOrStub(sourceKey: Long) = source
        }
        val storage = ZoukanStorage(ZoukanDirs(temp), InMemoryPreferenceStore())
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        val mangas = (1..entries).map { id -> Manga.create().copy(id = id.toLong(), source = 1L, ogTitle = "Entry $id") }
        val chapters = mangas.associate { m ->
            m.id to (1..perEntry).map { c ->
                Chapter.create().copy(id = m.id * 1000 + c, mangaId = m.id, url = "/m${m.id}/c$c", name = "Chapter $c")
            }
        }
        mangas.forEach { m ->
            val dir = paths.mangaDir(m).apply { mkdirs() }
            chapters.getValue(m.id).forEach { File(dir, "${it.name}.cbz").writeBytes(byteArrayOf(1)) }
        }

        // An existing library: no folder changed in the last seconds
        mangas.forEach { paths.mangaDir(it).setLastModified(System.currentTimeMillis() - 60_000) }

        // The index's clock: the test moves it past the recheck time instead of waiting
        var now = System.currentTimeMillis()
        fun manager() = DownloadManager(
            ZoukanDirs(temp),
            paths,
            sourceManager,
            downloadPreferences,
            mockk(),
            mockk(),
            mockk(),
            mockk(relaxed = true),
            indexClock = { now },
        )
        fun ms(block: () -> Unit): Long {
            val t = System.nanoTime()
            block()
            return (System.nanoTime() - t) / 1_000_000
        }

        /** As the library and then the entries' pages do: the badges' counts for every entry, then each entry's chapters. */
        fun load(manager: DownloadManager): Pair<Long, Long> {
            var total = 0
            val counts = ms { total += manager.downloadCounts(mangas).values.sum() }
            val ids = ms { mangas.forEach { m -> total += manager.downloadedIds(m, chapters.getValue(m.id)).size } }
            total shouldBe entries * perEntry * 2
            return counts to ids
        }

        /** The previous index, for a before figure on the same machine at the same time: one listing per folder, names in a set. */
        fun oldLoad(): Pair<Long, Long> {
            var total = 0
            val sets = HashMap<Long, Set<String>>()
            val counts = ms {
                mangas.forEach { m ->
                    val names = paths.mangaDir(m).list()!!.toHashSet().also { sets[m.id] = it }
                    total += names.count { n -> !n.endsWith("_tmp") && !n.startsWith(".") && DownloadIndex.NOT_CHAPTER.none { n.endsWith(it, true) } }
                }
            }
            val ids = ms {
                mangas.forEach { m ->
                    val names = sets.getValue(m.id)
                    total += chapters.getValue(m.id).count { c ->
                        val current = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, false, false)
                        val v1 = FileNames.buildValidFilename(c.name)
                        val other = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, true, true)
                        listOf(current, v1, other).distinct().flatMap { listOf(it, "$it.cbz") }.any { it in names }
                    }
                }
            }
            total shouldBe entries * perEntry * 2
            return counts to ids
        }

        fun Pair<Long, Long>.text() = "${first + second}ms (counts $first + chapters $second)"

        // Warms the JIT and the disk cache, as an app that ran before
        load(manager())
        oldLoad()
        val oldCold = oldLoad()
        val manager = manager()
        val cold = load(manager)
        val warm = (1..3).map { load(manager) }.minBy { it.first + it.second }
        // Past the recheck time every folder's modified time is read again (nothing changed)
        now += DownloadIndex.RECHECK_MILLIS + 100
        val readBefore = manager.foldersRead
        val rechecked = load(manager)
        println("DownloadIndexBenchmark recheck: ${manager.foldersRead - readBefore} folders read again")
        println(
            "DownloadIndexBenchmark: previous index ${oldCold.text()}; cold load ${cold.text()}, warm refresh ${warm.text()}, " +
                "refresh after the recheck time ${rechecked.text()} ($entries entries x $perEntry chapters)",
        )
    }
}

/** Unit tests for [DownloadStore]: atomic queue persistence, unreadable recovery, and held-item retry. */
class DownloadStoreTest {

    @TempDir
    lateinit var temp: File

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(
        queue: MutableStateFlow<List<Download>> = MutableStateFlow(emptyList()),
        getManga: GetManga = mockk(relaxed = true),
        getChapter: GetChapter = mockk(relaxed = true),
        canDownload: (Manga) -> Boolean = { true },
        onRestored: () -> Unit = {},
        repositories: (() -> Pair<MangaRepository, ChapterRepository>?) = { null },
    ): DownloadStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DownloadStore(
            dirs = ZoukanDirs(temp),
            scope = scope,
            getManga = getManga,
            getChapter = getChapter,
            canDownload = canDownload,
            queueLock = Any(),
            queue = queue,
            onRestored = onRestored,
            restoreRepositoriesProvider = repositories,
        )
    }

    private fun manga(id: Long) = Manga.create().copy(id = id, source = 1L, ogTitle = "Manga $id")

    private fun chapter(id: Long, mangaId: Long) = Chapter.create().copy(id = id, mangaId = mangaId, url = "/c$id", name = "Chapter $id")

    @Test
    fun `saveNow writes queue atomically and leaves no temp file`() = runBlocking<Unit> {
        val m = manga(1)
        val c1 = chapter(10, 1)
        val c2 = chapter(20, 1)
        val queue = MutableStateFlow(listOf(Download(m, c1), Download(m, c2)))
        val s = store(queue = queue)

        s.saveNow()

        s.queueFile.exists() shouldBe true
        File(s.queueFile.path + ".tmp").exists() shouldBe false
        s.peekSavedQueue() shouldContainExactly listOf(
            DownloadStore.SavedDownload(1L, 10L),
            DownloadStore.SavedDownload(1L, 20L),
        )
    }

    @Test
    fun `saveNow before restore preserves pre-existing saved entries alongside new queued items`() = runBlocking<Unit> {
        val m = manga(1)
        val existing = chapter(10, 1)
        val added = chapter(20, 1)
        val queue = MutableStateFlow(listOf(Download(m, added)))
        val s = store(queue = queue)
        s.queueFile.writeText("""[{"mangaId":1,"chapterId":10},{"mangaId":1,"chapterId":20}]""")

        s.saveNow()

        s.peekSavedQueue() shouldContainExactly listOf(
            DownloadStore.SavedDownload(1L, 20L),
            DownloadStore.SavedDownload(1L, 10L),
        )
        // Reorder check: added is now first, existing is preserved once without duplication
        queue.value.map { it.chapter.id } shouldContainExactly listOf(added.id)
        existing.id shouldBe 10L
    }

    @Test
    fun `restore quarantines unreadable queue file and posts warning without losing it on later save`() = runBlocking<Unit> {
        val m = manga(1)
        val c = chapter(11, 1)
        val queue = MutableStateFlow(listOf(Download(m, c)))
        val s = store(queue = queue)
        s.queueFile.writeText("{not valid json")

        val posted = coroutineScope {
            val messages = Collections.synchronizedList(mutableListOf<Notifications.Message>())
            val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { Notifications.flow.toList(messages) }
            yield()
            try {
                s.restore(start = false)
            } finally {
                collector.cancelAndJoin()
            }
            messages.filter { it.kind == Notifications.Kind.WARNING }
        }

        posted shouldHaveSize 1
        s.queueFile.exists() shouldBe false
        val quarantined = temp.listFiles().orEmpty().single { it.name.startsWith("downloads-queue.unreadable-") }
        quarantined.name shouldStartWith "downloads-queue.unreadable-"
        quarantined.readText() shouldBe "{not valid json"

        // Saving the new queue does not touch the quarantined file
        s.saveNow()
        quarantined.readText() shouldBe "{not valid json"
        s.peekSavedQueue() shouldContainExactly listOf(DownloadStore.SavedDownload(1L, 11L))
    }

    @Test
    fun `restore drops missing chapters and un-downloadable sources while keeping order`() = runBlocking<Unit> {
        val m1 = manga(1)
        val m2 = manga(2)
        val c1 = chapter(10, 1)
        val c2 = chapter(20, 1)
        val c3 = chapter(30, 2)
        val mangaRepo = mockk<MangaRepository> {
            coEvery { getMangaById(1) } returns m1
            coEvery { getMangaById(2) } returns m2
        }
        val chapterRepo = mockk<ChapterRepository> {
            coEvery { getChapterById(10) } returns c1
            coEvery { getChapterById(20) } returns c2
            coEvery { getChapterById(30) } returns c3
            coEvery { getChapterById(99) } returns null
        }
        var restoredCalls = 0
        val queue = MutableStateFlow<List<Download>>(emptyList())
        val s = store(
            queue = queue,
            canDownload = { it.id == 1L },
            onRestored = { restoredCalls++ },
            repositories = { mangaRepo to chapterRepo },
        )
        s.queueFile.writeText(
            """[{"mangaId":1,"chapterId":20},{"mangaId":1,"chapterId":99},{"mangaId":2,"chapterId":30},{"mangaId":1,"chapterId":10}]""",
        )

        s.restore(start = true)

        queue.value.map { it.chapter.id } shouldContainExactly listOf(20L, 10L)
        restoredCalls shouldBe 1
    }

    @Test
    fun `failed db read holds unread downloads until retryHeldIfNeeded succeeds and cancelHeld removes cancelled ones`() = runBlocking<Unit> {
        val m1 = manga(1)
        val c1 = chapter(10, 1)
        val c2 = chapter(20, 1)
        val c3 = chapter(30, 1)
        var failSecond = true
        val mangaRepo = mockk<MangaRepository> {
            coEvery { getMangaById(1) } returns m1
        }
        val chapterRepo = mockk<ChapterRepository> {
            coEvery { getChapterById(10) } returns c1
            coEvery { getChapterById(20) } answers {
                if (failSecond) throw IOException("db locked") else c2
            }
            coEvery { getChapterById(30) } returns c3
        }
        var startTriggered = 0
        val queue = MutableStateFlow<List<Download>>(emptyList())
        val s = store(
            queue = queue,
            onRestored = { startTriggered++ },
            repositories = { mangaRepo to chapterRepo },
        )
        s.queueFile.writeText("""[{"mangaId":1,"chapterId":10},{"mangaId":1,"chapterId":20},{"mangaId":1,"chapterId":30}]""")

        s.restore(start = true)

        queue.value.map { it.chapter.id } shouldContainExactly listOf(10L)
        s.heldChapterIds() shouldContainExactly listOf(20L, 30L)
        startTriggered shouldBe 1

        // Cancelling chapter 30 while held leaves only chapter 20 to be retried
        s.cancelHeld(setOf(30L)) shouldBe true
        s.cancelHeld(setOf(30L)) shouldBe false
        s.heldChapterIds() shouldContainExactly listOf(20L)

        // Heal DB and retry held items
        failSecond = false
        s.retryHeldIfNeeded()
        withTimeout(5_000) {
            Await.untilSuspending("held chapter to be restored") { queue.value.map { it.chapter.id } == listOf(10L, 20L) }
        }
        s.heldChapterIds() shouldBe emptyList()
        startTriggered shouldBe 2
    }

    @Test
    fun `peekSavedQueue returns null on corrupt file without moving it`() {
        val s = store()
        s.queueFile.writeText("corrupt")

        s.peekSavedQueue().shouldBeNull()
        s.queueFile.exists() shouldBe true
    }
}
