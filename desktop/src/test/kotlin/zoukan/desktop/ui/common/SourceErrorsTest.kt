package zoukan.desktop.ui.common

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

class SourceErrorsTest {
    @Test
    fun `a source that answers 503 shows the translated server error, not the raw status`() {
        sourceErrorText(HttpException(503)) shouldBe localized(MR.strings.exception_http, 503)
    }

    @Test
    fun `a timeout and an outdated extension show what to do, not a class name`() {
        sourceErrorText(java.net.SocketTimeoutException()) shouldBe localized(DMR.strings.source_timed_out)
        sourceErrorText(AbstractMethodError("eu.kanade.Foo.bar()")) shouldBe localized(DMR.strings.source_extension_outdated)
    }

    @Test
    fun `any other failure shows a generic message, never its class name or address`() {
        val expected = localized(DMR.strings.source_request_failed)
        sourceErrorText(IllegalStateException("GET https://example.org/manga/1 failed")) shouldBe expected
        sourceErrorText(java.io.FileNotFoundException("https://example.org/cover.jpg")) shouldBe expected
        sourceErrorText(Exception("  ")) shouldBe expected
    }

    @Test
    fun `an unknown host names the host, and never leaves the name empty`() {
        sourceErrorText(java.net.UnknownHostException("example.org")) shouldBe localized(MR.strings.exception_unknown_host, "example.org")
        sourceErrorText(java.net.UnknownHostException()) shouldBe localized(DMR.strings.source_request_failed)
    }

    @Test
    fun `the unsupported image format message of the page decoder is kept`() {
        val text = localized(DMR.strings.unsupported_image_format, "JPEG XL")
        sourceErrorText(IllegalArgumentException(text)) shouldBe text
    }

    @Test
    fun `a message the source wrote for the user is kept`() {
        sourceErrorText(Exception("Log in via WebView")) shouldBe "Log in via WebView"
    }
}
