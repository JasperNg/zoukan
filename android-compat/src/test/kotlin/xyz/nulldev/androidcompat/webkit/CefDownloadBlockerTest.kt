package xyz.nulldev.androidcompat.webkit

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.junit.jupiter.api.Test

/** No page saves a file: CefDownloadBlocker (KcefContentRules.kt) cancels every download. Runs without Chromium. */
class CefDownloadBlockerTest {
    private fun item(
        complete: Boolean = false,
        canceled: Boolean = false,
    ): CefDownloadItem =
        mockk {
            every { isComplete } returns complete
            every { isCanceled } returns canceled
        }

    @Test
    fun `a download is handled and never given a place to go`() {
        val callback = mockk<CefBeforeDownloadCallback>(relaxed = true)
        CefDownloadBlocker.onBeforeDownload(null, item(), "chapter.zip", callback) shouldBe true
        verify(exactly = 0) { callback.Continue(any(), any()) }
    }

    @Test
    fun `a download that started anyway is cancelled while it runs`() {
        val callback = mockk<CefDownloadItemCallback>(relaxed = true)
        CefDownloadBlocker.onDownloadUpdated(null, item(), callback)
        verify(exactly = 1) { callback.cancel() }
    }

    @Test
    fun `a finished or cancelled download is left alone`() {
        val callback = mockk<CefDownloadItemCallback>(relaxed = true)
        CefDownloadBlocker.onDownloadUpdated(null, item(complete = true), callback)
        CefDownloadBlocker.onDownloadUpdated(null, item(canceled = true), callback)
        CefDownloadBlocker.onDownloadUpdated(null, null, callback)
        verify(exactly = 0) { callback.cancel() }
    }

    @Test
    fun `a download whose state cannot be read is cancelled, and a failing cancel does not throw`() {
        val unreadable = mockk<CefDownloadItem> { every { isComplete } throws IllegalStateException("gone") }
        val callback = mockk<CefDownloadItemCallback> { every { cancel() } throws IllegalStateException("gone") }
        CefDownloadBlocker.onDownloadUpdated(null, unreadable, callback)
        verify(exactly = 1) { callback.cancel() }
        // No callback at all
        CefDownloadBlocker.onDownloadUpdated(null, item(), null)
    }
}
