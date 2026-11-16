package zoukan.desktop.harness

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

class WiringProbeTest {

    @Test
    fun `a probe an interceptor sends to an outside host is blocked and noted`() {
        val looked = CopyOnWriteArrayList<String>()
        // Stands in for the system resolver: records the lookup and fails, so nothing leaves the machine
        val recordingDns = Dns { host ->
            looked += host
            throw UnknownHostException(host)
        }
        // A source client whose interceptor sends every request to the site's own host
        val sourceClient = OkHttpClient.Builder()
            .dns(recordingDns)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url("https://manga.example/api").build()) }
            .build()
        val blocked = CopyOnWriteArrayList<String>()
        runCatching {
            ExtensionWiringCheck.probeClient(sourceClient, blocked)
                .newCall(Request.Builder().url("http://127.0.0.1:9/wiring/1").build()).execute().close()
        }
        looked.shouldBeEmpty()
        blocked shouldContainExactly listOf("manga.example")
    }
}
