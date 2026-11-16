package zoukan.desktop.harness

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WiringGapKindTest {

    private fun kind(symbol: String, reason: String) = ExtensionWiringCheck.kindOf(ApiLinkage.Gap(symbol, reason))

    @Test
    fun `a bundled copy of a library the app also has is known`() {
        kind("nl.adaptivity.xmlutil.XmlReader", ApiLinkage.SHADOWED) shouldBe ExtensionWiringCheck.GapKind.KNOWN
        kind("org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface", ApiLinkage.SHADOWED) shouldBe
            ExtensionWiringCheck.GapKind.KNOWN
    }

    @Test
    fun `a member the app's copy of a bundled library lacks is still a missing API`() {
        // The app's older copy runs, so a call to a newer method fails with NoSuchMethodError
        kind("nl.adaptivity.xmlutil.XmlReader.newMethod()V", "missing method") shouldBe ExtensionWiringCheck.GapKind.APP
        kind("org.chromium.support_lib_boundary.NewInterface", "missing class") shouldBe ExtensionWiringCheck.GapKind.APP
    }

    @Test
    fun `an Android-only library is known whatever the gap`() {
        kind("me.zhanghai.android.libarchive.Archive.readNew()J", "missing method") shouldBe ExtensionWiringCheck.GapKind.KNOWN
    }
}
