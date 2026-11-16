package zoukan.extension

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class ExtensionTrustTest {
    @Test
    fun `the trusted list is app state, so backups leave it out`() {
        val key = slot<String>()
        val store = mockk<PreferenceStore> {
            every { getStringSet(capture(key), any()) } returns mockk(relaxed = true)
        }

        ExtensionTrust(store)

        Preference.isAppState(key.captured) shouldBe true
    }
}
