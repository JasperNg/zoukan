package zoukan.desktop.update

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.Platform
import zoukan.desktop.app.Version
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.io.IOException

class UpdateCheckerTest {
    private val releases = """
        [
          {"tag_name": "desktop-v1.3.0", "name": "Draft", "draft": true, "prerelease": false, "html_url": "d", "assets": []},
          {"tag_name": "desktop-v1.2.0", "name": "Zoukan 1.2.0 beta", "draft": false, "prerelease": true, "html_url": "b", "assets": []},
          {"tag_name": "v1.15.0", "name": "Komikku for Android", "draft": false, "prerelease": false, "html_url": "a", "assets": []},
          {"tag_name": "desktop-v1.1.0", "name": "Zoukan 1.1.0", "body": "Fixes", "draft": false, "prerelease": false,
           "html_url": "https://github.com/o/r/releases/tag/desktop-v1.1.0",
           "assets": [
             {"name": "Zoukan-1.1.0-windows-x64.msi", "url": "https://api.github.com/repos/o/r/releases/assets/1", "size": 100,
              "digest": "sha256:abc123"},
             {"name": "Zoukan-1.1.0-macos-arm64.dmg", "url": "https://api.github.com/repos/o/r/releases/assets/2", "size": 200}
           ]},
          {"tag_name": "desktop-v1.0.1", "name": "", "draft": false, "prerelease": false, "html_url": "x", "assets": []}
        ]
    """.trimIndent()

    private val v100 = Version(listOf(1, 0, 0))

    @Test
    fun `picks the newest published desktop release`() {
        val release = UpdateChecker.select(releases, v100, Platform.WINDOWS)!!
        release.version shouldBe Version(listOf(1, 1, 0))
        release.name shouldBe "Zoukan 1.1.0"
        release.notes shouldBe "Fixes"
        release.asset!!.name shouldBe "Zoukan-1.1.0-windows-x64.msi"
        release.asset!!.apiUrl shouldBe "https://api.github.com/repos/o/r/releases/assets/1"
        release.asset!!.sha256 shouldBe "abc123"
    }

    @Test
    fun `picks the installer for the platform`() {
        UpdateChecker.select(releases, v100, Platform.MACOS)!!.asset!!.name shouldBe "Zoukan-1.1.0-macos-arm64.dmg"
        UpdateChecker.select(releases, v100, Platform.MACOS)!!.asset!!.sha256 shouldBe null
        UpdateChecker.select(releases, v100, Platform.LINUX)!!.asset shouldBe null
    }

    @Test
    fun `test releases only when asked`() {
        UpdateChecker.select(releases, v100, Platform.WINDOWS, includePrereleases = true)!!.version shouldBe Version(listOf(1, 2, 0))
    }

    @Test
    fun `nothing when up to date`() {
        UpdateChecker.select(releases, Version(listOf(1, 1, 0)), Platform.WINDOWS) shouldBe null
        UpdateChecker.select("[]", v100, Platform.WINDOWS) shouldBe null
    }

    @Test
    fun `an untitled release gets a name`() {
        UpdateChecker.select(releases, Version(listOf(1, 0, 0)), Platform.WINDOWS)
        val only = """[{"tag_name": "desktop-v1.0.1", "name": "", "html_url": "x", "assets": []}]"""
        UpdateChecker.select(only, v100, Platform.WINDOWS)!!.name shouldBe "Zoukan 1.0.1"
    }

    @Test
    fun `reads a release's notes for What's new`() {
        val body = """{"tag_name": "desktop-v1.1.0", "name": "", "body": "## Fixes\n- One", "html_url": "https://github.com/o/r/releases/tag/desktop-v1.1.0"}"""
        val notes = UpdateChecker.parseNotes(body, Version(listOf(1, 1, 0)))
        notes.name shouldBe "Zoukan 1.1.0"
        notes.body shouldBe "## Fixes\n- One"
        notes.pageUrl shouldBe "https://github.com/o/r/releases/tag/desktop-v1.1.0"
    }

    private fun answering(body: String) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

    /** Answers with status 200 that are not GitHub's JSON: an empty body, a captive portal's page, a release without its tag. */
    private val notReleases = listOf("", "<html>Sign in to the Wi-Fi</html>", """{"name": "no tag"}""", """[{"name": 1}]""", "{")

    @Test
    fun `release notes from an answer that is not a release are a failed result, not an exception`(): Unit = runBlocking {
        for (body in notReleases) {
            val checker = UpdateChecker(answering(body), MemorySecrets(), repo = "o/r", platform = Platform.WINDOWS)
            checker.releaseNotes(Version(listOf(9, 9, 9))).exceptionOrNull().shouldBeInstanceOf<IOException>()
        }
    }

    @Test
    fun `an update check whose answer is not a release list is a failed result, not an exception`(): Unit = runBlocking {
        for (body in notReleases) {
            val checker = UpdateChecker(answering(body), MemorySecrets(), repo = "o/r", platform = Platform.WINDOWS)
            checker.check().shouldBeInstanceOf<UpdateChecker.Result.Failed>()
        }
    }

    @Test
    fun `a cancelled request stays cancelled`(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw CancellationException("cancelled") }.build()
        val checker = UpdateChecker(client, MemorySecrets(), repo = "o/r", platform = Platform.WINDOWS)
        shouldThrow<CancellationException> { checker.releaseNotes(Version(listOf(9, 9, 9))) }
        shouldThrow<CancellationException> { checker.check() }
    }

    private class MemorySecrets(private val map: MutableMap<String, String> = mutableMapOf()) : SecretStore {
        override val name = "memory"
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun delete(key: String) {
            map.remove(key)
        }
    }

    private fun recordingClient(seen: MutableList<Request>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("x".toResponseBody()).build()
        }.build()

    @Test
    fun `an asset address that is not https on the GitHub API gets no token`(@TempDir dir: File): Unit = runBlocking {
        val seen = mutableListOf<Request>()
        val checker = UpdateChecker(
            recordingClient(seen),
            MemorySecrets(mutableMapOf(UpdateChecker.TOKEN_KEY to "secret-token")),
            repo = "o/r",
        )
        for (url in listOf("http://api.github.com/repos/o/r/releases/assets/1", "https://evil.example/asset")) {
            runCatching { checker.download(UpdateChecker.Asset("Zoukan.msi", url, 1, null), dir) }
        }
        seen.forEach { it.header("Authorization") shouldBe null }
    }

    @Test
    fun `an asset address on the GitHub API over https is downloaded with the token`(@TempDir dir: File): Unit = runBlocking {
        val seen = mutableListOf<Request>()
        val checker = UpdateChecker(
            recordingClient(seen),
            MemorySecrets(mutableMapOf(UpdateChecker.TOKEN_KEY to "secret-token")),
            repo = "o/r",
        )
        checker.download(UpdateChecker.Asset("Zoukan.msi", "https://api.github.com/repos/o/r/releases/assets/1", 1, null), dir)
        seen.map { it.header("Authorization") } shouldBe listOf("Bearer secret-token")
    }
}
