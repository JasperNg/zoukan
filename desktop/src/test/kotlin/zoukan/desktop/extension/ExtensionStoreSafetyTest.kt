package zoukan.desktop.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import mihon.data.extension.service.requireSecureStoreUrl
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import zoukan.extension.DesktopExtensionManager

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
}
