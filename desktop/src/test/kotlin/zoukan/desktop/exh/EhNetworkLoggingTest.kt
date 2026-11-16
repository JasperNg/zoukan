package zoukan.desktop.exh

import exh.log.maybeInjectEHLogger
import io.kotest.matchers.collections.shouldBeEmpty
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

class EhNetworkLoggingTest {
    @Test
    fun `the E-Hentai client adds no logging interceptor, so cookies and bodies never reach the log`() {
        val builder = OkHttpClient.Builder().maybeInjectEHLogger()
        builder.interceptors().shouldBeEmpty()
        builder.networkInterceptors().shouldBeEmpty()
    }
}
