package zoukan.desktop.ui.manga

import android.text.Html
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Size
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_copy_to_clipboard
import tachiyomi.i18n.action_global_search
import tachiyomi.i18n.action_search
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.action_library_search
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.fill_from_tracker
import tachiyomi.i18n.sy.thumbnail_url
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.compat.localized
import zoukan.desktop.manga.CustomCovers
import zoukan.desktop.manga.EditManga
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.DescriptionImages.Part
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Tests for manga entry presentation: [DescriptionImages] parsing/loading, HTML/CJK descriptions,
 * cover error placeholders and [CoverBasedTheme], [EntryCover] aspect ratio, and the floating
 * Read/Resume button position preference and docking.
 */
@OptIn(ExperimentalTestApi::class)
class MangaDescriptionAndCoverTest {

    /** How an address loads in a [CountingLoader]: after a delay, as the image, or failed. */
    private data class Plan(val afterMs: Long, val fails: Boolean = false, val width: Int = 300, val height: Int = 200)

    /** A Coil loader whose fetches are all counted, each one run as its address's [plan] says. */
    private class CountingLoader(private val plan: Map<String, Plan>) {
        /** Fetch starts and finished loads, by address. */
        val starts = ConcurrentHashMap<String, Int>()
        val ends = ConcurrentHashMap<String, Int>()

        /** The size each address was requested at. */
        val sizes = ConcurrentHashMap<String, Size>()

        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .components {
                add(StringKeyer())
                add(AddressKeyer())
                add(PlannedFetcherFactory())
            }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(64L * 1024 * 1024).build() }
            .build()

        /** Every fetch counts one start, waits its plan's delay, and fails or ends as its plan says. */
        private inner class PlannedFetcher(private val url: String) : Fetcher {
            override suspend fun fetch(): FetchResult {
                starts.merge(url, 1, Int::plus)
                val plan = plan.getValue(url)
                delay(plan.afterMs)
                if (plan.fails) throw IOException("planned failure for $url")
                ends.merge(url, 1, Int::plus)
                return ImageFetchResult(
                    ColorImage(0xFF88AA88.toInt(), plan.width, plan.height),
                    true,
                    DataSource.NETWORK,
                )
            }
        }

        // Coil maps an address string to its Uri before it looks for a fetcher, so the fetcher reads the address back out
        private inner class PlannedFetcherFactory : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
                data.toString().takeIf { it in plan }?.let {
                    sizes[it] = options.size
                    PlannedFetcher(it)
                }
        }

        // The cache key is the address, whichever type Coil looks it up by
        private class StringKeyer : Keyer<String> {
            override fun key(data: String, options: Options): String = data
        }

        private class AddressKeyer : Keyer<Uri> {
            override fun key(data: Uri, options: Options): String = data.toString()
        }
    }

    @Nested
    inner class DescriptionImagesParsingAndRendering {

        private fun image(url: String, label: String = "", source: String = "![$label]($url)") = Part.Image(url, label, source)

        /** The annotation `appendInlineContent` writes around every image's slot in the text. */
        private val inlineContentTag = "androidx.compose.foundation.text.inlineContent"

        /** A description that has no image this shows is one piece of text. */
        private fun staysText(description: String) {
            DescriptionImages.parse(description) shouldBe listOf(Part.Text(description))
        }

        @Test
        fun `a description without images is its text`() {
            staysText("A story.\nWith two lines, [a link](https://example.org) and an exclamation mark!")
            DescriptionImages.parse("") shouldBe emptyList()
        }

        @Test
        fun `reads the images out of the text around them`() {
            DescriptionImages.parse("Before ![Banner](https://example.org/a.png) after\n![](http://example.org/b.jpg)") shouldBe listOf(
                Part.Text("Before "),
                image("https://example.org/a.png", "Banner"),
                Part.Text(" after\n"),
                image("http://example.org/b.jpg"),
            )
            DescriptionImages.parse("![a](https://example.org/a.png)![b](https://example.org/b.png)") shouldBe listOf(
                image("https://example.org/a.png", "a"),
                image("https://example.org/b.png", "b"),
            )
        }

        @Test
        fun `an image's title is its label, or else its alt text`() {
            val url = "https://example.org/a.png"
            listOf("\"The cover\"", "'The cover'", "(The cover)").forEach { title ->
                val source = "![Alt text]($url $title)"
                DescriptionImages.parse(source) shouldBe listOf(Part.Image(url, "The cover", source))
            }
            DescriptionImages.parse("![Alt [in] brackets]($url)") shouldBe listOf(image(url, "Alt [in] brackets"))
        }

        @Test
        fun `reads addresses in angle brackets, with brackets of their own and with escapes`() {
            DescriptionImages.parse("![a](<https://example.org/a.png>)") shouldBe
                listOf(Part.Image("https://example.org/a.png", "a", "![a](<https://example.org/a.png>)"))
            DescriptionImages.parse("![a](https://example.org/a_(1).png) (more)") shouldBe
                listOf(image("https://example.org/a_(1).png", "a"), Part.Text(" (more)"))
            DescriptionImages.parse("""![a\]b](https://example.org/a\(1.png?x=1&amp;y=2)""") shouldBe
                listOf(Part.Image("https://example.org/a(1.png?x=1&y=2", "a]b", """![a\]b](https://example.org/a\(1.png?x=1&amp;y=2)"""))
        }

        @Test
        fun `only web addresses are images`() {
            staysText("![x](file:///C:/Users/me/secret.png)")
            staysText("""![x](C:\Users\me\secret.png)""")
            staysText("""![x](\\host\share\x.png)""")
            staysText("![x](//host/share/x.png)")
            staysText("![x](/covers/x.png)")
            staysText("![x](data:image/png;base64,iVBORw0KGgo=)")
            staysText("![x](ftp://example.org/x.png)")
            staysText("![x](javascript:alert(1))")
            staysText("![x](http:///x.png)")
            staysText("![x]()")
        }

        @Test
        fun `what only looks like an image stays text`() {
            staysText("""\![x](https://example.org/a.png)""")
            staysText("![x] (https://example.org/a.png)")
            staysText("![x](https://example.org/a.png")
            staysText("![x](https://example.org/a.png \"title)")
            staysText("Wow![really]")
        }

        @Test
        fun `an image in a link is still an image`() {
            DescriptionImages.parse("[![alt](https://example.org/i.png)](https://example.org)") shouldBe listOf(
                Part.Text("["),
                image("https://example.org/i.png", "alt"),
                Part.Text("](https://example.org)"),
            )
        }

        @Test
        fun `a long description that only looks full of images reads without trouble`() {
            staysText("![".repeat(20_000))
            staysText("![](".repeat(10_000))
            staysText("![" + "a".repeat(100_000) + "](https://example.org/a.png)")
            staysText("""![a](https://example.org/""" + "\\(".repeat(50_000))
        }

        @Test
        fun `an image shows at its own size, or as wide as there is room`() {
            DescriptionImages.fit(IntSize(100, 50), maxWidth = 400) shouldBe IntSize(100, 50)
            DescriptionImages.fit(IntSize(400, 300), maxWidth = 400) shouldBe IntSize(400, 300)
            DescriptionImages.fit(IntSize(800, 400), maxWidth = 400) shouldBe IntSize(400, 200)
            DescriptionImages.fit(IntSize(800, 8000), maxWidth = 400) shouldBe IntSize(400, 4000)
            DescriptionImages.fit(IntSize(5000, 2), maxWidth = 400) shouldBe IntSize(400, 1)
        }

        @Test
        fun `the annotations hold one slot per image, however the images show`() {
            val first = "https://example.org/a.png"
            val second = "https://example.org/b.png"
            val parts = DescriptionImages.parse("Before ![A]($first) then ![B]($second)")
            listOf(true, false).forEach { loadImages ->
                val annotated = DescriptionImages.annotate(parts, loadImages, Color.Red)
                val slots = annotated.getStringAnnotations(inlineContentTag, 0, annotated.length)
                // One slot per image, in order, whether the images show or are their labels: a slot is
                // never taken out, so the slots after it are never rebuilt (foundation lays the slots
                // out by position, so that would fetch the images still loading in them again)
                slots.size shouldBe 2
                if (loadImages) slots.map { it.item } shouldBe listOf(first, second)
            }
        }

        @Test
        fun `a failed image shows as its label where it was, and the images after it do not fetch again`() {
            val broken = "https://example.org/broken.png"
            val slow = "https://example.org/slow.png"
            val later = "https://example.org/later.png"
            val loader = CountingLoader(
                mapOf(
                    broken to Plan(afterMs = 100, fails = true),
                    // The failure lands while these two are still loading
                    slow to Plan(afterMs = 1_000),
                    later to Plan(afterMs = 2_000),
                ),
            )
            runDesktopComposeUiTest {
                setContent {
                    MaterialTheme {
                        DescriptionImagesText(
                            "Before ![Broken]($broken) ![Slow]($slow) and ![Later]($later) after.",
                            maxLines = Int.MAX_VALUE,
                            onTextLayout = {},
                            loadImages = true,
                            loader = loader.loader,
                        )
                    }
                }
                // The failed image keeps its slot, drawn as its label, clickable like the link form
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("failed-image").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("failed-image").assert(hasClickAction() and hasText("Broken"))
                // Even though the failure landed while the others were still loading, each fetched once
                waitUntil(timeoutMillis = 20_000) { loader.ends[slow] == 1 && loader.ends[later] == 1 }
                waitForIdle()
                loader.starts.toMap() shouldBe mapOf(broken to 1, slow to 1, later to 1)
            }
        }

        @Test
        fun `turning images off shows every image as its label, on again the failed one stays failed`() {
            val broken = "https://example.org/broken.png"
            val slow = "https://example.org/slow.png"
            val later = "https://example.org/later.png"
            val loader = CountingLoader(
                mapOf(
                    broken to Plan(afterMs = 100, fails = true),
                    slow to Plan(afterMs = 300),
                    later to Plan(afterMs = 300),
                ),
            )
            var loadImages by mutableStateOf(true)
            runDesktopComposeUiTest {
                setContent {
                    MaterialTheme {
                        DescriptionImagesText(
                            "Before ![Broken]($broken) ![Slow]($slow) and ![Later]($later) after.",
                            maxLines = Int.MAX_VALUE,
                            onTextLayout = {},
                            loadImages = loadImages,
                            loader = loader.loader,
                        )
                    }
                }
                waitUntil(timeoutMillis = 10_000) { loader.ends[slow] == 1 && loader.ends[later] == 1 }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("failed-image").fetchSemanticsNodes().isNotEmpty() }
                // Off: every image is its label, a link, and nothing fetches while it is off
                runOnUiThread { loadImages = false }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("failed-image").fetchSemanticsNodes().isEmpty() }
                onNodeWithText("Broken", substring = true).assertExists()
                loader.starts.toMap() shouldBe mapOf(broken to 1, slow to 1, later to 1)
                // On again: the images show in their slots again, and the failed one stays failed,
                // drawn as its label where the image was, without fetching again
                runOnUiThread { loadImages = true }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Later").fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("failed-image").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                loader.starts[broken] shouldBe 1
            }
        }

        @Test
        fun `with images off, no image ever loads and each is its label`() {
            val loader = CountingLoader(emptyMap())
            runDesktopComposeUiTest {
                setContent {
                    MaterialTheme {
                        DescriptionImagesText(
                            "Before ![Broken](https://example.org/broken.png) ![Slow](https://example.org/slow.png) after.",
                            maxLines = 6,
                            onTextLayout = {},
                            loadImages = false,
                            loader = loader.loader,
                        )
                    }
                }
                waitForIdle()
                onNodeWithText("Broken", substring = true).assertExists()
                onNodeWithText("Slow", substring = true).assertExists()
                waitForIdle()
                loader.starts.toMap() shouldBe emptyMap()
            }
        }

        @Test
        fun `a large description image is requested no wider than the text`() {
            val huge = "https://example.org/huge.png"
            val loader = CountingLoader(mapOf(huge to Plan(afterMs = 10, width = 8_000, height = 8_000)))
            runDesktopComposeUiTest {
                var widthPx = 0
                setContent {
                    widthPx = with(LocalDensity.current) { 400.dp.roundToPx() }
                    MaterialTheme {
                        Box(Modifier.width(400.dp)) {
                            DescriptionImagesText("![Huge]($huge)", Int.MAX_VALUE, {}, loadImages = true, loader = loader.loader)
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { loader.sizes.containsKey(huge) }
                val requested = loader.sizes.getValue(huge)
                requested shouldNotBe Size.ORIGINAL
                val requestedWidth = (requested.width as Dimension.Pixels).px
                requestedWidth shouldBeLessThanOrEqual widthPx
            }
        }

        @Test
        fun `opening a failed description image does not call the browser on the UI thread`() {
            val broken = "https://example.org/broken.png"
            val loader = CountingLoader(mapOf(broken to Plan(afterMs = 10, fails = true)))
            val opened = CountDownLatch(1)
            var browserThread: Thread? = null
            var uiThread: Thread? = null
            val original = DescriptionImageLinks.browse
            try {
                DescriptionImageLinks.browse = {
                    browserThread = Thread.currentThread()
                    opened.countDown()
                }
                runDesktopComposeUiTest {
                    setContent {
                        MaterialTheme {
                            DescriptionImagesText("![Broken]($broken)", Int.MAX_VALUE, {}, loadImages = true, loader = loader.loader)
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("failed-image").fetchSemanticsNodes().isNotEmpty() }
                    // The click handler runs on the thread that delivers the click: the UI thread
                    uiThread = Thread.currentThread()
                    onNodeWithTag("failed-image").performClick()
                    opened.await(5, TimeUnit.SECONDS) shouldBe true
                    browserThread shouldNotBe uiThread
                }
            } finally {
                DescriptionImageLinks.browse = original
            }
        }
    }

    /**
     * Covers HTML-to-text descriptions, CJK text rendering, broken cover placeholder (`cover-error`),
     * entry tag/title context menus, `EditMangaDialog` tracker/thumbnail fields, and `CoverBasedTheme`.
     */
    @Nested
    inner class HtmlDescriptionAndCoverError {

        @JvmField
        @RegisterExtension
        val injekt = FreshInjekt()

        @TempDir
        lateinit var tempDir: File

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
            Injekt.addSingleton(UiPreferences(TestPreferenceStore()))
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
                            TextButton(onClick = openMenu) { Text("Open tag") }
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
                val store = TestPreferenceStore()
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

        @Test
        fun `CoverBasedTheme preserves content state when themeCoverBased toggles and palette loads`() =
            runDesktopComposeUiTest(effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
                val uiPrefs = UiPreferences(store)
                Injekt.addSingleton(uiPrefs)
                val bluePng = solidPngBytes(0x2266EE)
                val client = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(bluePng.toResponseBody("image/png".toMediaType()))
                            .build()
                    }
                    .build()
                Injekt.addSingleton(mockk<NetworkHelper> { every { this@mockk.client } returns client })
                Injekt.addSingleton(mockk<SourceManager>(relaxed = true) { every { this@mockk.get(any()) } returns null })

                val manga = Manga.create().copy(
                    id = 702,
                    source = 4242L,
                    ogTitle = "State Preservation Cover",
                    ogThumbnailUrl = "https://example.invalid/blue-cover.png",
                    initialized = true,
                )

                uiPrefs.themeCoverBased().set(false)
                var currentPrimary = Color.Unspecified
                var observedCount = -1
                setContent {
                    MaterialTheme {
                        CoverBasedTheme(manga) {
                            currentPrimary = MaterialTheme.colorScheme.primary
                            var count by remember { mutableStateOf(0) }
                            observedCount = count
                            TextButton(onClick = { count++ }) {
                                Text("Count: $count")
                            }
                        }
                    }
                }
                waitForIdle()
                val basePrimary = currentPrimary

                onNodeWithText("Count: 0").performClick()
                waitUntil(timeoutMillis = 5_000) { observedCount == 1 }
                onNodeWithText("Count: 1").assertExists()

                uiPrefs.themeCoverBased().set(true)
                waitUntil(timeoutMillis = 5_000) { currentPrimary != basePrimary }
                waitForIdle()
                observedCount shouldBe 1
                onNodeWithText("Count: 1").assertExists()

                uiPrefs.themeCoverBased().set(false)
                waitUntil(timeoutMillis = 5_000) { currentPrimary == basePrimary }
                waitForIdle()
                observedCount shouldBe 1
                onNodeWithText("Count: 1").assertExists()
            }
    }

    @Nested
    inner class EntryCoverAspectRatio {

        @Test
        fun `a wide cover is a panorama only while the setting is on`() {
            EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 0.5f) shouldBe EntryCover.PANORAMA_RATIO
            EntryCover.aspectRatio(usePanoramaCover = false, coverRatio = 0.5f) shouldBe EntryCover.BOOK_RATIO
            EntryCover.PANORAMA_RATIO shouldBe 3f / 2f
            EntryCover.BOOK_RATIO shouldBe 2f / 3f
        }

        @Test
        fun `a cover is wide from three quarters as tall as it is wide, as Komikku's`() {
            EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 0.75f) shouldBe EntryCover.PANORAMA_RATIO
            EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 0.76f) shouldBe EntryCover.BOOK_RATIO
        }

        @Test
        fun `a book cover, a square one and one that has not loaded yet stay book covers`() {
            EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 1.5f) shouldBe EntryCover.BOOK_RATIO
            // 1 is the ratio until the cover has loaded, as Komikku's
            EntryCover.aspectRatio(usePanoramaCover = true, coverRatio = 1f) shouldBe EntryCover.BOOK_RATIO
        }
    }

    /**
     * The Resume button's docking side persists under the Android app's preference, so both apps
     * keep each other's choice and start on the same side.
     */
    @Nested
    inner class ReadButtonPosition {

        private val preferences = UiPreferences(
            DesktopPreferenceStore(
                JavaSharedPreferences("read_button_test_${System.nanoTime()}").also {
                    System.setProperty("$CONFIG_PREFIX.server.rootDir", Files.createTempDirectory("zoukan-read-button").toFile().absolutePath)
                },
            ),
        )

        @Test
        fun `docks through Android's key, defaulting to the End side`() {
            preferences.readButtonPosition().key() shouldBe "reading_button_position"
            // Android writes "End"; desktop's material3 spells the same side "FabPosition.End"
            preferences.readButtonPosition().get() shouldBe FabPosition.End.toString()
        }

        @Test
        fun `dragging the button to a side persists Android's spelling`() {
            preferences.readButtonPosition().set("Start")
            preferences.readButtonPosition().get() shouldBe "Start"
            preferences.readButtonPosition().set("End")
            preferences.readButtonPosition().get() shouldBe "End"
        }
    }

    /** The Resume button docks to the side its center was dropped on, as on Android. */
    @Nested
    inner class ResumeButtonDock {

        @Test
        fun `docks to the half the button's center ends on`() {
            // A 200 px button in a 1000 px area: its center crosses the middle at x = 400
            docksAtStart(buttonX = 0f, buttonWidth = 200, areaWidth = 1000) shouldBe true
            docksAtStart(buttonX = 399f, buttonWidth = 200, areaWidth = 1000) shouldBe true
            docksAtStart(buttonX = 400f, buttonWidth = 200, areaWidth = 1000) shouldBe false
            docksAtStart(buttonX = 800f, buttonWidth = 200, areaWidth = 1000) shouldBe false
        }
    }
}
