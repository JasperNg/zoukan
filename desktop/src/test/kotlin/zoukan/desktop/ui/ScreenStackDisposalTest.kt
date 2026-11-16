package zoukan.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.manga.MangaScreen

private class Probe : ScreenModel {
    var disposed = false

    override fun onDispose() {
        disposed = true
    }
}

/** Shows a probe model under the key of [real], so the real key decides what Voyager disposes. */
private class KeyOf(val real: Screen, val models: MutableList<Probe>) : Screen {
    override val key: ScreenKey = real.key

    @Composable
    override fun Content() {
        val model = rememberScreenModel { Probe().also { models += it } }
        Text(key + model.hashCode())
    }
}

/** The same screen twice on the stack must not share a model: going back from one cancels only its own. */
@OptIn(ExperimentalTestApi::class)
class ScreenStackDisposalTest {

    @Test
    fun `going back from a second copy keeps the first copy's model`() = runDesktopComposeUiTest {
        var navigator: Navigator? = null
        val first = mutableListOf<Probe>()
        setContent {
            Navigator(KeyOf(MangaScreen(1), first)) {
                navigator = it
                CurrentScreen()
            }
        }
        waitForIdle()
        runOnUiThread { navigator!!.push(KeyOf(MangaScreen(2), mutableListOf())) }
        waitForIdle()
        runOnUiThread { navigator!!.push(KeyOf(MangaScreen(1), mutableListOf())) }
        waitForIdle()
        runOnUiThread { navigator!!.pop() }
        waitForIdle()
        first.single().disposed shouldBe false
    }

    @Test
    fun `going back from a screen disposes its own model`() = runDesktopComposeUiTest {
        var navigator: Navigator? = null
        val upper = mutableListOf<Probe>()
        setContent {
            Navigator(KeyOf(MangaScreen(1), mutableListOf())) {
                navigator = it
                CurrentScreen()
            }
        }
        waitForIdle()
        runOnUiThread { navigator!!.push(KeyOf(MangaScreen(2), upper)) }
        waitForIdle()
        runOnUiThread { navigator!!.pop() }
        waitForIdle()
        upper.single().disposed shouldBe true
    }
}
