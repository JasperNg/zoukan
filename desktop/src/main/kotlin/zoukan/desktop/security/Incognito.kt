package zoukan.desktop.security

import eu.kanade.domain.source.service.SourcePreferences
import exh.source.EHENTAI_EXT_SOURCES
import exh.source.EH_PACKAGE
import exh.source.EH_SOURCE_ID
import exh.source.EXHENTAI_EXT_SOURCES
import exh.source.EXH_SOURCE_ID
import exh.source.ExhPreferences
import exh.source.LOCAL_SOURCE_PACKAGE
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import zoukan.desktop.local.LocalSource
import zoukan.extension.DesktopExtensionManager

/**
 * Incognito mode, as Android's GetIncognitoState: reading adds no history while it is on, for the
 * whole app or for the extensions picked under Browse (Komikku's per-extension incognito).
 *
 * The E-Hentai settings' "Incognito" also turns it on for the E-Hentai and ExHentai galleries; SY
 * keeps that setting but reads it nowhere.
 */
class Incognito(
    private val preferenceStore: PreferenceStore,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: DesktopExtensionManager,
    // ZOUKAN -->
    private val exhPreferences: ExhPreferences = ExhPreferences(preferenceStore),
    // ZOUKAN <--
) {
    /** BasePreferences.incognitoMode() on Android. */
    fun mode() = mode(preferenceStore)

    fun isOn(sourceId: Long?): Boolean {
        if (mode().get()) return true
        if (sourceId == null) return false
        val pkg = packageOf(sourceId) ?: return false
        // ZOUKAN -->
        if (pkg == EH_PACKAGE && exhPreferences.ehIncognitoMode().get()) return true
        // ZOUKAN <--
        return pkg in sourcePreferences.incognitoExtensions().get()
    }

    fun packageOf(sourceId: Long): String? = when {
        sourceId == LocalSource.ID -> LOCAL_SOURCE_PACKAGE
        sourceId == EH_SOURCE_ID || sourceId == EXH_SOURCE_ID || sourceId in EHENTAI_EXT_SOURCES || sourceId in EXHENTAI_EXT_SOURCES -> EH_PACKAGE
        else -> extensionManager.installedExtensionsFlow.value.values.firstOrNull { ext -> ext.sources.any { it.id == sourceId } }?.pkgName
    }

    fun setExtension(pkg: String, incognito: Boolean) {
        val pref = sourcePreferences.incognitoExtensions()
        pref.set(if (incognito) pref.get() + pkg else pref.get() - pkg)
    }

    companion object {
        /** BasePreferences.incognitoMode() on Android, without the extension manager [Incognito] needs. */
        fun mode(preferenceStore: PreferenceStore) = preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false)
    }
}
