package zoukan.desktop.ui.settings

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import exh.source.ExhPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Headers
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.runCatchingCancellable
import zoukan.desktop.browser.BrowserProfileCleanup
import zoukan.desktop.browser.JcefRuntime
import zoukan.desktop.browser.blocking.ContentBlocker
import zoukan.desktop.browser.blocking.ContentBlockerPreferences
import zoukan.desktop.browser.blocking.UserBlockLists
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.onboarding.Onboarding
import zoukan.extension.ExtensionTrust
import zoukan.i18n.desktop.DMR
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager

/**
 * Non-UI state/business controller for Advanced settings: cache/cookie/browser data clearing,
 * DoH and user-agent updates/validation, block lists, cover refresh, viewer flags reset, and
 * crash log export.
 */
class AdvancedSettingsController(
    private val networkPreferences: NetworkPreferences,
    private val contentBlockerPreferences: ContentBlockerPreferences,
    private val exhPreferences: ExhPreferences,
    private val dirs: ZoukanDirs,
    private val invalidateDownloadCacheAction: () -> Unit = { Injekt.get<DownloadManager>().invalidateCache() },
    private val clearAppCookiesAction: () -> Unit = ::clearAppCookies,
    private val clearBrowserCookiesAction: () -> Unit = ::deleteBrowserCookies,
    private val clearBrowserProfileAction: () -> Unit = {
        BrowserProfileCleanup.clearAll(
            dirs.browserCache,
            // A running Chromium holds files open (on Windows they cannot be deleted) and makes some
            // again at once (its lock files, Local State), so what is left then is no failure
            chromiumMayHoldFiles = JcefRuntime.startedInThisProcess,
        )
    },
    private val refreshBlockListsAction: () -> Unit = { ContentBlocker.refreshNow() },
    private val resetViewerFlagsAction: suspend () -> Boolean = { Injekt.get<ResetViewerFlags>().await() },
    private val revokeExtensionTrustAction: () -> Unit = { Injekt.get<ExtensionTrust>().revokeAll() },
    private val reopenOnboardingAction: () -> Unit = { Onboarding.reopen.value = true },
    private val getFavoritesAction: suspend () -> List<Manga> = { Injekt.get<GetFavorites>().await() },
    private val refreshMangaAction: suspend (Manga) -> Manga = { manga ->
        Injekt.get<RefreshManga>().invoke(manga, fetchDetails = true, fetchChapters = false).manga
    },
    private val forgetCoverAction: (Manga) -> Unit = { Images.forget(it) },
    private val clearMemoryCoversAction: () -> Unit = { Images.loader.memoryCache?.clear() },
    private val refreshingCovers: MutableStateFlow<String?> = sharedRefreshingCovers,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface UserAgentResult {
        data object Unchanged : UserAgentResult
        data object UpdatedRestartRequired : UserAgentResult
        data object Invalid : UserAgentResult
    }

    val refreshingCoversState: StateFlow<String?> get() = refreshingCovers

    fun reopenOnboarding() = reopenOnboardingAction()

    fun reindexDownloads() = invalidateDownloadCacheAction()

    fun clearCookies() {
        clearAppCookiesAction()
        clearBrowserCookiesAction()
    }

    suspend fun clearWebViewData(): Result<Unit> = runCatchingCancellable {
        withContext(ioDispatcher) {
            clearBrowserCookiesAction()
            clearBrowserProfileAction()
        }
    }

    fun setDohProvider(provider: Int): Boolean {
        val pref = networkPreferences.dohProvider()
        if (provider == pref.get()) return false
        pref.set(provider)
        return true
    }

    fun setUserAgent(value: String): UserAgentResult {
        return try {
            // OkHttp checks for valid values internally
            Headers.Builder().add("User-Agent", value)
            val pref = networkPreferences.defaultUserAgent()
            if (value == pref.get()) {
                UserAgentResult.Unchanged
            } else {
                pref.set(value)
                UserAgentResult.UpdatedRestartRequired
            }
        } catch (_: IllegalArgumentException) {
            UserAgentResult.Invalid
        }
    }

    fun resetUserAgent(): Boolean {
        val pref = networkPreferences.defaultUserAgent()
        if (!pref.isSet() && pref.get() == pref.defaultValue()) return false
        pref.delete()
        return true
    }

    fun updateBlockLists(raw: String): Boolean {
        // Only https addresses are kept, one per line
        val cleaned = UserBlockLists.parseUrls(raw).joinToString("\n")
        val pref = contentBlockerPreferences.listUrls()
        if (cleaned == pref.get()) return false
        pref.set(cleaned)
        refreshBlockListsAction()
        return true
    }

    suspend fun resetViewerFlags(): Boolean =
        runCatchingCancellable { resetViewerFlagsAction() }.getOrDefault(false)

    fun revokeExtensionTrust() = revokeExtensionTrustAction()

    fun setLogLevel(level: Int): Boolean {
        val pref = exhPreferences.logLevel(false)
        if (level == pref.get()) return false
        pref.set(level)
        return true
    }

    suspend fun saveCrashLogs(target: File): Result<Unit> = runCatchingCancellable {
        withContext(ioDispatcher) { target.writeText(crashLogs(dirs)) }
    }

    suspend fun refreshLibraryCovers(): Int? {
        if (refreshingCovers.value != null) return null
        refreshingCovers.value = localized(DMR.strings.refreshing_covers)
        return try {
            val favorites = getFavoritesAction()
            var failed = 0
            favorites.forEachIndexed { index, manga ->
                refreshingCovers.value = localized(DMR.strings.refreshing_covers_of, index + 1, favorites.size)
                runCatchingCancellable {
                    forgetCoverAction(manga)
                    val updated = refreshMangaAction(manga)
                    forgetCoverAction(updated)
                }.onFailure { failed++ }
            }
            clearMemoryCoversAction()
            failed
        } finally {
            refreshingCovers.value = null
        }
    }

    companion object {
        private val sharedRefreshingCovers = MutableStateFlow<String?>(null)

        internal fun clearAppCookies() {
            (CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
            runCatching { Injekt.get<NetworkHelper>().cookieJar.removeAll() }
        }

        internal fun deleteBrowserCookies() {
            // Now when Chromium is running, else when it starts: it keeps its cookies on disk
            xyz.nulldev.androidcompat.webkit.CefCookieSync.deleteFromCef("", "")
        }
    }
}
