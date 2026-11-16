package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.CefHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Asking for Chromium on the AWT thread must not block that thread while Chromium starts. */
class JcefRuntimeEdtTest {
    @Test
    fun `asking for Chromium on the UI thread returns at once instead of blocking it`() {
        val done = CountDownLatch(1)
        var result: Any? = "not called"
        try {
            SwingUtilities.invokeLater {
                result = JcefRuntime.app()
                done.countDown()
            }
            done.await(5, TimeUnit.SECONDS) shouldBe true
            result shouldBe null
        } finally {
            // Releases an event thread blocked by the old behaviour, so later tests in this JVM still run
            CefHelper.cefApp.value = Result.failure(IllegalStateException("test over"))
            done.await(5, TimeUnit.SECONDS)
            CefHelper.cefApp.value = Result.success(null)
        }
    }
}
