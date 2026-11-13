package zoukan.desktop.ui.settings

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import zoukan.extension.ExtensionException
import zoukan.extension.LoadResult

class MissingExtensionsTest {

    private fun store(name: String, key: String = "key-$name") = ExtensionStore(
        indexUrl = "https://$name.example/index.min.json",
        name = name,
        badgeLabel = name,
        signingKey = key,
        contact = ExtensionStore.Contact(website = "https://$name.example", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun available(
        pkg: String,
        vararg sourceIds: Long,
        store: ExtensionStore = store("A"),
        versionCode: Long = 1,
        nsfw: Boolean = false,
    ) = Extension.Available(
        name = pkg.substringAfterLast('.'),
        pkgName = pkg,
        versionName = "1.4.$versionCode",
        versionCode = versionCode,
        libVersion = 1.4,
        lang = "en",
        isNsfw = nsfw,
        signatureHash = store.signingKey,
        storeName = store.name,
        sources = sourceIds.map { Extension.Available.Source(it, "en", "Source $it", "https://s$it.example") },
        apkUrl = "https://example/$pkg.apk",
        iconUrl = "https://example/$pkg.png",
        store = store,
    )

    private fun untrusted(pkg: String, key: String) = Extension.Untrusted(
        name = pkg.substringAfterLast('.'),
        pkgName = pkg,
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        signatureHash = key,
    )

    private fun missing(vararg ids: Long) = ids.map { MissingSource(it, "Source $it") }

    @Test
    fun `several missing sources of one extension install it once`() {
        val ext = available("ext.multi", 1, 2, 3)
        val plan = MissingExtensionsPlan.of(missing(1, 2), listOf(ext), emptyMap(), emptyMap())

        plan.toInstall.size shouldBe 1
        plan.toInstall.single().extension shouldBe ext
        plan.toInstall.single().sources.map { it.id } shouldBe listOf(1L, 2L)
        plan.needsTrust.shouldBeEmpty()
        plan.unavailable.shouldBeEmpty()
    }

    @Test
    fun `a source no store offers is listed as unavailable`() {
        val plan = MissingExtensionsPlan.of(missing(1, 99), listOf(available("ext.one", 1)), emptyMap(), emptyMap())

        plan.toInstall.map { it.extension.pkgName } shouldBe listOf("ext.one")
        plan.unavailable shouldBe listOf(MissingSource(99, "Source 99"))
    }

    @Test
    fun `an installed but untrusted extension is left for the user to trust`() {
        val ext = available("ext.unknown", 5, store = store("A", key = "k"))
        val plan = MissingExtensionsPlan.of(
            missing(5),
            listOf(ext),
            installed = emptyMap(),
            untrusted = mapOf("ext.unknown" to untrusted("ext.unknown", "k")),
        )

        plan.toInstall.shouldBeEmpty()
        plan.needsTrust.map { it.extension.pkgName } shouldBe listOf("ext.unknown")
        plan.unavailable.shouldBeEmpty()
    }

    @Test
    fun `an extension in two stores comes from the store the Browse screen lists`() {
        val first = available("ext.both", 7, store = store("First"))
        val second = available("ext.both", 7, store = store("Second"), versionCode = 2)
        val plan = MissingExtensionsPlan.of(missing(7), listOf(first, second), emptyMap(), emptyMap())

        // Not installed: the first store's, in the order the stores were added (chooseOffer)
        plan.toInstall.single().extension.store.name shouldBe "First"
    }

    @Test
    fun `an untrusted extension in two stores is matched to its signer's store`() {
        val other = available("ext.both", 7, store = store("Other", key = "x"))
        val own = available("ext.both", 7, store = store("Own", key = "k"))
        val plan = MissingExtensionsPlan.of(
            missing(7),
            listOf(other, own),
            installed = emptyMap(),
            untrusted = mapOf("ext.both" to untrusted("ext.both", "k")),
        )

        plan.needsTrust.single().extension.store.name shouldBe "Own"
    }

    @Test
    fun `one failure does not stop the other installs`(): Unit = runBlocking {
        val plan = MissingExtensionsPlan.of(
            missing(1, 2, 3, 4),
            listOf(available("ext.a", 1), available("ext.b", 2), available("ext.c", 3)),
            emptyMap(),
            emptyMap(),
        )
        val progress = mutableListOf<Int>()
        val summary = installMissingExtensions(
            plan,
            install = { ext ->
                when (ext.pkgName) {
                    "ext.a" -> throw IllegalStateException("network down")
                    "ext.b" -> LoadResult.Untrusted(untrusted("ext.b", "k"))
                    else -> LoadResult.Error(ext.pkgName, ExtensionException(ExtensionException.Stage.SIGNATURE, "bad signature"))
                }
            },
            onProgress = { done, _, _ -> progress += done },
        )

        summary.installed.shouldBeEmpty()
        summary.needsTrust shouldBe listOf("b")
        summary.failed shouldBe listOf("a" to "network down", "c" to "bad signature")
        summary.unavailable shouldBe listOf("Source 4")
        progress shouldBe listOf(0, 1, 2, 3)
    }

    @Test
    fun `cancellation stops the run`() {
        val plan = MissingExtensionsPlan.of(missing(1, 2), listOf(available("ext.a", 1), available("ext.b", 2)), emptyMap(), emptyMap())
        val tried = mutableListOf<String>()
        shouldThrow<CancellationException> {
            runBlocking {
                installMissingExtensions(plan, install = {
                    tried += it.pkgName
                    throw CancellationException("left")
                })
            }
        }
        tried shouldBe listOf("ext.a")
    }
}
