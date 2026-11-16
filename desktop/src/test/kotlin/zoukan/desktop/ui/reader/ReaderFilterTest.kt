package zoukan.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.reader.ReaderFilter.Companion.readerFilter

@OptIn(ExperimentalTestApi::class)
class ReaderFilterTest {
    @Test
    fun `grayscale and inverted together give the product of the two matrices`() {
        val expected = floatArrayOf(
            -0.2126f, -0.7152f, -0.0722f, 0f, 1f,
            -0.2126f, -0.7152f, -0.0722f, 0f, 1f,
            -0.2126f, -0.7152f, -0.0722f, 0f, 1f,
            0f, 0f, 0f, 1f, 0f,
        )
        val actual = ReaderFilter.matrix(ReaderFilter(grayscale = true, inverted = true))!!.mat
        actual.size shouldBe 20
        expected.forEachIndexed { i, e -> actual[i] shouldBe (e plusOrMinus 1e-4f) }
    }

    @Test
    fun `only a blend overlay needs offscreen compositing`() {
        ReaderFilter.compositingFor(ReaderFilter(grayscale = true)) shouldBe CompositingStrategy.Auto
        ReaderFilter.compositingFor(ReaderFilter(color = 0x80FF0000.toInt(), mode = 1)) shouldBe CompositingStrategy.Offscreen
        ReaderFilter.compositingFor(ReaderFilter(brightness = -40)) shouldBe CompositingStrategy.Offscreen
    }

    @Test
    fun `the colour filter is built once while the filter does not change`() = runDesktopComposeUiTest {
        var tick by mutableStateOf(0)
        setContent {
            Text("$tick")
            val f = ReaderFilter(grayscale = true)
            Box(Modifier.size(100.dp).readerFilter(f))
        }
        waitForIdle()
        ReaderFilter.effectsBuilt = 0
        repeat(5) {
            tick++
            waitForIdle()
        }
        ReaderFilter.effectsBuilt shouldBeLessThanOrEqualTo 1
    }
}
