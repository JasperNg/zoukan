package zoukan.desktop.ui.manga

import android.text.Html
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.compat.localized
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.manga.EditManga
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Covers HTML-to-text descriptions, CJK text rendering, broken cover placeholder (`cover-error`),
 * entry tag/title context menus, `EditMangaDialog` tracker/thumbnail fields, and `CoverBasedTheme`.
 */
@OptIn(ExperimentalTestApi::class)
class HtmlDescriptionAndCoverErrorTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var tempDir: File

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

    @BeforeEach
    fun setUp() {
        Images.init(File(tempDir, "cache").apply { mkdirs() })
        CustomCovers.coverDir = File(tempDir, "covers").apply { mkdirs() }
        Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
    }

    private fun solidPngBytes(rgb: Int): ByteArray = ByteArrayOutputStream().also { out ->
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 32) for (x in 0 until 32) img.setRGB(x, y, rgb)
        ImageIO.write(img, "png", out)
    }.toByteArray()

    @Test
    fun `HTML description decodes entities and line breaks and CJK text renders`() = runDesktopComposeUiTest {
        Injekt.addSingleton(UiPreferences(ReactiveMemoryPreferenceStore()))
        val htmlDescription = Html.fromHtml("Tom &amp; Jerry<br>Second line &lt;3", Html.FROM_HTML_MODE_LEGACY).toString()
        htmlDescription.lines().map { it.trim() }.filter { it.isNotEmpty() } shouldBe listOf("Tom & Jerry", "Second line <3")
        val cjkTitle = "東京の漫画テスト・長いタイトル 한국어"
        val cjkDescription = "東京を舞台にした試験用の漫画です。한국어 설명도 있습니다."

        setContent {
            MaterialTheme {
                Column {
                    DescriptionText(htmlDescription, maxLines = Int.MAX_VALUE, onTextLayout = {})
                    Text(cjkTitle, modifier = Modifier.testTag("entry-title"))
                    DescriptionText(cjkDescription, maxLines = Int.MAX_VALUE, onTextLayout = {})
                }
            }
        }

        onNodeWithText("Tom & Jerry", substring = true).assertExists()
        onNodeWithText("Second line <3", substring = true).assertExists()
        onAllNodesWithText("&amp;", substring = true).fetchSemanticsNodes().shouldBeEmpty()
        onAllNodesWithText("<br", substring = true).fetchSemanticsNodes().shouldBeEmpty()
        onNodeWithText(cjkTitle).assertExists()
        onNodeWithText(cjkDescription, substring = true).assertExists()
    }

    @Test
    fun `missing cover HTTP 404 shows cover-error placeholder`() = runDesktopComposeUiTest(effectContext = e2eEffectContext()) {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        Injekt.addSingleton(mockk<NetworkHelper> { every { this@mockk.client } returns client })

        val manga = Manga.create().copy(
            id = 501,
            source = 4242L,
            ogTitle = "Fixture Without Cover",
            ogThumbnailUrl = "https://example.invalid/missing-cover.png",
            initialized = true,
        )
        setContent {
            MaterialTheme {
                CoverImage(Cover.of(manga), Modifier.size(120.dp))
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("cover-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `tag menu, title menu, and EditMangaDialog tracker and thumbnail fields work`() = runDesktopComposeUiTest {
        val libraryCalls = mutableListOf<String>()
        val globalCalls = mutableListOf<String>()
        val tagCalls = mutableListOf<String>()
        val search = EntryInfoSearch(
            library = { libraryCalls += it },
            global = { globalCalls += it },
            tag = { tagCalls += it },
        )
        Injekt.addSingleton(mockk<EditManga>(relaxed = true))
        val manga = Manga.create().copy(id = 601, source = 1L, favorite = true, ogTitle = "Charlie")
        var showEdit by mutableStateOf(false)

        setContent {
            MaterialTheme {
                Column {
                    TitleMenuBox("Charlie", search, onClick = { globalCalls += "click" }) { mod ->
                        Text("Charlie", mod.testTag("entry-title"))
                    }
                    TagMenuBox("female: sole female", search) { mod, openMenu ->
                        Text("female: sole female", mod.testTag("tag-chip"))
                        androidx.compose.material3.TextButton(onClick = openMenu) { Text("Open tag") }
                    }
                    if (showEdit) EditMangaDialog(manga, onDismiss = { showEdit = false })
                }
            }
        }

        onNodeWithText("Open tag").performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasText(localized(MR.strings.action_search))).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(localized(MR.strings.action_global_search)).assertExists()
        onNodeWithText(localized(MR.strings.action_copy_to_clipboard)).assertExists()
        onNodeWithText(localized(MR.strings.action_search)).performClick()
        tagCalls shouldBe listOf("female: sole female")

        onNodeWithTag("entry-title").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasText(localized(KMR.strings.action_library_search))).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(localized(KMR.strings.action_library_search)).performClick()
        libraryCalls shouldBe listOf("Charlie")

        runOnUiThread { showEdit = true }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasText(localized(SYMR.strings.fill_from_tracker))).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText(localized(SYMR.strings.thumbnail_url)).onFirst().assertExists()
    }

    @Test
    fun `CoverBasedTheme derives dynamic primary color only when themeCoverBased is enabled`() =
        runDesktopComposeUiTest(effectContext = e2eEffectContext()) {
            val store = ReactiveMemoryPreferenceStore()
            val uiPrefs = UiPreferences(store)
            Injekt.addSingleton(uiPrefs)
            val greenPng = solidPngBytes(0x00CC44)
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(greenPng.toResponseBody("image/png".toMediaType()))
                        .build()
                }
                .build()
            Injekt.addSingleton(mockk<NetworkHelper> { every { this@mockk.client } returns client })
            Injekt.addSingleton(mockk<SourceManager>(relaxed = true) { every { this@mockk.get(any()) } returns null })

            val manga = Manga.create().copy(
                id = 701,
                source = 4242L,
                ogTitle = "Colored Cover",
                ogThumbnailUrl = "https://example.invalid/green-cover.png",
                initialized = true,
            )

            uiPrefs.themeCoverBased().set(false)
            var currentPrimary = Color.Unspecified
            setContent {
                MaterialTheme {
                    CoverBasedTheme(manga) {
                        currentPrimary = MaterialTheme.colorScheme.primary
                    }
                }
            }
            waitForIdle()
            val basePrimary = currentPrimary

            uiPrefs.themeCoverBased().set(true)
            waitUntil(timeoutMillis = 5_000) { currentPrimary != basePrimary }
            currentPrimary shouldNotBe basePrimary

            uiPrefs.themeCoverBased().set(false)
            waitUntil(timeoutMillis = 5_000) { currentPrimary == basePrimary }
            currentPrimary shouldBe basePrimary
        }
}
