package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.WebtoonScaleTypes

class ReaderScaleTest {
    private fun store() = DesktopPreferenceStore(JavaSharedPreferences("reader_scale_test_${System.nanoTime()}"))

    @Test
    fun `scale types match Android's numbering and SubsamplingScaleImageView's scales`() {
        // A 1000 x 2000 page in a 1600 x 1000 window
        fun scale(mode: Int) = FitModes.scale(mode, 1000, 2000, 1600, 1000, original = 1.5f)
        scale(FitModes.FIT_SCREEN) shouldBe 0.5f
        scale(FitModes.STRETCH) shouldBe 1.6f
        scale(FitModes.FIT_WIDTH) shouldBe 1.6f
        scale(FitModes.FIT_HEIGHT) shouldBe 0.5f
        scale(FitModes.ORIGINAL) shouldBe 1.5f
        // Smart fit: a tall page fits the width, a wide one the height
        scale(FitModes.SMART_FIT) shouldBe 1.6f
        FitModes.scale(FitModes.SMART_FIT, 2000, 1000, 1600, 1000, original = 1f) shouldBe 1f
        FitModes.all shouldBe listOf(1, 2, 3, 4, 5, 6)
    }

    @Test
    fun `the old desktop fit mode moves to imageScaleType once`() {
        val store = store()
        store.getInt("zoukan_reader_fit_mode", 0).set(2)
        val preferences = DesktopPreferences(store)
        preferences.imageScaleType().get() shouldBe FitModes.FIT_HEIGHT
        store.getInt("zoukan_reader_fit_mode", -1).isSet() shouldBe false
        DesktopPreferences(store).imageScaleType().get() shouldBe FitModes.FIT_HEIGHT
        DesktopPreferences(store()).imageScaleType().get() shouldBe FitModes.FIT_SCREEN
    }

    @Test
    fun `webtoon smart scale narrows the strip to the ratio on a wider window`() {
        WebtoonScaleTypes.scale(WebtoonScaleTypes.FIT, 1600f, 900f) shouldBe 1f
        // 16:9 in a 1600 x 900 window: a 506.25 wide strip
        WebtoonScaleTypes.scale("R16_9", 1600f, 900f) shouldBe 900f * 9f / 16f / 1600f
        // A window narrower than the ratio is left alone
        WebtoonScaleTypes.scale("R4_3", 600f, 900f) shouldBe 1f
    }

    @Test
    fun `automatic background reads the page's margins`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFC02020.toInt()
        PageBackground.of(400, 600) { _, _ -> white }.portrait shouldBe listOf(white)
        PageBackground.of(400, 600) { _, _ -> black }.portrait shouldBe listOf(black)
        // A plain colored page takes its color
        PageBackground.of(400, 600) { _, _ -> red }.portrait shouldBe listOf(red)
        // Black on top, white below: a gradient in a tall window, one color in a wide one
        val split = PageBackground.of(400, 600) { _, y -> if (y < 300) black else white }
        split.portrait shouldBe listOf(black, black, white, white)
        split.landscape.size shouldBe 1
        // Too small to tell
        PageBackground.of(20, 20) { _, _ -> black }.portrait shouldBe listOf(white)
    }

    @Test
    fun `tap zones overlay shows on start only when asked, and when the zones change`() {
        val zones = ReaderNavigation.zones(ReaderNavigation.DEFAULT, verticalViewer = false)
        val other = ReaderNavigation.zones(ReaderNavigation.L_SHAPED, verticalViewer = false)
        val store = store()
        val preferences = DesktopPreferences(store)
        // The first reader ever shows them, as Android does for a new user
        TapZonesOverlayState(preferences).apply { onZones(zones) }.visible shouldBe true
        // After that only with "Show tap zones overlay"
        val quiet = TapZonesOverlayState(preferences).apply { onZones(zones) }
        quiet.visible shouldBe false
        quiet.onZones(zones)
        quiet.visible shouldBe false
        quiet.onZones(other)
        quiet.visible shouldBe true
        quiet.hide()
        quiet.visible shouldBe false
        preferences.showNavigationOverlayOnStart().set(true)
        TapZonesOverlayState(preferences).apply { onZones(zones) }.visible shouldBe true
        // Disabled tap zones never show
        TapZonesOverlayState(preferences).apply { onZones(emptyList()) }.visible shouldBe false
    }
}
