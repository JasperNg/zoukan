package zoukan.desktop.update

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.Platform
import zoukan.desktop.app.Version

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
}
