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
    fun `a message the source wrote for the user is kept`() {
        sourceErrorText(Exception("Log in via WebView")) shouldBe "Log in via WebView"
    }
}
