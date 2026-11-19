package zoukan.desktop.extension

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.service.ExtensionStoreService
import mihon.data.extension.service.InsecureExtensionStoreException
import mihon.data.extension.service.requireSecureStoreUrl
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.api.Test
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.backup.NoStubSources
import zoukan.desktop.property.FakeHttp
import zoukan.desktop.property.ValidDocuments
import zoukan.extension.DesktopExtensionManager
import java.io.File

class ExtensionStoreSafetyTest {
    private fun store(name: String, key: String) = ExtensionStore(
        indexUrl = "https://$name.example/index.pb",
        name = name,
        badgeLabel = name,
        signingKey = key,
        contact = ExtensionStore.Contact("https://$name.example", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun offer(store: ExtensionStore, versionCode: Long) = Extension.Available(
        name = "MangaDex",
        pkgName = "eu.kanade.tachiyomi.extension.all.mangadex",
        versionName = "1.4.$versionCode",
        versionCode = versionCode,
        libVersion = 1.4,
        lang = "all",
        isNsfw = false,
        signatureHash = store.signingKey,
        storeName = store.name,
        sources = emptyList(),
        apkUrl = "${store.indexUrl}/mangadex.apk",
        iconUrl = "",
        store = store,
    )

    private val keiyoushi = store("keiyoushi", "aaaa")
    private val other = store("other", "bbbb")
    private val legacy = store("legacy", DesktopExtensionManager.NO_SIGNING_KEY)

    @Test
    fun `another store cannot offer an update for an installed extension`() {
        val real = offer(keiyoushi, 200)
        val hostile = offer(other, 999)
        // Whatever the order of the stores
        DesktopExtensionManager.chooseOffer(listOf(real, hostile), installedSigner = "aaaa") shouldBe real
        DesktopExtensionManager.chooseOffer(listOf(hostile, real), installedSigner = "aaaa") shouldBe real
        DesktopExtensionManager.chooseOffer(listOf(hostile), installedSigner = "aaaa") shouldBe null
    }

    @Test
    fun `a store without a signing key may offer it, and the file is checked at install`() {
        val fromLegacy = offer(legacy, 300)
        DesktopExtensionManager.chooseOffer(listOf(offer(other, 999), fromLegacy), installedSigner = "aaaa") shouldBe fromLegacy
    }

    @Test
    fun `not installed, the first store that was added counts`() {
        val first = offer(keiyoushi, 200)
        DesktopExtensionManager.chooseOffer(listOf(first, offer(other, 999)), installedSigner = null) shouldBe first
        DesktopExtensionManager.chooseOffer(emptyList(), installedSigner = null) shouldBe null
    }

    @Test
    fun `stores and extension downloads must use https`() {
        val https = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json"
        requireSecureStoreUrl(https) shouldBe https
        requireSecureStoreUrl("http://localhost:8080/index.min.json") shouldBe "http://localhost:8080/index.min.json"
        requireSecureStoreUrl("http://127.0.0.1/repo.json") shouldBe "http://127.0.0.1/repo.json"
        listOf(
            "http://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json",
            "http://localhost.evil.example/repo.json",
            "ftp://example.org/repo.json",
            "file:///tmp/repo.json",
            "repo.json",
        ).forEach { shouldThrow<IllegalArgumentException> { requireSecureStoreUrl(it) } }
    }

    @Test
    fun `a restore that changes only a known store's list address needs confirmation`() {
        val known = ExtensionStore(
            indexUrl = "https://example.org/repo.json",
            name = "Example",
            badgeLabel = "Example",
            signingKey = "a".repeat(64),
            contact = ExtensionStore.Contact("https://example.org", null),
            isLegacy = false,
            extensionListUrl = "https://example.org/index.pb",
        )
        val repository = mockk<ExtensionStoreRepository> { coEvery { getAll() } returns listOf(known) }
        fun backupStore(
            list: String? = "https://example.org/index.pb",
            website: String = "https://example.org",
            discord: String? = null,
        ) = BackupExtensionStore(
            "https://example.org/repo.json",
            "Example",
            null,
            "a".repeat(64),
            website,
            discord,
            false,
            list,
        )
        val same = backupStore()
        val newList = backupStore(list = "https://evil.example/index.pb")
        val newWebsite = backupStore(website = "https://phish.example")
        val newDiscord = backupStore(discord = "https://discord.gg/phish")
        val backup = Backup(backupManga = emptyList(), backupExtensionStores = listOf(same, newList, newWebsite, newDiscord))
        val backups = DesktopBackups(File("unused"), NoStubSources)
        runBlocking {
            backups.newExtensionStores(backup, GetExtensionStores(repository)) shouldBe listOf(newList, newWebsite, newDiscord)
            val (restorable, skipped) = backups.withoutNewExtensionStores(backup, GetExtensionStores(repository))
            restorable.backupExtensionStores shouldBe listOf(same)
            skipped shouldBe listOf(newList, newWebsite, newDiscord)
        }
    }

    @Test
    fun `an old backup of a legacy store with the same details needs no confirmation`() {
        val known = ExtensionStore(
            indexUrl = "https://example.org/repo.json",
            name = "Example",
            badgeLabel = "Example",
            signingKey = "a".repeat(64),
            contact = ExtensionStore.Contact("https://example.org", null),
            isLegacy = true,
            extensionListUrl = null,
        )
        val repository = mockk<ExtensionStoreRepository> { coEvery { getAll() } returns listOf(known) }
        val old = BackupExtensionStore(
            "https://example.org/index.min.json",
            "Example",
            null,
            "a".repeat(64),
            "https://example.org",
            null,
            null,
            null,
        )
        val backup = Backup(backupManga = emptyList(), backupExtensionStores = listOf(old))
        runBlocking {
            DesktopBackups(File("unused"), NoStubSources)
                .newExtensionStores(backup, GetExtensionStores(repository)) shouldBe emptyList()
        }
    }
}

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
        val store = result.getOrThrow().shouldBeInstanceOf<ExtensionStore>()
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
