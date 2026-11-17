package zoukan.desktop.update

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.Platform
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.Version
import zoukan.desktop.e2e.FixtureReleases
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.io.IOException

/**
 * An update's download and checks offline (Updates.downloadVerified): the release list, the
 * installer and its `.sig` come from the fixture server, at GitHub API addresses, and the release
 * signature is checked with a test key, as a packaged build checks it with the built-in one.
 */
class UpdatesDownloadTest {
    @TempDir
    lateinit var dir: File

    private val server = FixtureServer()
    private val releases = FixtureReleases(server, REPO)
    private val key = TestReleaseKey()
    private val installer = ByteArray(300_000) { (it % 241).toByte() }

    @AfterEach
    fun close() {
        releases.close()
        server.close()
    }

    private object NoSecrets : SecretStore {
        override val name = "none"
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) = Unit
        override fun delete(key: String) = Unit
    }

    private val checker by lazy {
        UpdateChecker(releases.client(OkHttpClient()), NoSecrets, repo = REPO, current = Version.parse("1.0.0")!!, platform = Platform.WINDOWS)
    }

    /** Publishes a 2.0.0 release with [served] as the installer and [signature] as its `.sig`, and checks for it. */
    private fun offered(served: FixtureReleases.Asset, signature: ByteArray?): UpdateChecker.Asset {
        val assets = listOfNotNull(served, signature?.let { FixtureReleases.Asset(NAME + ".sig", it, sha256 = null) })
        releases.publish(releases.release(AppVersion.TAG_PREFIX + "2.0.0", "Zoukan 2.0.0", "Notes", assets = assets))
        val result = runBlocking { checker.check() }
        return result.shouldBeInstanceOf<UpdateChecker.Result.Available>().release.asset!!
    }

    private fun download(asset: UpdateChecker.Asset): File = runBlocking {
        Updates.downloadVerified(checker, asset, dir, key.verifier, InstallerSignature(packaged = false))
    }

    @Test
    fun `an installer signed with the release key is downloaded from the API addresses and kept`() {
        val asset = offered(FixtureReleases.Asset(NAME, installer), key.sign(NAME, installer))
        asset.apiUrl.startsWith(FixtureReleases.API) shouldBe true
        val file = download(asset)
        file.readBytes().contentEquals(installer) shouldBe true
        File(dir, "$NAME.sig").isFile shouldBe true
        server.requests.count { it.startsWith(releases.releasesPath + "/assets/") } shouldBe 2
    }

    @Test
    fun `an installer changed after it was signed is rejected and deleted, though GitHub's checksum matches it`() {
        val tampered = installer.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        // The release JSON lists the tampered file's own SHA-256: only the release signature can tell
        val asset = offered(FixtureReleases.Asset(NAME, tampered), key.sign(NAME, installer))
        shouldThrow<IOException> { download(asset) }.message shouldContain "does not match Zoukan's release key"
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `an installer signed with another key is rejected`() {
        val asset = offered(FixtureReleases.Asset(NAME, installer), TestReleaseKey().sign(NAME, installer))
        shouldThrow<IOException> { download(asset) }.message shouldContain "does not match Zoukan's release key"
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `a release without a signature is rejected`() {
        val asset = offered(FixtureReleases.Asset(NAME, installer), signature = null)
        shouldThrow<IOException> { download(asset) }.message shouldContain "no valid release signature"
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `a download that does not match GitHub's checksum is refused before its signature is fetched`() {
        val asset = offered(FixtureReleases.Asset(NAME, installer, sha256 = TestReleaseKey.sha256(byteArrayOf(1))), key.sign(NAME, installer))
        shouldThrow<IOException> { download(asset) }.message shouldContain "SHA-256 does not match"
        server.requests.count { it.startsWith(releases.releasesPath + "/assets/") } shouldBe 1
        File(dir, NAME).exists() shouldBe false
    }

    private companion object {
        const val REPO = "zoukan-test/zoukan"
        const val NAME = "Zoukan-2.0.0.msi"
    }
}
