package eu.kanade.tachiyomi.data.sync

import android.app.Application
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.sync.models.SyncTriggerOptions
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.data.sync.service.SyncYomiSyncService
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Chapters
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaMapper.mapManga
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.compat.localized
import zoukan.desktop.tray.Notifications
import zoukan.desktop.ui.AppScope
import zoukan.i18n.desktop.DMR
import java.util.Date

/**
 * The Android app's SyncManager on desktop: the same SyncYomi and WebDAV services and the same
 * merge, with the backup made and restored by [DesktopBackups] in place of BackupCreator and
 * BackupRestoreJob. Google Drive needs the app's OAuth client secrets, so it is not offered.
 */
class SyncManager(
    private val context: Application = Injekt.get(),
    private val handler: DatabaseHandler = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val dirs: ZoukanDirs = Injekt.get(),
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val notifier = SyncNotifier(context)
    private val mangaRestorer by lazy { MangaRestorer() }
    private val mutex = Mutex()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    enum class SyncService(val value: Int) {
        NONE(0),
        SYNCYOMI(1),
        GOOGLE_DRIVE(2),
        WEB_DAV(3),
        ;

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    companion object {
        /** The services desktop has; Google Drive needs the Android build's OAuth client. */
        val SUPPORTED = setOf(SyncService.SYNCYOMI, SyncService.WEB_DAV)
        const val RETRY_AFTER = 60 * 60_000L
    }

    /** When the last sync failed; a failed sync waits before it tries again, not the next minute's check. */
    @Volatile
    private var lastFailure = 0L

    /** Whether the sync interval has passed since the last sync, and a failure is not too recent. */
    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        if (SyncService.fromInt(syncPreferences.syncService().get()) !in SUPPORTED) return false
        val minutes = syncPreferences.syncInterval().get()
        if (minutes <= 0) return false
        val interval = minutes * 60_000L
        if (now - lastFailure < minOf(interval, RETRY_AFTER)) return false
        return now - syncPreferences.lastSyncTimestamp().get() >= interval
    }

    /** Syncs in the background when [trigger] is on in the sync trigger settings. */
    fun syncOn(trigger: (SyncTriggerOptions) -> Boolean) {
        if (SyncService.fromInt(syncPreferences.syncService().get()) in SUPPORTED && trigger(syncPreferences.getSyncTriggerOptions())) {
            AppScope.launch { syncData() }
        }
    }

    suspend fun syncData() {
        if (mutex.isLocked) return
        mutex.withLock {
            _running.value = true
            try {
                lastFailure = if (doSync()) 0L else System.currentTimeMillis()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastFailure = System.currentTimeMillis()
                logcat(LogPriority.ERROR, e) { "Sync failed" }
                notifier.showSyncError(e.message)
            } finally {
                _running.value = false
            }
        }
    }

    /** Returns false when the sync failed (the services notify why). */
    private suspend fun doSync(): Boolean {
        // Reset isSyncing in case it was left over or failed syncing during restore.
        handler.await(inTransaction = true) {
            mangasQueries.resetIsSyncing()
            chaptersQueries.resetIsSyncing()
        }

        val syncOptions = syncPreferences.getSyncSettings()
        val databaseManga = handler.awaitList { mangasQueries.getMangasWithFavoriteTimestamp(::mapManga) }

        val backupOptions = BackupOptions(
            libraryEntries = syncOptions.libraryEntries,
            categories = syncOptions.categories,
            chapters = syncOptions.chapters,
            tracking = syncOptions.tracking,
            history = syncOptions.history,
            extensionStores = syncOptions.extensionStores,
            appSettings = syncOptions.appSettings,
            sourceSettings = syncOptions.sourceSettings,
            privateSettings = syncOptions.privateSettings,
            customInfo = syncOptions.customInfo,
            readEntries = syncOptions.readEntries,
            savedSearchesFeeds = syncOptions.savedSearchesFeeds,
        )

        val backups = DesktopBackups(dirs.android)
        val creator = backups.contentCreator()
        val backupManga = creator.backupMangas(databaseManga, backupOptions)
        val backup = Backup(
            backupManga = backupManga,
            backupCategories = creator.backupCategories(backupOptions),
            backupSources = creator.backupSources(backupManga),
            backupPreferences = creator.backupAppPreferences(backupOptions),
            backupSourcePreferences = creator.backupSourcePreferences(backupOptions),
            backupExtensionStores = creator.backupExtensionStores(backupOptions),
            backupSavedSearches = creator.backupSavedSearches(backupOptions),
            backupFeeds = creator.backupFeeds(backupOptions),
        )

        val syncData = SyncData(deviceId = syncPreferences.uniqueDeviceID(), backup = backup)

        val service = when (val type = SyncService.fromInt(syncPreferences.syncService().get())) {
            SyncService.SYNCYOMI -> SyncYomiSyncService(context, json, syncPreferences, notifier)
            SyncService.WEB_DAV -> WebDavSyncService(context, json, syncPreferences, notifier)
            else -> {
                logcat(LogPriority.ERROR) { "Sync service not available on desktop: $type" }
                null
            }
        }

        val remoteBackup = service?.doSync(syncData) ?: return false

        if (remoteBackup === syncData.backup) {
            syncPreferences.lastSyncTimestamp().set(Date().time)
            notifier.showSyncSuccess("Sync completed successfully")
            return true
        }

        if (remoteBackup.backupManga.isEmpty()) {
            notifier.showSyncError("No data found on remote server.")
            return false
        }

        // The first sync only uploads
        if (syncPreferences.lastSyncTimestamp().get() == 0L && databaseManga.isNotEmpty()) {
            syncPreferences.lastSyncTimestamp().set(Date().time)
            notifier.showSyncSuccess("Updated remote data successfully")
            return true
        }

        val (favorites, nonFavorites) = splitFavorites(remoteBackup)
        updateNonFavorites(nonFavorites)

        if (favorites.isEmpty()) {
            syncPreferences.lastSyncTimestamp().set(Date().time)
            notifier.showSyncSuccess("Sync completed successfully")
            return true
        }

        val merged = backup.copy(
            backupManga = favorites,
            backupCategories = remoteBackup.backupCategories,
            backupSources = remoteBackup.backupSources,
            backupPreferences = remoteBackup.backupPreferences,
            backupSourcePreferences = remoteBackup.backupSourcePreferences,
            backupExtensionStores = remoteBackup.backupExtensionStores,
            backupSavedSearches = remoteBackup.backupSavedSearches,
            backupFeeds = remoteBackup.backupFeeds,
        )
        // The gate holds the background jobs off while the library is half-restored
        val (errors, skippedStores) = RestoreGate.whileRestoring {
            // ZOUKAN -->
            // Extension stores decide whose code runs. Nobody confirms a sync, so it never adds a
            // store or changes a signing key; the user adds those by hand (Settings restore asks)
            val (restorable, skipped) = backups.withoutNewExtensionStores(merged)
            // ZOUKAN <--
            backups.restore(
                restorable,
                RestoreOptions(appSettings = true, sourceSettings = true, libraryEntries = true, extensionStores = true),
                isSync = true,
            ) to skipped
        }
        // ZOUKAN -->
        if (skippedStores.isNotEmpty()) {
            logcat(LogPriority.WARN) { "Sync skipped ${skippedStores.size} new or re-keyed extension stores" }
            val names = skippedStores.map { it.name.ifBlank { it.indexUrl }.take(80) }
            val shown = if (names.size <= 5) {
                names.joinToString()
            } else {
                localized(DMR.strings.list_and_n_more, names.take(5).joinToString(), names.size - 5)
            }
            Notifications.post(
                localized(DMR.strings.extension_repos),
                localized(DMR.strings.sync_skipped_extension_stores, shown),
                Notifications.Kind.WARNING,
            )
        }
        // ZOUKAN <--
        syncPreferences.lastSyncTimestamp().set(Date().time)
        if (errors.isEmpty()) {
            notifier.showSyncSuccess("Sync completed successfully")
        } else {
            notifier.showSyncError("Sync finished with ${errors.size} errors: ${errors.first().second}")
        }
        return true
    }

    private suspend fun allManga(): List<Manga> = handler.awaitList { mangasQueries.getAllManga(::mapManga) }

    private suspend fun isMangaDifferent(local: Manga, remote: BackupManga): Boolean {
        val localChapters = handler.await {
            chaptersQueries.getChaptersByMangaId(
                local.id,
                0,
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
            ).executeAsList()
        }
        val localCategories = getCategories.await(local.id).map { it.order }
        return chaptersDiffer(localChapters, remote.chapters) ||
            local.version != remote.version ||
            localCategories.toSet() != remote.categories.toSet()
    }

    private fun chaptersDiffer(local: List<Chapters>, remote: List<BackupChapter>): Boolean {
        val localMap = local.associateBy { it.url }
        val remoteMap = remote.associateBy { it.url }
        if (localMap.size != remoteMap.size) return true
        return localMap.any { (url, chapter) -> remoteMap[url]?.version != chapter.version }
    }

    private suspend fun splitFavorites(backup: Backup): Pair<List<BackupManga>, List<BackupManga>> {
        val local = allManga().associateBy { Triple(it.source, it.url, it.title) }
        val favorites = mutableListOf<BackupManga>()
        val nonFavorites = mutableListOf<BackupManga>()
        backup.backupManga.forEach { remote ->
            if (remote.favorite) {
                val match = local[Triple(remote.source, remote.url, remote.title)]
                if (match == null || isMangaDifferent(match, remote)) favorites.add(remote)
            } else {
                nonFavorites.add(remote)
            }
        }
        return favorites to nonFavorites
    }

    private suspend fun updateNonFavorites(nonFavorites: List<BackupManga>) {
        val local = allManga().associateBy { Triple(it.source, it.url, it.title) }
        nonFavorites.forEach { remote ->
            local[Triple(remote.source, remote.url, remote.title)]?.let { manga ->
                if (manga.favorite != remote.favorite) mangaRestorer.updateManga(manga.copy(favorite = remote.favorite))
            }
        }
    }
}
