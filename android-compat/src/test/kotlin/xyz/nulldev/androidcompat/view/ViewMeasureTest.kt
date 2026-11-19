package xyz.nulldev.androidcompat.view

import android.view.View
import android.view.View.MeasureSpec
import android.webkit.ConsoleMessage
import io.kotest.matchers.shouldBe
import org.cef.CefSettings
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider

class ViewMeasureTest {

    @Test
    fun `measure specs round trip like Android`() {
        val exactly = MeasureSpec.makeMeasureSpec(1280, MeasureSpec.EXACTLY)
        exactly shouldBe (1280 or 1073741824)
        MeasureSpec.getMode(exactly) shouldBe MeasureSpec.EXACTLY
        MeasureSpec.getSize(exactly) shouldBe 1280

        val atMost = MeasureSpec.makeMeasureSpec(720, MeasureSpec.AT_MOST)
        MeasureSpec.getMode(atMost) shouldBe MeasureSpec.AT_MOST
        MeasureSpec.getSize(atMost) shouldBe 720
        MeasureSpec.toString(atMost) shouldBe "MeasureSpec: AT_MOST 720"

        View.resolveSize(1000, atMost) shouldBe 720
        View.getDefaultSize(10, exactly) shouldBe 1280
        View.getDefaultSize(10, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) shouldBe 10
    }

    @Test
    fun `a view takes the size it is measured and laid out at`() {
        val view = View(null)
        view.measure(
            MeasureSpec.makeMeasureSpec(300, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY),
        )
        view.measuredWidth shouldBe 300
        view.measuredHeight shouldBe 200
        view.layout(0, 0, 300, 200)
        view.width shouldBe 300
        view.height shouldBe 200
        view.isLaidOut shouldBe true
        view.isVerticalScrollBarEnabled = false
        view.isVerticalScrollBarEnabled shouldBe false
        view.invalidate()
    }

    @Test
    fun `console messages keep what they were given`() {
        val message = ConsoleMessage("result:42", "https://example.org/app.js", 7, ConsoleMessage.MessageLevel.WARNING)
        message.message() shouldBe "result:42"
        message.sourceId() shouldBe "https://example.org/app.js"
        message.lineNumber() shouldBe 7
        message.messageLevel() shouldBe ConsoleMessage.MessageLevel.WARNING
        // console.debug, console.log and console.error from Chromium
        KcefWebViewProvider.consoleLevel(CefSettings.LogSeverity.LOGSEVERITY_VERBOSE) shouldBe ConsoleMessage.MessageLevel.DEBUG
        KcefWebViewProvider.consoleLevel(CefSettings.LogSeverity.LOGSEVERITY_INFO) shouldBe ConsoleMessage.MessageLevel.LOG
        KcefWebViewProvider.consoleLevel(CefSettings.LogSeverity.LOGSEVERITY_ERROR) shouldBe ConsoleMessage.MessageLevel.ERROR
    }
}
