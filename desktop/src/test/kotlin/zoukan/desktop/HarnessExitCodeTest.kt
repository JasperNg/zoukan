package zoukan.desktop

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.harness.ApiLinkage
import zoukan.desktop.harness.CompatibilityHarness
import zoukan.desktop.harness.ExtensionWiringCheck

/** The harness and the wiring check exit 1 when the app or an install is at fault, so scripts see a regression. */
class HarnessExitCodeTest {

    @Test
    fun `a wiring run with an app gap exits 1`() {
        val row = ExtensionWiringCheck.Row(pkg = "en.a", version = "1", load = "ok")
        row.linkage += ApiLinkage.Gap("android.zoukan.Missing.call()V", "no such method")
        wiringExitCode(listOf(row)) shouldBe 1
    }

    @Test
    fun `a wiring run where an extension failed to load exits 1`() {
        val row = ExtensionWiringCheck.Row(pkg = "en.a", version = "1", load = "install: IOException")
        wiringExitCode(listOf(row)) shouldBe 1
    }

    @Test
    fun `a clean wiring run exits 0`() {
        val row = ExtensionWiringCheck.Row(pkg = "en.a", version = "1", load = "ok")
        row.linkage += ApiLinkage.Gap("android.zoukan.Missing.call()V", "only the deep-link activity", activityOnly = true)
        wiringExitCode(listOf(row)) shouldBe 0
    }

    @Test
    fun `a harness run where an install failed exits 1`() {
        val row = CompatibilityHarness.Row(pkg = "en.a", install = "fail", error = "signature: bad")
        harnessExitCode(listOf(row)) shouldBe 1
    }

    @Test
    fun `a harness run where every extension installed exits 0`() {
        val row = CompatibilityHarness.Row(pkg = "en.a", install = "ok (jar)", popular = "fail: HTTP 503")
        harnessExitCode(listOf(row)) shouldBe 0
    }
}
