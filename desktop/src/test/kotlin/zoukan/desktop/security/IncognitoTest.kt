package zoukan.desktop.security

import eu.kanade.domain.source.service.SourcePreferences
import exh.source.EH_SOURCE_ID
import exh.source.EXH_SOURCE_ID
import exh.source.ExhPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import zoukan.extension.DesktopExtensionManager

/** The E-Hentai settings' "Incognito" keeps no history for the E-Hentai and ExHentai galleries. */
class IncognitoTest {
    private fun <T> pref(value: T): Preference<T> = mockk { every { get() } returns value }

    private fun incognito(ehIncognito: Boolean) = Incognito(
        preferenceStore = mockk<PreferenceStore> { every { getBoolean(any(), any()) } returns pref(false) },
        sourcePreferences = mockk<SourcePreferences> { every { incognitoExtensions() } returns pref(emptySet()) },
        extensionManager = mockk<DesktopExtensionManager> { every { installedExtensionsFlow } returns MutableStateFlow(emptyMap()) },
        exhPreferences = mockk<ExhPreferences> { every { ehIncognitoMode() } returns pref(ehIncognito) },
    )

    @Test
    fun `on, E-Hentai and ExHentai galleries are incognito`() {
        val incognito = incognito(ehIncognito = true)
        incognito.isOn(EH_SOURCE_ID) shouldBe true
        incognito.isOn(EXH_SOURCE_ID) shouldBe true
    }

    @Test
    fun `on, other sources are not`() {
        incognito(ehIncognito = true).isOn(12345L) shouldBe false
        incognito(ehIncognito = true).isOn(null) shouldBe false
    }

    @Test
    fun `off, E-Hentai galleries keep their history`() {
        incognito(ehIncognito = false).isOn(EH_SOURCE_ID) shouldBe false
    }
}
