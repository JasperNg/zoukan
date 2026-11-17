package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * On macOS, Chromium takes over Cmd+Q and asks the app before it shuts down. The windowed app
 * must quit itself instead: when JCEF shut Chromium down under the running app, a second Cmd+Q
 * crashed in the shut-down framework.
 */
class JcefQuitTest {

    @Test
    fun `with the app's quit installed, Cmd+Q runs it and JCEF does not shut Chromium down itself`() {
        var quits = 0
        JcefRuntime.onBeforeTerminate { quits++ } shouldBe true
        quits shouldBe 1
    }

    @Test
    fun `once the app is quitting, another Cmd+Q still does not let JCEF shut Chromium down`() {
        JcefRuntime.onBeforeTerminate(JcefRuntime.QUIT_UNDERWAY) shouldBe true
    }

    @Test
    fun `without it, as in the command-line tools, JCEF's own shutdown ends the run`() {
        JcefRuntime.onBeforeTerminate(null) shouldBe false
    }
}
