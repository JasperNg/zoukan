package zoukan.desktop.property

import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.service.ExtensionStoreService
import org.junit.jupiter.api.Test
import org.xml.sax.SAXException
import zoukan.desktop.Platform
import zoukan.desktop.app.Version
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.desktop.update.UpdateChecker
import java.io.IOException

/** Inputs [OutsideDataParserPropertyTest] found that a parser did not handle, kept as regression tests. */
class ParserKnownDefectsTest {

    @Test
    fun `a deeply nested ComicInfo xml is a parse error, not a stack overflow`() {
        val xml = "<ComicInfo><Series>" + "<a>".repeat(COMIC_INFO_DEPTH) + "x" + "</a>".repeat(COMIC_INFO_DEPTH) + "</Series></ComicInfo>"
        val outcome = runCatching { ComicInfoXml.read(xml.byteInputStream()) }
        val error = outcome.exceptionOrNull()
        (error == null || error is SAXException || error is IOException) shouldBe true
    }

    @Test
    fun `release notes from an answer that is not a release are a failed result`() = runBlocking<Unit> {
        val checker = UpdateChecker(FakeHttp { _, _ -> ByteArray(0) }.client, MemorySecretStore(), repo = "o/r", platform = Platform.WINDOWS)
        val result = runCatching { checker.releaseNotes(Version(listOf(9, 9, 9))) }
        result.isSuccess shouldBe true
        result.getOrThrow().isFailure shouldBe true
    }

    @Test
    fun `a store index that names itself as its index_v2 gives a failed result`() = runBlocking<Unit> {
        val url = "https://store.test/repo.json"
        val index = """{"index_v2":"$url","meta":{"name":"S","website":"https://store.test","signingKeyFingerprint":"00"}}"""
        val http = FakeHttp { _, _ -> index.toByteArray() }
        val service = ExtensionStoreService(
            http.network,
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
            ProtoBuf,
        )
        val result = withTimeoutOrNull(5_000) { service.fetch(url) }
        http.requests.get() shouldBeLessThan 10
        (result != null && result.isFailure) shouldBe true
    }

    companion object {
        /** Deep enough to overflow a default thread stack; the smallest depth depends on the stack size. */
        const val COMIC_INFO_DEPTH = 100_000
    }
}
