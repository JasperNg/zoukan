package zoukan.desktop.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.service.ExtensionStoreService
import mihon.data.extension.service.InsecureExtensionStoreException
import org.junit.jupiter.api.Test
import zoukan.desktop.property.FakeHttp
import zoukan.desktop.property.ValidDocuments

/**
 * A legacy store's repo.json can name the store's new-format index in `index_v2`
 * ([ExtensionStoreService.fetch]). That link is followed once: the new index is the end of it, so
 * a repo.json that names itself, a cycle or a chain of legacy repos fails instead of being fetched
 * forever.
 */
class ExtensionStoreIndexV2Test {
    private val service = { http: FakeHttp ->
        ExtensionStoreService(
            http.network,
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
            ProtoBuf,
        )
    }

    private fun legacyRepo(indexV2: String) =
        """{"index_v2":"$indexV2","meta":{"name":"S","website":"https://store.test","signingKeyFingerprint":"00"}}"""

    private fun fetch(answers: Map<String, String>, url: String): Pair<Result<*>, Int> {
        val http = FakeHttp { request, _ -> answers[request.url.toString()]?.toByteArray() }
        val result = runBlocking { withTimeout(5_000) { service(http).fetch(url) } }
        return result to http.requests.get()
    }

    @Test
    fun `a legacy repo's index_v2 is followed to the new index`() {
        val (result, requests) = fetch(
            mapOf(
                "https://store.test/repo.json" to legacyRepo("https://store.test/index.json"),
                "https://store.test/index.json" to ValidDocuments.storeIndex,
            ),
            "https://store.test/repo.json",
        )
        val store = result.getOrThrow().shouldBeInstanceOf<mihon.domain.extension.model.ExtensionStore>()
        store.indexUrl shouldBe "https://store.test/index.json"
        store.isLegacy shouldBe false
        requests shouldBe 2
    }

    @Test
    fun `an index_v2 on another https host is followed, as on Android`() {
        val (result, requests) = fetch(
            mapOf(
                "https://store.test/repo.json" to legacyRepo("https://cdn.store.test/index.json"),
                "https://cdn.store.test/index.json" to ValidDocuments.storeIndex,
            ),
            "https://store.test/repo.json",
        )
        result.isSuccess shouldBe true
        requests shouldBe 2
    }

    @Test
    fun `a repo that names itself as its index_v2 fails after one hop`() {
        val url = "https://store.test/repo.json"
        val (result, requests) = fetch(mapOf(url to legacyRepo(url)), url)
        result.isFailure shouldBe true
        requests shouldBe 2
    }

    @Test
    fun `two repos that name each other fail after one hop`() {
        val (result, requests) = fetch(
            mapOf(
                "https://a.test/repo.json" to legacyRepo("https://b.test/repo.json"),
                "https://b.test/repo.json" to legacyRepo("https://a.test/repo.json"),
            ),
            "https://a.test/repo.json",
        )
        result.isFailure shouldBe true
        requests shouldBe 2
    }

    @Test
    fun `an index_v2 that is relative or plain http fails without following it`() {
        val relative = fetch(mapOf("https://store.test/repo.json" to legacyRepo("index.json")), "https://store.test/repo.json")
        relative.first.isFailure shouldBe true
        relative.second shouldBe 1

        val http = fetch(mapOf("https://store.test/repo.json" to legacyRepo("http://store.test/index.json")), "https://store.test/repo.json")
        http.first.exceptionOrNull().shouldBeInstanceOf<InsecureExtensionStoreException>()
        http.second shouldBe 1
    }
}
