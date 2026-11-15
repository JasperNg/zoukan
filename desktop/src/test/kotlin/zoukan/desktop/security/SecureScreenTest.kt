package zoukan.desktop.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences.SecureScreenMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Settings > Security and privacy > Secure screen: when the windows are kept out of captures. */
class SecureScreenTest {
    @Test
    fun `Always secures with or without incognito`() {
        SecureScreen.isSecure(SecureScreenMode.ALWAYS, incognito = false) shouldBe true
        SecureScreen.isSecure(SecureScreenMode.ALWAYS, incognito = true) shouldBe true
    }

    @Test
    fun `Incognito secures only while incognito mode is on`() {
        SecureScreen.isSecure(SecureScreenMode.INCOGNITO, incognito = false) shouldBe false
        SecureScreen.isSecure(SecureScreenMode.INCOGNITO, incognito = true) shouldBe true
    }

    @Test
    fun `Never never secures`() {
        SecureScreen.isSecure(SecureScreenMode.NEVER, incognito = false) shouldBe false
        SecureScreen.isSecure(SecureScreenMode.NEVER, incognito = true) shouldBe false
    }

    @Test
    fun `Windows tries exclusion from capture, then the black-out of older Windows, and clears to none`() {
        SecureScreen.windowsAffinities(secure = true) shouldBe
            listOf(SecureScreen.Affinity.EXCLUDE_FROM_CAPTURE, SecureScreen.Affinity.MONITOR)
        SecureScreen.windowsAffinities(secure = false) shouldBe listOf(SecureScreen.Affinity.NONE)
        SecureScreen.Affinity.EXCLUDE_FROM_CAPTURE shouldBe 0x11
        SecureScreen.Affinity.MONITOR shouldBe 0x1
    }
}
