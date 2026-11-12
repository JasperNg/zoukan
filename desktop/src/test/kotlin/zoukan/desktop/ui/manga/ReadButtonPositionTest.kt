package zoukan.desktop.ui.manga

import androidx.compose.material3.FabPosition
import eu.kanade.domain.ui.UiPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.nio.file.Files

/**
 * The Resume button's docking side persists under the Android app's preference, so both apps
 * keep each other's choice and start on the same side.
 */
class ReadButtonPositionTest {

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
