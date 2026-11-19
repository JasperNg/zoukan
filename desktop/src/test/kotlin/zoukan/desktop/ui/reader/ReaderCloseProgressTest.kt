package zoukan.desktop.ui.reader

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.screen.Screen
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.DownloadManager
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Closing the reader right after reaching the last page keeps the read mark and the page.
 *
 * Voyager disposes the reader's screen model as soon as the screen is popped, while the reader
 * still fades out on screen; the viewer can report the page it settled on during that fade, after
 * the model was disposed. That last page change was dropped, so the chapter stayed unread (the
 * BrowseAndRead and Stats journeys failed this way on CI).
 */
class ReaderCloseProgressTest {

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @TempDir
    lateinit var dir: File

    init {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        app.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    /** A chapter row as the database keeps it: a null field leaves it as it is */
    private class Row(var read: Boolean = false, var lastPageRead: Long = 0) {
        @Synchronized
        fun apply(update: ChapterUpdate) {
            update.read?.let { read = it }
            update.lastPageRead?.let { lastPageRead = it }
        }
    }

    /** The in-memory store has no string sets */
    private class SetCapableStore(base: PreferenceStore = InMemoryPreferenceStore()) : PreferenceStore by base {
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
            InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
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

    private fun newModel(row: Row, folder: File): ReaderScreenModel {
        val store = SetCapableStore()
        return ReaderScreenModel(
            mangaId = manga.id,
            initialChapterId = chapter.id,
            getManga = mockk { coEvery { await(manga.id) } returns manga },
            getChapters = mockk { coEvery { await(manga.id) } returns listOf(chapter) },
            sourceManager = mockk { every { get(any<Long>()) } returns mockk<eu.kanade.tachiyomi.source.Source>(relaxed = true) },
            updateChapter = mockk<UpdateChapter> {
                coEvery { await(any()) } answers {
                    row.apply(firstArg())
                }
                coEvery { awaitAll(any()) } returns Unit
            },
            upsertHistory = mockk(relaxed = true),
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
        )
    }

    /** Waits, as quitting does, until every write the reader started on the app scope has run. */
    private suspend fun awaitAppWork() {
        while (true) {
            val pending = app.coroutineContext.job.children.filter { it.isActive }.toList()
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    /**
     * Opens the reader on [chapter], waits for its pages, then runs [close] on the main thread
     * (the UI thread) with the last page's index, and returns the row once every write has finished.
     */
    private fun readThenClose(iteration: Int, close: (ReaderScreenModel, Screen, Int) -> Unit): Row = runBlocking {
        val row = Row()
        val folder = chapterFolder(PAGES)
        val screen = TestScreen("reader-close-test-${java.util.UUID.randomUUID()}")
        val model = withContext(Dispatchers.Main) { register(screen) { newModel(row, folder) } }
        withTimeout(10_000) { model.state.first { it.pages.size == PAGES } }
        withContext(Dispatchers.Main) { close(model, screen, PAGES - 1) }
        withTimeout(10_000) { awaitAppWork() }
        row
    }

    @Test
    fun `the last page reported while the reader fades out after closing is still saved`() {
        repeat(REPEATS) { i ->
            val row = readThenClose(i) { model, screen, last ->
                // The pop disposes the model at once; the viewer, still on screen for the fade,
                // then reports the page the pager settled on
                ScreenModelStore.onDispose(screen)
                model.onPageChanged(last)
            }
            withClue(i) {
                row.read shouldBe true
                row.lastPageRead shouldBe (PAGES - 1).toLong()
            }
        }
    }

    @Test
    fun `closing right after the last page keeps the read mark and the page`() {
        repeat(REPEATS) { i ->
            val row = readThenClose(i) { model, screen, last ->
                model.onPageChanged(last)
                ScreenModelStore.onDispose(screen)
            }
            withClue(i) {
                row.read shouldBe true
                row.lastPageRead shouldBe (PAGES - 1).toLong()
            }
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
    private fun register(screen: Screen, create: () -> ReaderScreenModel): ReaderScreenModel {
        val store = ScreenModelStore::class.java
        val key = "${screen.key}:${ReaderScreenModel::class.qualifiedName}:default"
        (store.getMethod("getLastScreenModelKey").invoke(ScreenModelStore) as MutableStateFlow<String?>).value = key
        val model = create()
        (store.getMethod("getScreenModels").invoke(ScreenModelStore) as MutableMap<String, Any>)[key] = model
        return model
    }

    private inline fun withClue(iteration: Int, block: () -> Unit) =
        io.kotest.assertions.withClue("iteration $iteration") { block() }

    private companion object {
        const val PAGES = 3
        const val REPEATS = 50
    }
}
