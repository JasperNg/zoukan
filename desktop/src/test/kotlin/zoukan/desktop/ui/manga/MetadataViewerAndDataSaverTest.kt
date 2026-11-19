package zoukan.desktop.ui.manga

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.EH_SOURCE_ID
import exh.util.DataSaver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.dataSaverSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Covers E-Hentai [MetadataDescription] + [NamespaceTags] on [MangaInfoPane] (keeping related titles
 * and page previews hidden when disabled), [MetadataViewScreen] field formatting, and
 * Settings > Data [dataSaverSettings] + [DataSaver.compress].
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MetadataViewerAndDataSaverTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var tempDir: File

    private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = mutableListOf<MangaScreenModel>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(main)
        Images.init(File(tempDir, "cache").apply { mkdirs() })
        CustomCovers.coverDir = File(tempDir, "covers").apply { mkdirs() }
        Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
        Injekt.addSingleton(mockk<NetworkHelper> { every { client } returns OkHttpClient() })
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        app.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private class ReactiveMemoryPreferenceStore : PreferenceStore {
        private val flows = ConcurrentHashMap<String, MutableStateFlow<Any?>>()
        private fun <T> pref(key: String, defaultValue: T): Preference<T> {
            val flow = flows.computeIfAbsent(key) { MutableStateFlow(null) }
            @Suppress("UNCHECKED_CAST")
            return object : Preference<T> {
                override fun key(): String = key
                override fun get(): T = (flow.value as T?) ?: defaultValue
                override fun isSet(): Boolean = flow.value != null
                override fun delete() {
                    flow.value = null
                }
                override fun defaultValue(): T = defaultValue
                override fun changes(): Flow<T> = flow.map { (it as T?) ?: defaultValue }
                override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
                override fun set(value: T) {
                    flow.value = value
                }
            }
        }
        override fun getString(key: String, defaultValue: String) = pref(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long) = pref(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int) = pref(key, defaultValue)
        override fun getFloat(key: String, defaultValue: Float) = pref(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean) = pref(key, defaultValue)
        override fun getStringSet(key: String, defaultValue: Set<String>) = pref(key, defaultValue)
        override fun <T> getObjectFromString(key: String, defaultValue: T, serializer: (T) -> String, deserializer: (String) -> T) = pref(key, defaultValue)
        override fun <T> getObjectFromInt(key: String, defaultValue: T, serializer: (T) -> Int, deserializer: (Int) -> T) = pref(key, defaultValue)
        override fun getAll(): Map<String, *> = flows.mapValues { it.value.value }
    }

    private fun sampleMetadata(mangaId: Long): EHentaiSearchMetadata = EHentaiSearchMetadata().apply {
        this.mangaId = mangaId
        gId = "123456"
        gToken = "abcdef1234"
        title = "Metadata gallery"
        genre = "doujinshi"
        uploader = "uploader-name"
        language = "english"
        translated = true
        size = 52_428_800
        length = 24
        favorites = 321
        averageRating = 4.5
        ratingCount = 100
        visible = "Yes"
        tags += RaisedTag("artist", "some artist", EHentaiSearchMetadata.TAG_TYPE_NORMAL)
        tags += RaisedTag("female", "glasses", EHentaiSearchMetadata.TAG_TYPE_LIGHT)
    }

    @Test
    fun `MangaInfoPane renders metadata details and namespace tags, and MetadataViewScreen shows gallery fields`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val uiPrefs = UiPreferences(store).apply { previewsRowCount().set(0) }
            val sourcePrefs = SourcePreferences(store).apply { relatedMangas().set(false) }
            val manga = Manga.create().copy(
                id = 8801L,
                url = "/g/123456/abcdef1234/",
                ogTitle = "Metadata gallery",
                source = EH_SOURCE_ID,
                favorite = true,
                initialized = true,
            )
            val meta = sampleMetadata(manga.id)
            val ehentaiSource = mockk<EHentai>(relaxed = true) {
                every { id } returns EH_SOURCE_ID
                every { name } returns "E-Hentai"
                every { metaClass } returns EHentaiSearchMetadata::class
            }
            val getFlatMetadataById = mockk<GetFlatMetadataById> {
                every { subscribe(manga.id) } returns flowOf(meta.flatten())
                coEvery { await(manga.id) } returns meta.flatten()
            }
            val sourceManager = mockk<SourceManager>(relaxed = true) {
                every { get(EH_SOURCE_ID) } returns ehentaiSource
            }
            val downloads = mockk<DownloadManager>(relaxed = true) {
                every { changes } returns MutableStateFlow(0)
                every { queue } returns MutableStateFlow(emptyList())
            }
            val ch = Chapter.create().copy(id = 88010L, mangaId = manga.id, url = manga.url, name = "Chapter", sourceOrder = 0)

            Injekt.addSingleton(uiPrefs)
            Injekt.addSingleton(sourcePrefs)
            Injekt.addSingleton(sourceManager)
            Injekt.addSingleton(getFlatMetadataById)
            Injekt.addSingleton(mockk<GetManga> { coEvery { await(manga.id) } returns manga })
            Injekt.addSingleton(Application())
            Injekt.addSingleton(mockk<tachiyomi.domain.manga.interactor.GetMergedMangaById>(relaxed = true))

            val model = MangaScreenModel(
                mangaId = manga.id,
                getMangaWithChapters = mockk<GetMangaWithChapters> {
                    coEvery { awaitManga(manga.id) } returns manga
                    coEvery { subscribe(manga.id) } returns flowOf(manga to listOf(ch))
                },
                sourceManager = sourceManager,
                updateChapter = mockk(relaxed = true),
                refreshManga = mockk(relaxed = true),
                downloadManager = downloads,
                getTracks = mockk { every { subscribe(any<Long>()) } returns flowOf(emptyList()) },
                trackPreferences = mockk(relaxed = true),
                trackerManager = mockk(relaxed = true),
                trackChapters = mockk(relaxed = true),
                getMergedChaptersByMangaId = mockk(relaxed = true),
                getFlatMetadataById = getFlatMetadataById,
                addToLibraryFlow = mockk(relaxed = true),
                getCategories = mockk(relaxed = true),
                setMangaCategories = mockk(relaxed = true),
                chapterSettings = mockk(relaxed = true) { every { excludedScanlators(any()) } returns flowOf(emptySet()) },
                filterChaptersForDownload = mockk(relaxed = true),
                updateErrors = mockk(relaxed = true),
                refreshTracks = mockk(relaxed = true),
                libraryPreferences = LibraryPreferences(store),
                uiPreferences = uiPrefs,
                chaptersByEntry = mockk<ChaptersByEntry> {
                    coEvery { group(any(), any()) } answers { listOf(firstArg<Manga>() to secondArg<List<Chapter>>()) }
                },
                desktopPreferences = lazyOf(DesktopPreferences(store)),
                appScope = app,
            ).also { models += it }

            var openedMetadata = false
            setContent {
                MaterialTheme {
                    Navigator(
                        object : Screen {
                            override val key: ScreenKey = "metadata-host"

                            @androidx.compose.runtime.Composable
                            override fun Content() {
                                val state by model.state.collectAsState()
                                val m = state.manga
                                if (m != null) {
                                    MangaInfoPane(
                                        manga = m,
                                        state = state,
                                        model = model,
                                        onWebView = null,
                                        onSearch = {},
                                        onOpenMetadata = { openedMetadata = true },
                                        search = EntryInfoSearch({}, {}, {}),
                                    )
                                }
                            }
                        },
                    ) { it.lastItem.Content() }
                }
            }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText("uploader-name")).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodes(hasText("glasses")).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText(localized(SYMR.strings.more_info)).onFirst().assertExists()
            onAllNodesWithText("artist").onFirst().assertExists()
            onAllNodesWithText("some artist").onFirst().assertExists()
            onAllNodesWithText("female").onFirst().assertExists()
            onAllNodesWithText(localized(zoukan.i18n.desktop.DMR.strings.related_titles)).fetchSemanticsNodes().shouldBeEmpty()
            onAllNodesWithText(localized(SYMR.strings.more_previews)).fetchSemanticsNodes().shouldBeEmpty()

            onAllNodesWithText(localized(SYMR.strings.more_info)).onFirst().performScrollTo().performClick()
            openedMetadata shouldBe true
        }

    @Test
    fun `MetadataViewScreen displays E-Hentai gallery metadata fields`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val manga = Manga.create().copy(id = 8901L, url = "/g/123456/abcdef1234/", ogTitle = "Metadata gallery", source = EH_SOURCE_ID)
            val meta = sampleMetadata(manga.id)
            val ehentaiSource = mockk<EHentai>(relaxed = true) {
                every { id } returns EH_SOURCE_ID
                every { metaClass } returns EHentaiSearchMetadata::class
            }
            Injekt.addSingleton(Application())
            Injekt.addSingleton(mockk<GetManga> { coEvery { await(manga.id) } returns manga })
            Injekt.addSingleton(mockk<SourceManager>(relaxed = true) { every { get(EH_SOURCE_ID) } returns ehentaiSource })
            Injekt.addSingleton(mockk<GetFlatMetadataById> { coEvery { await(manga.id) } returns meta.flatten() })

            setContent {
                MaterialTheme {
                    Navigator(MetadataViewScreen(manga.id, EH_SOURCE_ID)) { it.lastItem.Content() }
                }
            }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText("123456")).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodes(hasText("52.4 MB")).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodes(hasText("321")).fetchSemanticsNodes().isNotEmpty()
            }
        }

    @Test
    fun `dataSaverSettings enables dependent options on wsrv_nl and DataSaver rewrites URLs`() =
        runDesktopComposeUiTest(width = 900, height = 700, effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val sourcePrefs = SourcePreferences(store)
            val source = mockk<EHentai>(relaxed = true) { every { id } returns EH_SOURCE_ID }
            Injekt.addSingleton(sourcePrefs)
            Injekt.addSingleton(mockk<SourceManager>(relaxed = true) { every { getOnlineSources() } returns emptyList() })

            setContent {
                MaterialTheme {
                    LazyColumn {
                        dataSaverSettings()
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty()
            }
            val enabledBefore = onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().size

            onAllNodesWithText(localized(SYMR.strings.data_saver_summary)).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText(localized(SYMR.strings.wsrv))).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText(localized(SYMR.strings.wsrv)).onFirst().performClick()

            waitUntil(timeoutMillis = 5_000) {
                sourcePrefs.dataSaver().get() == SourcePreferences.DataSaver.WSRV_NL &&
                    onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().size > enabledBefore
            }
            onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().size shouldBeGreaterThan enabledBefore

            DataSaver(source, sourcePrefs).compress("https://example.org/a.png") shouldBe
                "https://wsrv.nl/?url=https://example.org/a.png&output=webp&q=80"
        }
}
