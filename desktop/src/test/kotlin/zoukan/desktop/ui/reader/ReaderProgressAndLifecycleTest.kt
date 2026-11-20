package zoukan.desktop.ui.reader

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.EH_SOURCE_ID
import exh.source.ExhPreferences
import exh.source.MERGED_SOURCE_ID
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import zoukan.desktop.AppTempFiles
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.CbzEncryption
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.manga.EditManga
import zoukan.desktop.secrets.FileCipher
import zoukan.desktop.security.Incognito
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.UncaughtCoroutineErrors
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.extension.DesktopExtensionManager
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Tests for reader persistence and lifecycle: [ProgressWriter] ordering, closing progress capture,
 * per-source incognito resolution, [ReaderCleanup] and image retention, fatal error propagation,
 * entry Pictures folder naming, and in-memory cover assignment.
 */
class ReaderProgressAndLifecycleTest {

    /** A chapter row as the database keeps it: a null field leaves it as it is. */
    private class ChapterRow(var read: Boolean = false, var lastPageRead: Long = 0) {
        @Synchronized
        fun apply(update: ChapterUpdate) {
            update.read?.let { read = it }
            update.lastPageRead?.let { lastPageRead = it }
        }
    }

    private class TestScreen(override val key: String) : Screen {
        @Composable
        override fun Content() = Unit
    }

    /**
     * Registers the model under [screen] as rememberScreenModel does (its scope included), so
     * ScreenModelStore.onDispose(screen), what Voyager runs when the screen is popped, disposes the
     * model and cancels its screenModelScope. The store's registration is internal to Voyager.
     */
    @Suppress("UNCHECKED_CAST")
    private fun registerReaderModel(screen: Screen, create: () -> ReaderScreenModel): ReaderScreenModel {
        val store = ScreenModelStore::class.java
        val key = "${screen.key}:${ReaderScreenModel::class.qualifiedName}:default"
        (store.getMethod("getLastScreenModelKey").invoke(ScreenModelStore) as MutableStateFlow<String?>).value = key
        val model = create()
        (store.getMethod("getScreenModels").invoke(ScreenModelStore) as MutableMap<String, Any>)[key] = model
        return model
    }

    /** Waits, as quitting does, until every write the reader started on [app] has run. */
    private suspend fun awaitAppWork(app: CoroutineScope) {
        while (true) {
            val pending = app.coroutineContext.job.children.filter { it.isActive }.toList()
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    private fun drainScopeAndModels(app: CoroutineScope, models: MutableList<ReaderScreenModel>) {
        runBlocking {
            withTimeout(5_000) {
                models.forEach { m ->
                    val job = m.screenModelScope.coroutineContext[Job]!!
                    job.cancelChildren()
                    job.children.toList().joinAll()
                }
                app.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
        models.clear()
    }

    private fun pngBytes(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    @Nested
    inner class ProgressQueue {

        private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Test
        fun `writes finish in the order the pages were turned, even when an earlier one is slower`() = runBlocking<Unit> {
            val row = ChapterRow()
            val order = Collections.synchronizedList(mutableListOf<Long>())
            val writer = ProgressWriter(scope()) { update ->
                // The earlier writes take longer, as a busy database would make them
                delay((10 - update.lastPageRead!!) * 20)
                row.apply(update)
                order += update.lastPageRead!!
            }
            (0..9).forEach { writer.submit(progressUpdate(1L, it, read = it == 9)) }
            withTimeout(10_000) { writer.join() }

            order shouldContainExactly (0L..9L).toList()
            row.lastPageRead shouldBe 9L
            row.read shouldBe true
        }

        @Test
        fun `a progress write never makes a read chapter unread`() = runBlocking<Unit> {
            progressUpdate(1L, 3, read = false).read shouldBe null
            progressUpdate(1L, 3, read = true).read shouldBe true
            progressUpdate(1L, 3, read = false).lastPageRead shouldBe 3L

            val row = ChapterRow(read = true, lastPageRead = 20)
            val writer = ProgressWriter(scope()) { row.apply(it) }
            writer.submit(progressUpdate(1L, 4, read = false))
            withTimeout(10_000) { writer.join() }

            row.read shouldBe true
            row.lastPageRead shouldBe 4L
        }

        @Test
        fun `a failed write does not stop the ones after it`() = runBlocking<Unit> {
            val row = ChapterRow()
            val writer = ProgressWriter(scope()) { update ->
                if (update.lastPageRead == 1L) error("database is locked")
                row.apply(update)
            }
            writer.submit(progressUpdate(1L, 1, read = false))
            writer.submit(progressUpdate(1L, 2, read = false))
            withTimeout(10_000) { writer.join() }

            row.lastPageRead shouldBe 2L
        }

        @Test
        fun `a write submitted after the reader closed still runs, after the earlier ones`() = runBlocking<Unit> {
            val row = ChapterRow()
            val writer = ProgressWriter(scope()) { update ->
                delay(50)
                row.apply(update)
            }
            writer.submit(progressUpdate(1L, 5, read = false))
            // The last page, reported while the closed reader fades out
            writer.submit(progressUpdate(1L, 6, read = true))
            withTimeout(10_000) { writer.join() }

            row.lastPageRead shouldBe 6L
            row.read shouldBe true
        }
    }

    /**
     * Closing the reader right after reaching the last page keeps the read mark and the page.
     *
     * Voyager disposes the reader's screen model as soon as the screen is popped, while the reader
     * still fades out on screen; the viewer can report the page it settled on during that fade, after
     * the model was disposed. That last page change was dropped, so the chapter stayed unread (the
     * BrowseAndRead and Stats journeys failed this way on CI).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Nested
    inner class CloseProgress {

        private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val models = mutableListOf<ReaderScreenModel>()

        @TempDir
        lateinit var dir: File

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
        }

        @AfterEach
        fun tearDown() {
            try {
                drainScopeAndModels(app, models)
            } finally {
                Dispatchers.resetMain()
                main.close()
            }
        }

        private val manga = Manga.create().copy(id = 7, source = 4242, initialized = true)
        private val chapter = Chapter.create().copy(id = 70, mangaId = 7, name = "Chapter 1", url = "/c/1")

        private fun chapterFolder(pages: Int): File {
            val folder = File(dir, "chapter").apply { mkdirs() }
            repeat(pages) { i ->
                val file = File(folder, "${i + 1}.png")
                if (!file.exists()) ImageIO.write(BufferedImage(4, 6, BufferedImage.TYPE_INT_RGB), "png", file)
            }
            return folder
        }

        private fun newModel(
            row: ChapterRow,
            folder: File,
            upsertHistory: UpsertHistory = mockk(relaxed = true),
        ): ReaderScreenModel {
            val store = TestPreferenceStore()
            return ReaderScreenModel(
                mangaId = manga.id,
                initialChapterId = chapter.id,
                getManga = mockk { coEvery { await(manga.id) } returns manga },
                getChapters = mockk { coEvery { await(manga.id) } returns listOf(chapter) },
                sourceManager = mockk { every { get(any<Long>()) } returns mockk<Source>(relaxed = true) },
                updateChapter = mockk<UpdateChapter> {
                    coEvery { await(any()) } answers {
                        row.apply(firstArg())
                    }
                    coEvery { awaitAll(any()) } returns Unit
                },
                upsertHistory = upsertHistory,
                mangaRepository = mockk(relaxed = true),
                downloadManager = mockk<DownloadManager>(relaxed = true) {
                    every { findChapter(any(), any()) } returns folder
                    every { isDownloaded(any(), any()) } returns true
                },
                cbzPassword = mockk(relaxed = true),
                downloadPreferences = DownloadPreferences(store),
                libraryPreferences = LibraryPreferences(store),
                preferences = DesktopPreferences(store),
                incognitoPolicy = lazyOf(mockk { every { isOn(any()) } returns false }),
                syncManager = lazyOf(mockk(relaxed = true)),
                trackChapters = lazyOf(mockk(relaxed = true)),
                appScope = app,
            ).also { models += it }
        }

        /**
         * Opens the reader on [chapter], waits for its pages, then runs [close] on the main thread
         * (the UI thread) with the last page's index, and returns the row once every write has finished.
         */
        private fun readThenClose(close: (ReaderScreenModel, Screen, Int) -> Unit): ChapterRow = runBlocking {
            val row = ChapterRow()
            val folder = chapterFolder(PAGES)
            val screen = TestScreen("reader-close-test-${UUID.randomUUID()}")
            val model = withContext(Dispatchers.Main) { registerReaderModel(screen) { newModel(row, folder) } }
            withTimeout(10_000) { model.state.first { it.pages.size == PAGES } }
            withContext(Dispatchers.Main) { close(model, screen, PAGES - 1) }
            withTimeout(10_000) { awaitAppWork(app) }
            row
        }

        @Test
        fun `the last page reported while the reader fades out after closing is still saved`() {
            repeat(REPEATS) { i ->
                val row = readThenClose { model, screen, last ->
                    // The pop disposes the model at once; the viewer, still on screen for the fade,
                    // then reports the page the pager settled on
                    ScreenModelStore.onDispose(screen)
                    model.onPageChanged(last)
                }
                withClue("iteration $i") {
                    row.read shouldBe true
                    row.lastPageRead shouldBe (PAGES - 1).toLong()
                }
            }
        }

        @Test
        fun `closing right after the last page keeps the read mark and the page`() {
            repeat(REPEATS) { i ->
                val row = readThenClose { model, screen, last ->
                    model.onPageChanged(last)
                    ScreenModelStore.onDispose(screen)
                }
                withClue("iteration $i") {
                    row.read shouldBe true
                    row.lastPageRead shouldBe (PAGES - 1).toLong()
                }
            }
        }

        @Test
        fun `eager init on Main keeps incognito off and still saves progress and history on dispose`() = runBlocking<Unit> {
            val row = ChapterRow()
            val historyUpdates = CopyOnWriteArrayList<HistoryUpdate>()
            val upsertHistory = mockk<UpsertHistory> {
                coEvery { await(any()) } answers { historyUpdates += firstArg<HistoryUpdate>() }
            }
            val folder = chapterFolder(PAGES)
            val screen = TestScreen("reader-eager-init-test-${UUID.randomUUID()}")
            val model = try {
                Dispatchers.setMain(Dispatchers.Unconfined)
                registerReaderModel(screen) { newModel(row, folder, upsertHistory) }
            } finally {
                Dispatchers.setMain(main)
            }
            withTimeout(10_000) { model.state.first { it.pages.size == PAGES } }
            withContext(Dispatchers.Main) {
                model.onPageChanged(PAGES - 1)
                ScreenModelStore.onDispose(screen)
            }
            withTimeout(10_000) { awaitAppWork(app) }

            row.read shouldBe true
            row.lastPageRead shouldBe (PAGES - 1).toLong()
            historyUpdates.map { it.chapterId } shouldContain chapter.id
        }
    }

    /**
     * Per-extension incognito in a merged entry follows each chapter's own source, not the merged
     * entry's (which no extension has), as the reader resolves it with [chapterManga].
     */
    @Nested
    inner class IncognitoResolution {

        private fun <T> pref(value: T): Preference<T> = mockk { every { get() } returns value }

        // E-Hentai's "Incognito" on: its galleries are incognito, other sources are not
        private val incognito = Incognito(
            preferenceStore = mockk<PreferenceStore> { every { getBoolean(any(), any()) } returns pref(false) },
            sourcePreferences = mockk<SourcePreferences> { every { incognitoExtensions() } returns pref(emptySet()) },
            extensionManager = mockk<DesktopExtensionManager> { every { installedExtensionsFlow } returns MutableStateFlow(emptyMap()) },
            exhPreferences = mockk<ExhPreferences> { every { ehIncognitoMode() } returns pref(true) },
        )

        private val merged = Manga.create().copy(id = 1, source = MERGED_SOURCE_ID)
        private val gallery = Manga.create().copy(id = 2, source = EH_SOURCE_ID)
        private val other = Manga.create().copy(id = 3, source = 12345L)
        private val entries = listOf(merged, gallery, other).associateBy { it.id }

        private fun chapterOf(manga: Manga) = Chapter.create().copy(id = manga.id * 10, mangaId = manga.id)

        private fun incognitoFor(readerManga: Manga, chapter: Chapter): Boolean = runBlocking {
            val manga = chapterManga(readerManga, chapter) { entries[it] }!!
            incognito.isOn(manga.source)
        }

        @Test
        fun `a merged entry's chapter from an incognito source is read in incognito`() {
            // The merged entry's own source is no extension's, so it alone would keep history
            incognito.isOn(merged.source) shouldBe false
            incognitoFor(merged, chapterOf(gallery)) shouldBe true
        }

        @Test
        fun `a merged entry's chapter from another source keeps its history`() {
            incognitoFor(merged, chapterOf(other)) shouldBe false
        }

        @Test
        fun `an ordinary entry uses its own source and is not looked up again`() = runBlocking<Unit> {
            chapterManga(gallery, chapterOf(gallery)) { error("not looked up") } shouldBe gallery
            incognitoFor(gallery, chapterOf(gallery)) shouldBe true
        }

        @Test
        fun `a merged entry whose part is gone resolves to nothing`() = runBlocking<Unit> {
            chapterManga(merged, Chapter.create().copy(id = 99, mangaId = 42)) { null } shouldBe null
        }
    }

    @Nested
    inner class Cleanup {

        @Test
        fun `the chapter file is closed before the read downloads are deleted`() = runBlocking<Unit> {
            val events = mutableListOf<String>()
            val file = AutoCloseable { events += "close" }
            ReaderCleanup.closeThenDelete(file, listOf(Chapter.create().copy(id = 1L))) { events += "delete ${it.map { c -> c.id }}" }
            events shouldContainExactly listOf("close", "delete [1]")
        }

        @Test
        fun `a file that fails to close does not stop the deletes`() = runBlocking<Unit> {
            val events = mutableListOf<String>()
            val file = AutoCloseable { error("locked") }
            ReaderCleanup.closeThenDelete(file, listOf(Chapter.create())) { events += "delete" }
            events shouldContainExactly listOf("delete")
        }

        @Test
        fun `a failed delete is not thrown`() = runBlocking<Unit> {
            var closed = false
            ReaderCleanup.closeThenDelete(AutoCloseable { closed = true }, listOf(Chapter.create())) { error("in use") }
            closed shouldBe true
        }

        @Test
        fun `running out of memory while closing or deleting is not swallowed`() = runBlocking<Unit> {
            shouldThrow<OutOfMemoryError> {
                ReaderCleanup.closeThenDelete(AutoCloseable { throw OutOfMemoryError() }, emptyList()) { }
            }
            shouldThrow<OutOfMemoryError> {
                ReaderCleanup.closeThenDelete(null, listOf(Chapter.create())) { throw OutOfMemoryError() }
            }
        }
    }

    @Nested
    inner class KeepsImage {

        @Test
        fun `pages near the current one keep their image`() {
            keepsImage(index = 12, center = 10, behind = 4, ahead = 10, visible = IntRange.EMPTY) shouldBe true
            keepsImage(index = 5, center = 10, behind = 4, ahead = 10, visible = IntRange.EMPTY) shouldBe false
        }

        @Test
        fun `visible pages far from the middle one keep their image`() {
            // A zoomed-out webtoon shows pages 0 to 30 with page 15 in the middle
            (0..30).all { keepsImage(it, center = 15, behind = 4, ahead = 10, visible = 0..30) } shouldBe true
            keepsImage(index = 40, center = 15, behind = 4, ahead = 10, visible = 0..30) shouldBe false
        }
    }

    /**
     * A fatal error while the reader loads goes on up to the crash report, but only after the reader
     * has left its loading state: a chapter or page left loading spins for good, and "Retry" passes
     * over a page that is still loading.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Nested
    inner class FatalErrors {

        private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var previousHandler: Thread.UncaughtExceptionHandler? = null
        private val uncaught = CopyOnWriteArrayList<Throwable>()
        private val models = mutableListOf<ReaderScreenModel>()

        @BeforeEach
        fun setUp() {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Dispatchers.setMain(main)
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        }

        @AfterEach
        fun tearDown() {
            try {
                models.forEach { it.onDispose() }
                // Its coroutines read Dispatchers.Main until they end, and resetting it meanwhile fails
                drainScopeAndModels(app, models)
            } finally {
                Dispatchers.resetMain()
                main.close()
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
                UncaughtCoroutineErrors.drain()
            }
        }

        private val manga = Manga.create().copy(id = 7, source = 4242, initialized = true)
        private val chapter = Chapter.create().copy(id = 70, mangaId = 7, name = "Chapter 1", url = "/c/1")

        private fun newModel(getManga: GetManga, source: Source): ReaderScreenModel {
            val store = TestPreferenceStore()
            return ReaderScreenModel(
                mangaId = manga.id,
                initialChapterId = chapter.id,
                getManga = getManga,
                getChapters = mockk { coEvery { await(manga.id) } returns listOf(chapter) },
                sourceManager = mockk { every { get(any<Long>()) } returns source },
                updateChapter = mockk(relaxed = true),
                upsertHistory = mockk(relaxed = true),
                mangaRepository = mockk(relaxed = true),
                downloadManager = mockk<DownloadManager>(relaxed = true) {
                    every { findChapter(any(), any()) } returns null
                    every { isDownloaded(any(), any()) } returns false
                },
                cbzPassword = mockk(relaxed = true),
                downloadPreferences = DownloadPreferences(store),
                libraryPreferences = LibraryPreferences(store),
                preferences = DesktopPreferences(store),
                incognitoPolicy = lazyOf(mockk { every { isOn(any()) } returns true }),
                syncManager = lazyOf(mockk(relaxed = true)),
                trackChapters = lazyOf(mockk(relaxed = true)),
                appScope = app,
            ).also { models += it }.also { models += it }
        }

        @Test
        fun `running out of memory while a chapter loads shows an error and still reaches the crash report`() {
            val getManga = mockk<GetManga> { coEvery { await(any<Long>()) } returns manga }
            val source = mockk<HttpSource>(relaxed = true) { coEvery { getPageList(any()) } throws OutOfMemoryError("test") }
            val model = newModel(getManga, source)

            Await.until("the chapter's error", 10_000) { model.state.value.error != null && uncaught.isNotEmpty() }

            model.state.value.loading shouldBe false
            uncaught.single().shouldBeInstanceOf<OutOfMemoryError>()
        }

        @Test
        fun `running out of memory while a page loads leaves the page an error, not loading`() {
            val getManga = mockk<GetManga> { coEvery { await(any<Long>()) } returns manga }
            val source = mockk<HttpSource>(relaxed = true) {
                coEvery { getPageList(any()) } returns listOf(Page(0, imageUrl = "https://example.org/1.png"))
                coEvery { getImage(any()) } throws OutOfMemoryError("test")
            }
            val model = newModel(getManga, source)

            Await.until("the page's error", 10_000) {
                model.state.value.pages.firstOrNull()?.state?.value is ReaderPage.State.Error && uncaught.isNotEmpty()
            }

            model.state.value.pages.single().state.value.shouldBeInstanceOf<ReaderPage.State.Error>()
            model.state.value.pages.single().state.value shouldNotBe ReaderPage.State.Loading
            uncaught.map { it::class } shouldContain OutOfMemoryError::class
        }
    }

    /** The per-entry Pictures folder keeps the name an older version gave it. */
    @Nested
    inner class PicturesFolder {

        @TempDir
        lateinit var root: File

        @Test
        fun `an entry's existing pictures folder under the old name is used again`() {
            val old = File(root, ".hack__Sign").apply { mkdirs() }

            PageImages.entryPicturesFolder(root, ".hack//Sign") shouldBe old
        }

        @Test
        fun `a new entry's pictures folder gets the portable name`() {
            PageImages.entryPicturesFolder(root, ".hack//Sign") shouldBe File(root, "hack__Sign")
            // Where both spellings agree, nothing changes
            PageImages.entryPicturesFolder(root, "Plain title") shouldBe File(root, "Plain title")
        }

        @Test
        fun `a file or a link under the old name is not taken for the folder`() {
            File(root, ".hack__Sign").writeText("not a folder")
            PageImages.entryPicturesFolder(root, ".hack//Sign") shouldBe File(root, "hack__Sign")
        }
    }

    /**
     * Setting a page as cover goes straight from memory (including pages decrypted from an encrypted
     * chapter) to the encrypted custom cover file, writing zero temporary files to [AppTempFiles] or
     * `java.io.tmpdir`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Nested
    inner class PageAsCoverInMemoryTest {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        @TempDir
        lateinit var dir: File

        private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val models = mutableListOf<ReaderScreenModel>()
        private val cipher = FileCipher(ByteArray(32) { 7 })
        private lateinit var appTemp: File
        private var previousCoverDir: File? = null
        private var previousCodec: SettingsFileCodec? = null
        private var previousTempRoot: File? = null

        @BeforeEach
        fun setUp() {
            Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
            Dispatchers.setMain(main)
            previousTempRoot = AppTempFiles.currentRoot()
            previousCoverDir = runCatching { CustomCovers.coverDir }.getOrNull()
            previousCodec = CustomCovers.codec
            appTemp = File(dir, "app-tmp").apply { mkdirs() }
            AppTempFiles.init(appTemp)
            CustomCovers.coverDir = File(dir, "cache/covers")
            CustomCovers.codec = cipher
        }

        @AfterEach
        fun tearDown() {
            if (::appTemp.isInitialized) appTemp.setWritable(true)
            AppTempFiles.restoreRoot(previousTempRoot)
            previousCoverDir?.let { CustomCovers.coverDir = it }
            CustomCovers.codec = previousCodec
            try {
                drainScopeAndModels(app, models)
            } finally {
                Dispatchers.resetMain()
                main.close()
            }
        }

        private fun newEditManga(): EditManga {
            val updateManga = mockk<UpdateManga> { coEvery { await(any()) } returns true }
            return EditManga(mockk<SetCustomMangaInfo>(relaxed = true), updateManga, mockk(relaxed = true))
        }

        private fun assertEncryptedCoverWithoutTempFiles(mangaId: Long, expectedBytes: ByteArray, sysTmpBefore: Set<String>) {
            val cover = CustomCovers.file(mangaId)
            cover.isFile shouldBe true
            val stored = cover.readBytes()
            cipher.isEncoded(stored) shouldBe true
            stored.contentEquals(expectedBytes) shouldBe false
            CustomCovers.read(cover) shouldBe expectedBytes
            cover.parentFile.listFiles().orEmpty().map { it.name } shouldBe listOf(cover.name)
            appTemp.listFiles().orEmpty().shouldBeEmpty()
            val sysTmp = File(System.getProperty("java.io.tmpdir"))
            (sysTmp.listFiles().orEmpty().map { it.name }.toSet() - sysTmpBefore).shouldBeEmpty()
        }

        @Test
        fun `EditManga setCover saves custom cover encrypted in memory without temporary files`() = runBlocking<Unit> {
            val manga = Manga.create().copy(id = 11, source = 4242, favorite = true, initialized = true)
            val pageBytes = pngBytes(6, 6)
            CustomCovers.file(manga.id).exists() shouldBe false
            val sysTmp = File(System.getProperty("java.io.tmpdir"))
            val sysTmpBefore = sysTmp.listFiles().orEmpty().map { it.name }.toSet()

            appTemp.setWritable(false)
            try {
                newEditManga().setCover(manga, pageBytes)
            } finally {
                appTemp.setWritable(true)
            }

            assertEncryptedCoverWithoutTempFiles(manga.id, pageBytes, sysTmpBefore)
        }

        @Test
        fun `ReaderScreenModel setPageAsCover reads encrypted chapter in memory without temporary files`() = runBlocking<Unit> {
            val manga = Manga.create().copy(id = 12, source = 4242, favorite = true, initialized = true)
            val chapter = Chapter.create().copy(id = 120, mangaId = manga.id, name = "Chapter 1", url = "/c/1")
            val pageBytes = pngBytes(6, 6)
            val password = "chapter-secret"
            val archive = File(dir, "chapter.cbz")
            CbzWriter(archive, CbzEncryption(password.toCharArray(), SecurityPreferences.EncryptionType.AES_256)).use {
                it.write("001.png", pageBytes.copyOf())
            }

            val store = TestPreferenceStore()
            val screen = TestScreen("page-as-cover-test-${UUID.randomUUID()}")
            val model = withContext(Dispatchers.Main) {
                registerReaderModel(screen) {
                    ReaderScreenModel(
                        mangaId = manga.id,
                        initialChapterId = chapter.id,
                        getManga = mockk { coEvery { await(manga.id) } returns manga },
                        getChapters = mockk { coEvery { await(manga.id) } returns listOf(chapter) },
                        sourceManager = mockk { every { get(any<Long>()) } returns mockk<Source>(relaxed = true) },
                        updateChapter = mockk(relaxed = true),
                        upsertHistory = mockk(relaxed = true),
                        mangaRepository = mockk(relaxed = true),
                        downloadManager = mockk<DownloadManager>(relaxed = true) {
                            every { findChapter(any(), any()) } returns archive
                            every { isDownloaded(any(), any()) } returns true
                        },
                        cbzPassword = mockk<CbzPassword>(relaxed = true) {
                            every { get() } returns password.toCharArray()
                        },
                        downloadPreferences = DownloadPreferences(store),
                        libraryPreferences = LibraryPreferences(store),
                        preferences = DesktopPreferences(store),
                        incognitoPolicy = lazyOf(mockk { every { isOn(any()) } returns false }),
                        syncManager = lazyOf(mockk(relaxed = true)),
                        trackChapters = lazyOf(mockk(relaxed = true)),
                        editManga = lazyOf(newEditManga()),
                        appScope = app,
                    ).also { models += it }
                }
            }
            try {
                withTimeout(10_000) { model.state.first { it.pages.size == 1 } }
                val page = model.state.value.pages[0]
                withTimeout(10_000) { page.state.first { it is ReaderPage.State.Ready } }
                page.bytes = null
                page.bytes shouldBe null

                CustomCovers.file(manga.id).exists() shouldBe false
                val sysTmp = File(System.getProperty("java.io.tmpdir"))
                val sysTmpBefore = sysTmp.listFiles().orEmpty().map { it.name }.toSet()

                appTemp.setWritable(false)
                try {
                    model.setPageAsCover(0)
                    withTimeout(10_000) { awaitAppWork(app) }
                } finally {
                    appTemp.setWritable(true)
                }

                assertEncryptedCoverWithoutTempFiles(manga.id, pageBytes, sysTmpBefore)
            } finally {
                withContext(Dispatchers.Main) { ScreenModelStore.onDispose(screen) }
                withTimeout(10_000) { awaitAppWork(app) }
            }
        }
    }

    private companion object {
        const val PAGES = 3
        const val REPEATS = 50
    }
}
