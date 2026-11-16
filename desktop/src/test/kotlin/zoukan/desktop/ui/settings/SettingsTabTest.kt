package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
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
    fun `a disabled setting row reports that it is disabled`() = runDesktopComposeUiTest {
        setContent {
            Column {
                SettingRow("Locked", "Why", enabled = false, onClick = {})
                SettingRow("Plain", "Why", enabled = false, onClick = null)
                SettingRow("Open", "Why", enabled = true, onClick = {})
            }
        }
        onNodeWithText("Locked").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        onNodeWithText("Plain").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        onNodeWithText("Open").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
    }

    @Test
    fun `a disabled setting row's title is drawn at the disabled alpha`() {
        // A colour keeps its alpha in 8 bits, so 0.38 comes back a little off
        settingTitleColor(false, lightColorScheme()).alpha shouldBe (0.38f plusOrMinus 0.01f)
        settingTitleColor(true, lightColorScheme()) shouldBe lightColorScheme().onSurface
    }

    @Test
    fun `settings pages are in the Android app's order`() {
        SettingsCategory.entries.map { it.title }.take(4) shouldBe listOf("Appearance", "Library", "Reader", "Downloads")
        SettingsCategory.entries.last() shouldBe SettingsCategory.About
    }
}
