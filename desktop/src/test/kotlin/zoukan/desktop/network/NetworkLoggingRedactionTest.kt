package zoukan.desktop.network

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import exh.log.EHLogLevel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

/** Verbose network logging keeps URLs (as upstream) but not the values of credential-like query parameters. */
class NetworkLoggingRedactionTest {
    @TempDir
    lateinit var dir: File

    private fun setLogLevel(level: EHLogLevel) {
        val prefs = mockk<SharedPreferences> { every { getInt(any(), any()) } returns level.ordinal }
        EHLogLevel.init(
            mockk<Context> {
                every { packageName } returns "zoukan.test"
                every { getSharedPreferences(any<String>(), any<Int>()) } returns prefs
            },
        )
    }

    @AfterEach
    fun resetLogLevel() = setLogLevel(EHLogLevel.MINIMAL)

    @Test
    fun `the logging interceptor redacts token-like query parameters`() {
        setLogLevel(EHLogLevel.EXTRA)
        val context = mockk<Context> { every { cacheDir } returns dir }
        val client = NetworkHelper(context, NetworkPreferences(InMemoryPreferenceStore())).client
        val logging = client.networkInterceptors.filterIsInstance<HttpLoggingInterceptor>().single()
        val redactUrl = HttpLoggingInterceptor::class.java.methods.single { it.name.startsWith("redactUrl") }

        val url = "https://api.example.com/v1/list?page=2&access_token=s3cret&API_KEY=k&sid=abc&q=one+piece".toHttpUrl()
        val logged = redactUrl.invoke(logging, url) as String
        // OkHttp writes ██ (percent-encoded) in place of each value
        val redacted = "%E2%96%88%E2%96%88"
        logged shouldBe "https://api.example.com/v1/list?page=2&access_token=$redacted&API_KEY=$redacted&sid=$redacted&q=one%20piece"
        // An address without such parameters is logged as it is
        val plain: HttpUrl = "https://example.com/manga/1?page=3".toHttpUrl()
        redactUrl.invoke(logging, plain) shouldBe plain.toString()
    }
}
