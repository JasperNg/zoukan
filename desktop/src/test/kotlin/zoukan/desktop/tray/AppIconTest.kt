package zoukan.desktop.tray

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppIconTest {
    @Test
    fun `the icon is the vector master, not the fallback square`() {
        // The SVG's 512 × 512 view box; the fallback painter has no intrinsic size
        AppIcon.painter().intrinsicSize.width shouldBe 512f
    }

    @Test
    fun `each size is drawn at that size, with the teal ring and clear corners`() {
        for (size in listOf(16, 32, 256)) {
            val image = AppIcon.image(size)
            image.width shouldBe size
            image.height shouldBe size
            (image.getRGB(0, 0) ushr 24) shouldBe 0
            if (size < 32) continue
            // The ring's left side, halfway down: 36–73 of the view box's 512 units
            val ring = image.getRGB(size * 50 / 512, size / 2)
            (ring ushr 24) shouldBe 0xFF
            (ring and 0xFFFFFF) shouldBe 0x13877A
        }
    }

    @Test
    fun `the window gets the title bar and taskbar sizes of every Windows scale`() {
        // 16 px title bar and 32 px taskbar at 100%, 125%, 150%, 175%, 200% and 250%
        AppIcon.WindowSizes shouldContainAll listOf(16, 20, 24, 28, 32, 40, 48, 56, 64, 80)
        AppIcon.windowImages().map { it.width } shouldBe AppIcon.WindowSizes
    }
}
