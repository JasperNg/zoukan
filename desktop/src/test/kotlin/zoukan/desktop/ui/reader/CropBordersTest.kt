package zoukan.desktop.ui.reader

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ReadingModes

class CropBordersTest {
    private fun page(width: Int, height: Int, background: Int, content: Rect?): Bitmap {
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(width, height)) }
        Canvas(bitmap).apply {
            clear(background)
            if (content != null) drawRect(content, Paint().apply { color = Color.makeRGB(200, 30, 30) })
        }
        return bitmap
    }

    @Test
    fun `white margins are cut down to the content plus a small pad`() {
        val rect = PageImage.contentRect(page(400, 600, Color.WHITE, Rect.makeLTRB(100f, 150f, 300f, 450f))).shouldNotBeNull()
        // The pad is max(2, 400 / 200) = 2 pixels
        rect.left shouldBe 98
        rect.top shouldBe 148
        rect.right shouldBe 302
        rect.bottom shouldBe 452
    }

    @Test
    fun `black margins are cut too`() {
        val rect = PageImage.contentRect(page(400, 600, Color.BLACK, Rect.makeLTRB(0f, 50f, 400f, 550f))).shouldNotBeNull()
        rect.left shouldBe 0
        rect.top shouldBe 48
        rect.right shouldBe 400
        rect.bottom shouldBe 552
    }

    @Test
    fun `a page that is all margin or has a colored corner is left alone`() {
        PageImage.contentRect(page(400, 600, Color.WHITE, null)).shouldBeNull()
        PageImage.contentRect(page(400, 600, Color.makeRGB(120, 160, 90), Rect.makeLTRB(100f, 100f, 200f, 200f))).shouldBeNull()
    }

    @Test
    fun `continuous vertical crops by its own setting, as Android's crop_borders_continues_vertical`() {
        // The key and default Android backs up, so a backup carries the setting either way
        DesktopPreferences(InMemoryPreferenceStore()).cropBordersContinuousVertical().let {
            it.key() shouldBe "crop_borders_continues_vertical"
            it.defaultValue() shouldBe false
        }
        // Only continuous vertical's crop borders is on, as a restored Android backup can have it
        val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreferenceStore.InMemoryPreference("crop_borders_continues_vertical", true, false)))
        val prefs = DesktopPreferences(store)
        val png = Image.makeFromBitmap(page(400, 600, Color.WHITE, Rect.makeLTRB(100f, 150f, 300f, 450f)))
            .encodeToData(EncodedImageFormat.PNG).shouldNotBeNull().bytes
        val widths = ReadingModes.all.associateWith { mode ->
            PageImage.decode(png, cropBorders = prefs.cropBordersFor(mode).get()).width
        }
        // Only continuous vertical is cropped (to the content plus a 2 pixel pad on each side)
        widths shouldBe ReadingModes.all.associateWith { if (it == ReadingModes.CONTINUOUS_VERTICAL) 204 else 400 }
        // Webtoon keeps its own setting, and paged modes theirs
        prefs.cropBordersFor(ReadingModes.WEBTOON).key() shouldBe "crop_borders_webtoon"
        prefs.cropBordersFor(ReadingModes.RIGHT_TO_LEFT).key() shouldBe "crop_borders"
    }
}
