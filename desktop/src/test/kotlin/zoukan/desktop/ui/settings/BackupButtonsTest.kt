package zoukan.desktop.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.FileRequest
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.compat.localized
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
class BackupButtonsTest {
    @TempDir
    lateinit var temp: File

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val picks = AtomicInteger()

    private val effects = object : SystemEffects by SystemEffects.Native {
        override fun pickFile(request: FileRequest): File? {
            picks.incrementAndGet()
            return null
        }
    }

    @AfterEach
    fun restore() {
        SystemEffects.current = SystemEffects.Native
    }

    private object Blank : Screen {
        @Composable
        override fun Content() = Unit
    }

    @Test
    fun `two clicks on Restore before the button is disabled open one picker`() = runDesktopComposeUiTest {
        Injekt.addSingleton(ZoukanDirs(temp))
        Injekt.addSingleton(mockk<ZoukanStorage> { every { isAvailable } returns false })
        SystemEffects.current = effects
        setContent { Navigator(Blank) { BackupButtons() } }

        val click = onNodeWithText(localized(MR.strings.pref_restore_backup)).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // In one frame, as a fast double click lands before the recomposition that disables the button
        runOnUiThread {
            click()
            click()
        }
        Await.until("the picker to be asked for") { picks.get() > 0 }
        waitForIdle()

        picks.get() shouldBe 1
    }
}
