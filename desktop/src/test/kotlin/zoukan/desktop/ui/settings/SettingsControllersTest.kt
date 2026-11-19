package zoukan.desktop.ui.settings

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import exh.favorites.FavoritesSyncStatus
import exh.log.EHLogLevel
import exh.source.ExhPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.browser.blocking.ContentBlockerPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.security.AppLock
import zoukan.desktop.security.Incognito
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.extension.DesktopExtensionManager
import zoukan.i18n.desktop.DMR
import java.io.File

class SettingsControllersTest {

    @TempDir
    lateinit var temp: File

    private fun testIncognito(store: TestPreferenceStore): Incognito {
        val extManager = mockk<DesktopExtensionManager>(relaxed = true) {
            every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
        }
        return Incognito(store, SourcePreferences(store), extManager, ExhPreferences(store))
    }

    @Test
    fun `SecuritySettingsController manages lock setup, verification, removal, and secret store failures`() = runBlocking {
        val store = TestPreferenceStore()
        val prefs = SecurityPreferences(store)
        val secrets = FailingSecretStore()
        val lock = AppLock(prefs, secrets)
        val controller = SecuritySettingsController(prefs, lock, testIncognito(store), Dispatchers.Unconfined)

        // Enabling lock without a stored password prompts setup
        controller.toggleLock(true) shouldBe SecuritySettingsController.ToggleLockResult.NeedsPasswordSetup
        prefs.useAuthenticator().get() shouldBe false

        // Validation catches short or mismatched passwords before touching secret store
        controller.savePassword(false, "", "123", "123") shouldBe
            SecuritySettingsController.PasswordActionResult.ValidationError(localized(DMR.strings.use_at_least_4_characters))
        controller.savePassword(false, "", "1234", "5678") shouldBe
            SecuritySettingsController.PasswordActionResult.ValidationError(localized(DMR.strings.the_passwords_differ))

        // Setting a valid password enables lock
        controller.savePassword(false, "", "secret1", "secret1") shouldBe
            SecuritySettingsController.PasswordActionResult.Saved
        prefs.useAuthenticator().get() shouldBe true
        lock.hasPassword() shouldBe true

        // Turning lock off and back on reuses stored password
        controller.toggleLock(false) shouldBe SecuritySettingsController.ToggleLockResult.Disabled
        prefs.useAuthenticator().get() shouldBe false
        controller.toggleLock(true) shouldBe SecuritySettingsController.ToggleLockResult.Enabled
        prefs.useAuthenticator().get() shouldBe true

        // Wrong current password rejects password change and removal
        controller.savePassword(true, "wrong", "secret2", "secret2") shouldBe
            SecuritySettingsController.PasswordActionResult.WrongCurrentPassword
        controller.removePassword("wrong") shouldBe
            SecuritySettingsController.PasswordActionResult.WrongCurrentPassword
        lock.hasPassword() shouldBe true

        // Correct current password updates, then removes lock
        controller.savePassword(true, "secret1", "secret2", "secret2") shouldBe
            SecuritySettingsController.PasswordActionResult.Saved
        lock.verify("secret2") shouldBe true
        controller.removePassword("secret2") shouldBe
            SecuritySettingsController.PasswordActionResult.Removed
        lock.hasPassword() shouldBe false
        prefs.useAuthenticator().get() shouldBe false

        // Secret store failure reports an error instead of throwing
        secrets.failOn(FailingSecretStore.Operation.READ, FailingSecretStore.Failure.LOCKED)
        controller.toggleLock(true).shouldBeInstanceOf<SecuritySettingsController.ToggleLockResult.SecretStoreFailed>()
        controller.hasPassword().isFailure shouldBe true
        secrets.heal()

        // Days, time ranges, and per-extension incognito persist cleanly
        controller.saveDays(AppLock.LOCK_MONDAY or AppLock.LOCK_FRIDAY)
        prefs.authenticatorDays().get() shouldBe (AppLock.LOCK_MONDAY or AppLock.LOCK_FRIDAY)

        controller.addTimeRange("23:00", "22:00").shouldBeInstanceOf<SecuritySettingsController.TimeRangeResult.Invalid>()
        controller.addTimeRange("bad", "23:59").shouldBeInstanceOf<SecuritySettingsController.TimeRangeResult.Invalid>()
        controller.addTimeRange("08:30", "17:45") shouldBe
            SecuritySettingsController.TimeRangeResult.Added("${8 * 60 + 30},${17 * 60 + 45}")
        controller.saveTimeRanges(listOf("510,1065"))
        prefs.authenticatorTimeRanges().get() shouldBe setOf("510,1065")
        SecuritySettingsController.timeRangesSummary(prefs.authenticatorTimeRanges().get()) shouldBe
            localized(DMR.strings.to, "08:30", "17:45")

        controller.setExtensionIncognito("eu.kanade.test", true)
        SourcePreferences(store).incognitoExtensions().get() shouldBe setOf("eu.kanade.test")
        controller.setExtensionIncognito("eu.kanade.test", false)
        SourcePreferences(store).incognitoExtensions().get() shouldBe emptySet()
    }

    @Test
    fun `EhSettingsController handles sign-in, sync guards, config upload, and Android format preferences`() = runBlocking {
        val store = TestPreferenceStore()
        val prefs = ExhPreferences(store)
        var loggedOut = 0
        var syncRuns = 0
        var resetCalls = 0
        var shouldFail = false
        var cookieAccepted = true

        val controller = EhSettingsController(
            preferences = prefs,
            loginAction = { !shouldFail },
            cookieLoginAction = { _, _, _ ->
                if (shouldFail) throw IllegalStateException("network")
                cookieAccepted
            },
            logoutAction = { loggedOut++ },
            configureSiteAction = { if (shouldFail) throw IllegalStateException("upload failed") },
            runGalleryUpdateAction = { if (shouldFail) throw IllegalStateException("update failed") },
            runFavoritesSyncAction = { syncRuns++ },
            resetFavoritesSyncAction = {
                if (shouldFail) throw IllegalStateException("db error")
                resetCalls++
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.signIn().getOrNull() shouldBe true
        controller.signInWithCookies("1", "2", "") shouldBe EhSettingsController.CookieLoginResult.Success
        cookieAccepted = false
        controller.signInWithCookies("1", "2", "") shouldBe EhSettingsController.CookieLoginResult.Rejected

        shouldFail = true
        controller.signInWithCookies("1", "2", "").shouldBeInstanceOf<EhSettingsController.CookieLoginResult.Failed>()
        controller.applySiteSettings().isFailure shouldBe true
        controller.updateGalleriesNow().isFailure shouldBe true
        controller.resetFavoritesSyncState().isFailure shouldBe true

        shouldFail = false
        controller.applySiteSettings().isSuccess shouldBe true
        controller.updateGalleriesNow().isSuccess shouldBe true
        controller.resetFavoritesSyncState().isSuccess shouldBe true
        resetCalls shouldBe 1

        controller.logout()
        loggedOut shouldBe 1

        controller.startFavoritesSync(FavoritesSyncStatus.Processing.DownloadingFavorites) shouldBe false
        controller.startFavoritesSync(FavoritesSyncStatus.Idle) shouldBe true
        controller.startFavoritesSync(FavoritesSyncStatus.SyncError.UnknownSyncError("boom")) shouldBe true
        syncRuns shouldBe 2

        val rows = controller.loadLanguages().map { it.toMutableList() }
        rows[1][1] = true
        controller.saveLanguages(rows)
        controller.loadLanguages()[1][1] shouldBe true

        val shown = controller.loadShownCategories().toMutableList()
        shown[0] = false
        controller.saveShownCategories(shown)
        controller.loadShownCategories()[0] shouldBe false
    }

    @Test
    fun `DataSettingsController handles caches, storage checks, backup guards, and CSV export`() = runBlocking {
        val dataDir = File(temp, "data").apply { mkdirs() }
        val dirs = ZoukanDirs(dataDir)
        val store = TestPreferenceStore()
        val storage = ZoukanStorage(dirs, store)
        var coverCacheCleared = 0
        var chapterCacheFail = false
        var exportFail = false

        val controller = DataSettingsController(
            dirs = dirs,
            clearCoverCacheAction = { coverCacheCleared++ },
            chapterCacheSizeAction = { 4096L },
            clearChapterCacheAction = {
                if (chapterCacheFail) throw IllegalStateException("io error")
                3
            },
            getFavoritesAction = {
                if (exportFail) throw IllegalStateException("db error")
                listOf(Manga.create().copy(ogTitle = "Manga A", ogAuthor = "Author A", ogArtist = "Artist A"))
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.clearCoverCache()
        coverCacheCleared shouldBe 1

        controller.readChapterCacheSize() shouldBe 4096L
        controller.clearChapterCache().getOrNull() shouldBe 3
        chapterCacheFail = true
        controller.clearChapterCache().isFailure shouldBe true

        controller.hasDefaultStorageDownloads() shouldBe false
        File(dataDir, "${ZoukanStorage.DOWNLOADS}/Src/Title").mkdirs()
        controller.hasDefaultStorageDownloads() shouldBe true

        val newRoot = File(temp, "new-storage").apply { mkdirs() }
        controller.checkStorageFolder(newRoot) shouldBe StorageLocation.Verdict.Ok(hasDownloads = false)
        var invalidated = false
        controller.applyStorageFolder(storage, newRoot.path) { invalidated = true }
        invalidated shouldBe true
        storage.root shouldBe newRoot

        val csvTarget = File(temp, "library.csv")
        controller.exportLibraryCsv(csvTarget, LibraryCsv.Options(title = true, author = true, artist = false)).isSuccess shouldBe true
        csvTarget.readText() shouldBe "Manga A,Author A"

        exportFail = true
        controller.exportLibraryCsv(csvTarget, LibraryCsv.Options(title = true, author = true, artist = true)).isFailure shouldBe true

        controller.canStartBackupOrRestore(null) shouldBe true
        controller.canStartBackupOrRestore("Backing up...") shouldBe false
    }

    @Test
    fun `AdvancedSettingsController validates network prefs, clears data, refreshes covers, and saves crash logs`() = runBlocking {
        val dataDir = File(temp, "adv-data").apply { mkdirs() }
        val dirs = ZoukanDirs(dataDir)
        val store = TestPreferenceStore()
        val networkPrefs = NetworkPreferences(store)
        val blockerPrefs = ContentBlockerPreferences(store)
        val exhPrefs = ExhPreferences(store)

        var reindexed = 0
        var appCookiesCleared = 0
        var browserCookiesCleared = 0
        var browserProfileCleared = 0
        var blockListsRefreshed = 0
        var trustRevoked = 0
        var onboardingReopened = 0
        var resetViewerResult = true
        var failWebView = false
        val progressFlow = MutableStateFlow<String?>(null)
        val mangaOk = Manga.create().copy(id = 1L, ogTitle = "Good")
        val mangaBad = Manga.create().copy(id = 2L, ogTitle = "Bad")
        val forgotten = mutableListOf<Long>()
        var memoryCleared = 0

        val controller = AdvancedSettingsController(
            networkPreferences = networkPrefs,
            contentBlockerPreferences = blockerPrefs,
            exhPreferences = exhPrefs,
            dirs = dirs,
            invalidateDownloadCacheAction = { reindexed++ },
            clearAppCookiesAction = { appCookiesCleared++ },
            clearBrowserCookiesAction = { browserCookiesCleared++ },
            clearBrowserProfileAction = {
                if (failWebView) throw IllegalStateException("locked")
                browserProfileCleared++
            },
            refreshBlockListsAction = { blockListsRefreshed++ },
            resetViewerFlagsAction = { resetViewerResult },
            revokeExtensionTrustAction = { trustRevoked++ },
            reopenOnboardingAction = { onboardingReopened++ },
            getFavoritesAction = { listOf(mangaOk, mangaBad) },
            refreshMangaAction = { manga ->
                if (manga.id == 2L) throw IllegalStateException("network")
                manga
            },
            forgetCoverAction = { forgotten += it.id },
            clearMemoryCoversAction = { memoryCleared++ },
            refreshingCovers = progressFlow,
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.reopenOnboarding()
        onboardingReopened shouldBe 1

        controller.reindexDownloads()
        reindexed shouldBe 1

        controller.clearCookies()
        appCookiesCleared shouldBe 1
        browserCookiesCleared shouldBe 1

        controller.clearWebViewData().isSuccess shouldBe true
        browserCookiesCleared shouldBe 2
        browserProfileCleared shouldBe 1
        failWebView = true
        controller.clearWebViewData().isFailure shouldBe true

        // DoH changes only report restart when value actually changes
        networkPrefs.dohProvider().set(PREF_DOH_QUAD9)
        controller.setDohProvider(PREF_DOH_QUAD9) shouldBe false
        controller.setDohProvider(PREF_DOH_CLOUDFLARE) shouldBe true
        networkPrefs.dohProvider().get() shouldBe PREF_DOH_CLOUDFLARE

        // User-Agent validates header characters, tracks changes, and resets cleanly
        controller.setUserAgent("Bad\u0007Agent") shouldBe AdvancedSettingsController.UserAgentResult.Invalid
        controller.setUserAgent("ZoukanTest/2.0") shouldBe AdvancedSettingsController.UserAgentResult.UpdatedRestartRequired
        controller.setUserAgent("ZoukanTest/2.0") shouldBe AdvancedSettingsController.UserAgentResult.Unchanged
        controller.resetUserAgent() shouldBe true
        controller.resetUserAgent() shouldBe false

        // Block lists filter non-https entries and refresh only on change
        controller.updateBlockLists("https://example.org/a.txt\nhttp://insecure.example/b.txt\nhttps://example.org/a.txt") shouldBe true
        blockerPrefs.listUrls().get() shouldBe "https://example.org/a.txt"
        blockListsRefreshed shouldBe 1
        controller.updateBlockLists("https://example.org/a.txt") shouldBe false
        blockListsRefreshed shouldBe 1

        controller.resetViewerFlags() shouldBe true
        resetViewerResult = false
        controller.resetViewerFlags() shouldBe false

        controller.revokeExtensionTrust()
        trustRevoked shouldBe 1

        controller.setLogLevel(EHLogLevel.MINIMAL.ordinal) shouldBe false
        controller.setLogLevel(EHLogLevel.EXTRA.ordinal) shouldBe true
        exhPrefs.logLevel(false).get() shouldBe EHLogLevel.EXTRA.ordinal

        val crashFile = File(temp, "crash_logs.txt")
        controller.saveCrashLogs(crashFile).isSuccess shouldBe true
        crashFile.exists() shouldBe true

        // Cover refresh counts partial failures, clears memory cache, and resets progress
        controller.refreshLibraryCovers() shouldBe 1
        forgotten shouldBe listOf(1L, 1L, 2L)
        memoryCleared shouldBe 1
        controller.refreshingCoversState.value shouldBe null

        // Concurrent guard when already refreshing
        progressFlow.value = localized(DMR.strings.refreshing_covers)
        controller.refreshLibraryCovers() shouldBe null
    }
}
