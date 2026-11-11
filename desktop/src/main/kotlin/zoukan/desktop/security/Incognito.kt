package zoukan.desktop.security

import eu.kanade.domain.source.service.SourcePreferences
import exh.source.EHENTAI_EXT_SOURCES
import exh.source.EH_PACKAGE
import exh.source.EH_SOURCE_ID
import exh.source.EXHENTAI_EXT_SOURCES
import exh.source.EXH_SOURCE_ID
import exh.source.LOCAL_SOURCE_PACKAGE
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import zoukan.desktop.local.LocalSource
import zoukan.extension.DesktopExtensionManager

/**
 * Incognito mode, as Android's GetIncognitoState: reading adds no history while it is on, for the
 * whole app or for the extensions picked under Browse (Komikku's per-extension incognito).
 */
class Incognito(
    private val preferenceStore: PreferenceStore,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: DesktopExtensionManager,
) {
    /** BasePreferences.incognitoMode() on Android. */
    fun mode() = preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false)

    fun isOn(sourceId: Long?): Boolean {
        if (mode().get()) return true
        if (sourceId == null) return false
        val pkg = packageOf(sourceId) ?: return false
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
}
