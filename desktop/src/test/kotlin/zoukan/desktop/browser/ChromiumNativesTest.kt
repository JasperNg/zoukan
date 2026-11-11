package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import me.friwi.jcefmaven.CefBuildInfo
import org.junit.jupiter.api.Test

class ChromiumNativesTest {
    @Test
    fun `the JCEF build on the class path has a pinned checksum for every supported platform`() {
        val tag = CefBuildInfo.fromClasspath().releaseTag
        listOf("windows-amd64", "macosx-arm64", "linux-amd64").forEach { platform ->
            val sha = ChromiumNatives.pinnedSha256(platform, tag)
            sha shouldNotBe null
            sha!!.matches(Regex("[0-9a-f]{64}")) shouldBe true
        }
    }

    @Test
    fun `another build or platform has none, so nothing is downloaded for it`() {
        ChromiumNatives.pinnedSha256("windows-amd64", "jcef-0000000+cef-1.0.0") shouldBe null
        ChromiumNatives.pinnedSha256("solaris-sparc", CefBuildInfo.fromClasspath().releaseTag) shouldBe null
    }
}
