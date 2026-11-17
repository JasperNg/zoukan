package zoukan.desktop.security

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import exh.source.EH_SOURCE_ID
import exh.source.EXH_SOURCE_ID
import exh.source.ExhPreferences
import exh.source.LOCAL_SOURCE_PACKAGE
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import zoukan.desktop.local.LocalSource
import zoukan.extension.DesktopExtensionManager

/**
 * Which sources read in incognito: the E-Hentai settings' "Incognito" for the E-Hentai and ExHentai
 * galleries, and the extensions picked in Settings > Security, by package.
 */
class IncognitoTest {
    private fun <T> pref(value: T): Preference<T> = mockk { every { get() } returns value }

    private fun incognito(
        ehIncognito: Boolean = false,
        incognitoExtensions: Set<String> = emptySet(),
        installed: Map<String, Extension.Installed> = emptyMap(),
    ) = Incognito(
        preferenceStore = mockk<PreferenceStore> { every { getBoolean(any(), any()) } returns pref(false) },
        sourcePreferences = mockk<SourcePreferences> { every { incognitoExtensions() } returns pref(incognitoExtensions) },
        extensionManager = mockk<DesktopExtensionManager> { every { installedExtensionsFlow } returns MutableStateFlow(installed) },
        exhPreferences = mockk<ExhPreferences> { every { ehIncognitoMode() } returns pref(ehIncognito) },
    )

    private fun extension(pkg: String, vararg sourceIds: Long): Extension.Installed = mockk {
        every { pkgName } returns pkg
        every { sources } returns sourceIds.map { sourceId -> mockk<Source> { every { id } returns sourceId } }
    }

    @Test
    fun `an extension picked under incognito extensions keeps no history for any of its sources`() {
        val installed = mapOf(
            "eu.kanade.tachiyomi.extension.en.picked" to extension("eu.kanade.tachiyomi.extension.en.picked", 11L, 12L),
            "eu.kanade.tachiyomi.extension.en.other" to extension("eu.kanade.tachiyomi.extension.en.other", 21L),
        )
        val incognito = incognito(incognitoExtensions = setOf("eu.kanade.tachiyomi.extension.en.picked"), installed = installed)
        incognito.isOn(11L) shouldBe true
        incognito.isOn(12L) shouldBe true
        incognito.isOn(21L) shouldBe false
        // A source no installed extension has (a stub) is not incognito
        incognito.isOn(99L) shouldBe false
    }

    @Test
    fun `the Local source is picked by its own package`() {
        incognito(incognitoExtensions = setOf(LOCAL_SOURCE_PACKAGE)).isOn(LocalSource.ID) shouldBe true
        incognito().isOn(LocalSource.ID) shouldBe false
    }

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
