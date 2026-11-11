package zoukan.desktop.app

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VersionTest {
    @Test
    fun `parses and compares dotted versions`() {
        Version.parse("1.2.3") shouldBe Version(listOf(1, 2, 3))
        Version.parse("v1.10") shouldBe Version(listOf(1, 10))
        Version.parse("1.2.3-beta") shouldBe null
        Version.parse("desktop-v1.0.0") shouldBe null
        Version.parse("1.10.0")!! shouldBeGreaterThan Version.parse("1.9.9")!!
        Version.parse("2.0")!! shouldBeGreaterThan Version.parse("1.99.99")!!
        Version.parse("1.0")!!.compareTo(Version.parse("1.0.0")!!) shouldBe 0
    }

    @Test
    fun `the build writes the version`() {
        AppVersion.name shouldBe AppVersion.version.toString()
        AppVersion.version shouldBeGreaterThan Version(listOf(0, 9))
    }
}
