package zoukan.desktop.ui.manga

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntSize
import coil3.ColorImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.manga.DescriptionImages.Part
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalTestApi::class)
class DescriptionImagesTest {
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

    /** How an address loads in a [CountingLoader]: after a delay, as the image, or failed. */
    private data class Plan(val afterMs: Long, val fails: Boolean = false)

    /** A Coil loader whose fetches are all counted, each one run as its address's [plan] says. */
    private class CountingLoader(private val plan: Map<String, Plan>) {
        /** Fetch starts and finished loads, by address. */
        val starts = ConcurrentHashMap<String, Int>()
        val ends = ConcurrentHashMap<String, Int>()

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
                return ImageFetchResult(ColorImage(0xFF88AA88.toInt(), 300, 200, 300L * 200 * 4, true), true, DataSource.NETWORK)
            }
        }

        // Coil maps an address string to its Uri before it looks for a fetcher, so the fetcher reads the address back out
        private inner class PlannedFetcherFactory : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
                data.toString().takeIf { it in plan }?.let { PlannedFetcher(it) }
        }

        // The cache key is the address, whichever type Coil looks it up by
        private class StringKeyer : Keyer<String> {
            override fun key(data: String, options: Options): String = data
        }

        private class AddressKeyer : Keyer<Uri> {
            override fun key(data: Uri, options: Options): String = data.toString()
        }
    }
}
