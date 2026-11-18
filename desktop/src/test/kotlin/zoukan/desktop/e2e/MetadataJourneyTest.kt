package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.EH_SOURCE_ID
import exh.source.ExhPreferences
import exh.source.getMainSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.testing.Await
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import kotlin.time.Duration.Companion.minutes

/**
 * An E-Hentai entry with stored gallery metadata shows the details block and its tags by
 * namespace, "More info" opens the metadata viewer, and Settings > Data has the data saver.
 *
 * Opening an E-Hentai entry would load related titles and page previews from e-hentai.org, so
 * both are switched off before the entry opens and the journey checks neither is on screen:
 * nothing leaves 127.0.0.1. MangaDex settings need the MangaDex extension and are not part of
 * this journey.
 *
 *   ./gradlew :desktop:journeyTest --tests "*MetadataJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class MetadataJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun metadataViewerAndDataSaver() {
        journey = Journey.start("metadata")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        // Nothing may reach e-hentai.org: no related titles, no page previews (0 rows hides the
        // section and skips the load)
        Injekt.get<SourcePreferences>().relatedMangas().set(false)
        Injekt.get<UiPreferences>().previewsRowCount().set(0)

        // The built-in E-Hentai source, and an entry from it with its gallery metadata
        Injekt.get<ExhPreferences>().isHentaiEnabled().set(true)
        val sources = Injekt.get<SourceManager>()
        runBlocking { sources.isInitialized.first { it } }
        Await.until("the SourceManager to list E-Hentai", 30_000) { sources.get(EH_SOURCE_ID) != null }
        val manga = runBlocking {
            val manga = Injekt.get<NetworkToLocalManga>()(
                Manga.create().copy(
                    url = "/g/123456/abcdef1234/",
                    ogTitle = "Metadata gallery",
                    source = EH_SOURCE_ID,
                    favorite = true,
                    initialized = true,
                    dateAdded = System.currentTimeMillis(),
                ),
            )
            Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = manga.url, name = "Chapter", sourceOrder = 0)),
            )
            val meta = EHentaiSearchMetadata().apply {
                mangaId = manga.id
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
            Injekt.get<InsertFlatMetadata>().await(meta)
            // Metadata sources resolve their database access through these interfaces (page previews)
            val source = sources.get(EH_SOURCE_ID)!!.getMainSource<MetadataSource<*, *>>()!!
            source.getMangaId.awaitId(manga.url, EH_SOURCE_ID) shouldBe manga.id
            source.getFlatMetadataById.await(manga.id) shouldNotBe null
            source.insertFlatMetadata shouldNotBe null
            manga
        }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                step("Details block and namespace tags") {
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("More info"))
                    waitFor(hasText("uploader-name"))
                    waitFor(hasText("artist"))
                    waitFor(hasText("some artist"))
                    waitFor(hasText("female"))
                    waitFor(hasText("glasses"))
                    onAllNodesWithText("glasses").onFirst().performScrollTo()
                    // The sections that would call e-hentai.org are not there
                    onAllNodesWithText("Related titles").fetchSemanticsNodes().size shouldBe 0
                    onAllNodesWithText("More previews").fetchSemanticsNodes().size shouldBe 0
                    shot("1-details")
                }

                step("Metadata viewer") {
                    onAllNodesWithText("More info").onFirst().performScrollTo().performClick()
                    waitFor(hasText("Gallery size"))
                    waitFor(hasText("123456"))
                    waitFor(hasText("52.4 MB"))
                    waitFor(hasText("321"))
                    shot("2-metadata-viewer")
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Data saver settings") {
                    runOnUiThread { navigator?.let { HomeScreen.openSettings(it) } }
                    waitFor(hasText("Settings"))
                    settingsPage(SettingsCategory.Data).performScrollToNode(hasText("Excluded sources"))
                    shot("3-data-saver")
                    // With the data saver off, those options are disabled
                    waitFor(isToggleable())
                    val enabledSwitches = { onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().size }
                    val before = enabledSwitches()
                    onAllNodesWithText("Compress images before downloading or loading in reader").onFirst().performClick()
                    waitFor(hasText("wsrv.nl"))
                    onAllNodesWithText("wsrv.nl").onFirst().performClick()
                    waitForIdle()
                    Injekt.get<SourcePreferences>().dataSaver().get() shouldBe SourcePreferences.DataSaver.WSRV_NL
                    val url = exh.util.DataSaver(sources.get(EH_SOURCE_ID)!!, Injekt.get()).compress("https://example.org/a.png")
                    url shouldBe "https://wsrv.nl/?url=https://example.org/a.png&output=webp&q=80"
                    // The setting shows as chosen on screen: the options that need a data saver are enabled now
                    waitOrDump("for the data saver switches to turn on") { enabledSwitches() > before }
                    shot("4-data-saver-chosen")
                    Injekt.get<SourcePreferences>().dataSaver().set(SourcePreferences.DataSaver.NONE)
                }
            }

            step("The metadata is still stored, and the sections that call E-Hentai stayed off") {
                runBlocking {
                    val source = sources.get(EH_SOURCE_ID)!!.getMainSource<MetadataSource<*, *>>()!!
                    source.getFlatMetadataById.await(manga.id) shouldNotBe null
                }
                // The journey kit has no host-level network guard (the fixture server only sees
                // requests made to it), so what keeps this offline is these two settings staying
                // off for the whole journey, with their sections absent in the first step
                Injekt.get<SourcePreferences>().relatedMangas().get() shouldBe false
                Injekt.get<UiPreferences>().previewsRowCount().get() shouldBe 0
            }
        }
    }
}
