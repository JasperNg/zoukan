package zoukan.desktop.ui.settings

import androidx.compose.ui.graphics.Color
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SettingsTabTest {

    private fun Color.luminanceSum() = red + green + blue

    @Test
    fun `the settings list is darker than a dark surface and lighter than a light one`() {
        val dark = Color(0xFF1B1B1F)
        val light = Color(0xFFFEFBFF)
        palerSurface(dark).luminanceSum() shouldBeLessThan dark.luminanceSum()
        palerSurface(light).luminanceSum() shouldBeGreaterThan Color(0xFFF0F0F0).luminanceSum()
        palerSurface(Color.White) shouldBe Color.White
    }

    @Test
    fun `settings pages are in the Android app's order`() {
        SettingsCategory.entries.map { it.title }.take(4) shouldBe listOf("Appearance", "Library", "Reader", "Downloads")
        SettingsCategory.entries.last() shouldBe SettingsCategory.About
    }
}
