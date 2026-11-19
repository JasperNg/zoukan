package zoukan.desktop.ui.settings

import exh.favorites.FavoritesSyncStatus
import exh.source.ExhPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.app.runCatchingCancellable

/**
 * Non-UI state/business controller for E-Hentai settings: browser/cookie sign-in, site config
 * upload, gallery update triggers, favorites sync triggers/reset, and Android format preferences.
 */
class EhSettingsController(
    private val preferences: ExhPreferences,
    private val loginAction: suspend () -> Boolean,
    private val cookieLoginAction: suspend (memberId: String, passHash: String, igneous: String) -> Boolean,
    private val logoutAction: () -> Unit,
    private val configureSiteAction: suspend () -> Unit,
    private val runGalleryUpdateAction: suspend () -> Unit,
    private val runFavoritesSyncAction: () -> Unit,
    private val resetFavoritesSyncAction: suspend () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface CookieLoginResult {
        data object Success : CookieLoginResult
        data object Rejected : CookieLoginResult
        data class Failed(val error: Throwable) : CookieLoginResult
    }

    suspend fun signIn(): Result<Boolean> = runCatchingCancellable { loginAction() }

    suspend fun signInWithCookies(
        memberId: String,
        passHash: String,
        igneous: String,
    ): CookieLoginResult = runCatchingCancellable {
        cookieLoginAction(memberId, passHash, igneous)
    }.fold(
        onSuccess = { ok -> if (ok) CookieLoginResult.Success else CookieLoginResult.Rejected },
        onFailure = {
            logcat(LogPriority.WARN, it) { "Signing in failed" }
            CookieLoginResult.Failed(it)
        },
    )

    fun logout() = logoutAction()

    suspend fun applySiteSettings(): Result<Unit> = runCatchingCancellable {
        withContext(ioDispatcher) { configureSiteAction() }
    }

    suspend fun updateGalleriesNow(): Result<Unit> = runCatchingCancellable {
        runGalleryUpdateAction()
    }

    fun canStartFavoritesSync(status: FavoritesSyncStatus): Boolean =
        status is FavoritesSyncStatus.Idle ||
            status is FavoritesSyncStatus.SyncError ||
            status is FavoritesSyncStatus.CompleteWithErrors ||
            status is FavoritesSyncStatus.BadLibraryState

    fun startFavoritesSync(status: FavoritesSyncStatus): Boolean {
        if (!canStartFavoritesSync(status)) return false
        runFavoritesSyncAction()
        return true
    }

    suspend fun resetFavoritesSyncState(): Result<Unit> = runCatchingCancellable {
        withContext(ioDispatcher) { resetFavoritesSyncAction() }
    }

    fun loadLanguages(): List<List<Boolean>> =
        EhSettingsFormat.parseLanguages(preferences.exhSettingsLanguages().get())

    fun saveLanguages(rows: List<List<Boolean>>) {
        preferences.exhSettingsLanguages().set(EhSettingsFormat.formatLanguages(rows))
    }

    fun loadShownCategories(): List<Boolean> =
        EhSettingsFormat.parseShownCategories(preferences.exhEnabledCategories().get())

    fun saveShownCategories(shown: List<Boolean>) {
        preferences.exhEnabledCategories().set(EhSettingsFormat.formatShownCategories(shown))
    }
}
