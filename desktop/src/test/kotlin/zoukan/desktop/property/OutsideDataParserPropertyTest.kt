package zoukan.desktop.property

import eu.kanade.tachiyomi.data.backup.BackupCodec
import io.kotest.property.checkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.xml.sax.SAXException
import zoukan.desktop.Platform
import zoukan.desktop.app.Version
import zoukan.desktop.backup.SyntheticBackup
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.property.Mutations.Format
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.desktop.update.UpdateChecker
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Parsers of data from outside the app, given any input (random bytes, random text, a valid
 * document broken in [Mutations]' ways), return a result or the error their caller handles, never
 * another exception, and return in bounded time:
 *
 * - `ComicInfo.xml` ([ComicInfoXml.read]): a [SAXException] or [IOException]; LocalSource and
 *   Archives catch it and read the entry without it.
 * - An extension store's index and extension list ([ExtensionStoreService.fetch] and
 *   `getExtensions`): a `Result`, failed for any bad index; they must not throw.
 * - The update manifest, GitHub's releases list ([UpdateChecker.select], [UpdateChecker.parseNotes]):
 *   a [SerializationException] (an [IllegalArgumentException]) for text that is not the format;
 *   and through the network calls that read them, [UpdateChecker.check] and
 *   [UpdateChecker.releaseNotes], which return their own result types.
 * - A backup file ([BackupCodec.decode]): an [BackupCodec.InvalidBackupException].
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class OutsideDataParserPropertyTest {

    /** What a parser did with one input. */
    private class Outcomes(private val parser: String) {
        val found = Counterexamples<String>()
        var slowest = 0L
        var handled = 0
        var parsed = 0

        inline fun run(
            input: ByteArray,
            handledError: (Throwable) -> Boolean,
            kind: (Throwable) -> String = { it::class.qualifiedName.orEmpty() },
            block: () -> Unit,
        ) {
            val start = System.nanoTime()
            try {
                block()
                parsed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (handledError(e)) handled++ else found.add("$parser: ${kind(e)}", input.toString(Charsets.ISO_8859_1).take(300), e.toString().take(300))
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            if (ms > slowest) slowest = ms
            if (ms > MAX_MILLIS_PER_INPUT) found.add("$parser: slow", input.toString(Charsets.ISO_8859_1).take(300), "$ms ms")
        }

        fun check(known: Set<String> = emptySet()) {
            println("$parser: $parsed parsed, $handled handled errors, slowest ${slowest}ms")
            val unknown = found.kinds() - known
            if ((found.kinds() intersect known).isNotEmpty()) println("Known parser defects seen:\n" + found.report(found.kinds() intersect known))
            check(unknown.isEmpty()) { "Unhandled results (seed ${PropertyRuns.seed}):\n" + found.report(unknown) }
        }
    }

    @Test
    fun `ComicInfo xml gives values or a parse error`() = runBlocking<Unit> {
        val valid = listOf(ValidDocuments.comicInfo.toByteArray(), ComicInfoXml(mapOf("Series" to "S", "Number" to "1")).write().toByteArray())
        val outcomes = Outcomes("ComicInfoXml.read")
        checkAll(PropertyRuns.config(ITERATIONS), arbFrom { Mutations.input(it, valid, Format.XML) }) { input ->
            outcomes.run(
                input,
                { it is SAXException || it is IOException },
            ) { ComicInfoXml.read(input.inputStream()) }
        }
        outcomes.check()
    }

    @Test
    fun `an extension store index gives a store or a failed result`() = runBlocking<Unit> {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val proto = ProtoBuf.encodeToByteArray(json.decodeFromString<NetworkExtensionStore>(ValidDocuments.storeIndex))
        val valid = listOf(ValidDocuments.storeIndex, ValidDocuments.legacyRepo, ValidDocuments.legacyIndex).map { it.toByteArray() } + listOf(proto)
        val outcomes = Outcomes("ExtensionStoreService")
        checkAll(PropertyRuns.config(ITERATIONS), arbFrom { r -> Mutations.input(r, valid, if (r.nextBoolean()) Format.JSON else Format.BINARY) to r.nextInt(4) }) { (input, url) ->
            // Every request gets the input, so an index_v2 that names the index itself is fetched
            // again: once, as the hop limit allows (ExtensionStoreIndexV2Test)
            val http = FakeHttp { _, _ -> input }
            val service = ExtensionStoreService(http.network, json, ProtoBuf)
            val indexUrl = listOf("https://store.test/index.min.json", "https://store.test/repo.json", "https://store.test/index.pb", "https://store.test/").get(url)
            outcomes.run(input, { false }) {
                service.fetch(indexUrl)
            }
            val store = ExtensionStore(
                indexUrl = indexUrl,
                name = "Store",
                badgeLabel = "ST",
                signingKey = "00",
                contact = ExtensionStore.Contact("https://store.test", null),
                isLegacy = url == 1,
                extensionListUrl = if (url == 2) "https://store.test/list.pb" else null,
            )
            val list = FakeHttp { _, _ -> input }
            outcomes.run(input, { false }) {
                ExtensionStoreService(list.network, json, ProtoBuf).getExtensions(store)
            }
        }
        outcomes.check()
    }

    @Test
    fun `the update manifest gives a release or a format error`() = runBlocking<Unit> {
        val valid = listOf(ValidDocuments.releases, ValidDocuments.release).map { it.toByteArray() }
        val outcomes = Outcomes("UpdateChecker.select/parseNotes")
        val formatError = { e: Throwable -> e is IllegalArgumentException }
        checkAll(PropertyRuns.config(ITERATIONS), arbFrom { r -> Mutations.input(r, valid, Format.JSON) }) { input ->
            val body = input.toString(Charsets.UTF_8)
            for (platform in Platform.entries) {
                outcomes.run(input, formatError) { UpdateChecker.select(body, Version(listOf(0, 1)), platform, includePrereleases = true) }
            }
            outcomes.run(input, formatError) { UpdateChecker.parseNotes(body, Version(listOf(9, 9, 9))) }
        }
        outcomes.check()
    }

    @Test
    fun `the update check and the release notes return their results for any answer`() = runBlocking<Unit> {
        val valid = listOf(ValidDocuments.releases, ValidDocuments.release).map { it.toByteArray() }
        val checks = Outcomes("UpdateChecker.check")
        val notes = Outcomes("UpdateChecker.releaseNotes")
        checkAll(PropertyRuns.config(ITERATIONS / 2), arbFrom { r -> Mutations.input(r, valid, Format.JSON) }) { input ->
            val checker = UpdateChecker(FakeHttp { _, _ -> input }.client, MemorySecretStore(), repo = "o/r", current = Version(listOf(0, 1)), platform = Platform.WINDOWS)
            // Both return their own result type for any answer: nothing is thrown
            checks.run(input, { false }) { checker.check() }
            notes.run(input, { false }) { checker.releaseNotes(Version(listOf(9, 9, 9))) }
        }
        checks.check()
        notes.check()
    }

    @Test
    fun `a backup file gives a backup or an invalid-backup error`() = runBlocking<Unit> {
        val backup = BackupCodec.encode(SyntheticBackup.create())
        val valid = listOf(backup, Mutations.gzip(backup))
        val outcomes = Outcomes("BackupCodec.decode")
        checkAll(PropertyRuns.config(ITERATIONS), arbFrom { r -> Mutations.input(r, valid, Format.BINARY) }) { input ->
            outcomes.run(input, { it is BackupCodec.InvalidBackupException }) { BackupCodec.decode(Buffer().write(input)) }
        }
        outcomes.check()
    }

    @Test
    fun `self-test - the inputs include the valid documents and broken ones`() {
        val r = Random(PropertyRuns.seed)
        val valid = listOf(ValidDocuments.comicInfo.toByteArray())
        val inputs = List(200) { Mutations.input(r, valid, Format.XML) }
        check(inputs.any { it.contentEquals(valid[0]) })
        check(inputs.count { runCatching { ComicInfoXml.read(it.inputStream()) }.isFailure } > 50)
    }

    companion object {
        private const val ITERATIONS = 500

        /** Generous: a parse here takes well under a millisecond; only a hang or a blow-up is this slow. */
        private const val MAX_MILLIS_PER_INPUT = 5_000L
    }
}
