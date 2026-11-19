package zoukan.desktop.ui.reader

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.AppTempFiles
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.CbzEncryption
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.manga.EditManga
import zoukan.desktop.secrets.FileCipher
import zoukan.desktop.testing.FreshInjekt
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Setting a page as cover goes straight from memory (including pages decrypted from an encrypted
 * chapter) to the encrypted custom cover file, writing zero temporary files to [AppTempFiles] or
 * `java.io.tmpdir`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageAsCoverInMemoryTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var dir: File

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cipher = FileCipher(ByteArray(32) { 7 })
    private lateinit var appTemp: File
    private var previousCoverDir: File? = null
    private var previousCodec: xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec? = null
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
        app.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private class SetCapableStore(base: PreferenceStore = InMemoryPreferenceStore()) : PreferenceStore by base {
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
            InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
    }

    private fun pngBytes(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(6, 6, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

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
        val pageBytes = pngBytes()
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
        val pageBytes = pngBytes()
        val password = "chapter-secret"
        val archive = File(dir, "chapter.cbz")
        CbzWriter(archive, CbzEncryption(password.toCharArray(), SecurityPreferences.EncryptionType.AES_256)).use {
            it.write("001.png", pageBytes.copyOf())
        }

        val store = SetCapableStore()
        val screen = TestScreen("page-as-cover-test-${UUID.randomUUID()}")
        val model = withContext(Dispatchers.Main) {
            register(screen) {
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
                    discordPresence = lazyOf(mockk(relaxed = true)),
                    trackChapters = lazyOf(mockk(relaxed = true)),
                    editManga = lazyOf(newEditManga()),
                    appScope = app,
                )
            }
        }
        try {
            withTimeout(10_000) { model.state.first { it.pages.size == 1 } }
            model.state.value.pages[0].bytes shouldBe null

            CustomCovers.file(manga.id).exists() shouldBe false
            val sysTmp = File(System.getProperty("java.io.tmpdir"))
            val sysTmpBefore = sysTmp.listFiles().orEmpty().map { it.name }.toSet()

            appTemp.setWritable(false)
            try {
                model.setPageAsCover(0)
                withTimeout(10_000) { awaitAppWork() }
            } finally {
                appTemp.setWritable(true)
            }

            assertEncryptedCoverWithoutTempFiles(manga.id, pageBytes, sysTmpBefore)
        } finally {
            withContext(Dispatchers.Main) { ScreenModelStore.onDispose(screen) }
            withTimeout(10_000) { awaitAppWork() }
        }
    }

    private suspend fun awaitAppWork() {
        while (true) {
            val pending = app.coroutineContext.job.children.filter { it.isActive }.toList()
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    private class TestScreen(override val key: String) : Screen {
        @Composable
        override fun Content() = Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun register(screen: Screen, create: () -> ReaderScreenModel): ReaderScreenModel {
        val store = ScreenModelStore::class.java
        val key = "${screen.key}:${ReaderScreenModel::class.qualifiedName}:default"
        (store.getMethod("getLastScreenModelKey").invoke(ScreenModelStore) as MutableStateFlow<String?>).value = key
        val model = create()
        (store.getMethod("getScreenModels").invoke(ScreenModelStore) as MutableMap<String, Any>)[key] = model
        return model
    }
}
