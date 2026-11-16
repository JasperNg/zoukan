package zoukan.extension

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class PreferenceConvertedJarHashesTest {
    private val values = mutableMapOf<String, String>()

    private val store = mockk<PreferenceStore> {
        every { getString(any(), any()) } answers {
            val key = firstArg<String>()
            mockk<Preference<String>> {
                every { get() } answers { values[key].orEmpty() }
                every { set(any()) } answers { values[key] = firstArg() }
                every { delete() } answers { values.remove(key) }
            }
        }
    }

    @Test
    fun `a recorded jar matches only the APK, version and jar it was recorded for`() {
        val hashes = PreferenceConvertedJarHashes(store)
        hashes.record("zoukan.extension.test.sample", 3, "apk-a", "jar-a")

        hashes.matches("zoukan.extension.test.sample", 3, "apk-a", "jar-a") shouldBe true
        hashes.matches("zoukan.extension.test.sample", 3, "apk-b", "jar-a") shouldBe false
        hashes.matches("zoukan.extension.test.sample", 3, "apk-a", "jar-b") shouldBe false
        hashes.matches("zoukan.extension.test.sample", 4, "apk-a", "jar-a") shouldBe false
    }

    @Test
    fun `a forgotten package matches nothing`() {
        val hashes = PreferenceConvertedJarHashes(store)
        hashes.record("zoukan.extension.test.sample", 3, "apk-a", "jar-a")

        hashes.forget("zoukan.extension.test.sample")

        hashes.matches("zoukan.extension.test.sample", 3, "apk-a", "jar-a") shouldBe false
    }
}
