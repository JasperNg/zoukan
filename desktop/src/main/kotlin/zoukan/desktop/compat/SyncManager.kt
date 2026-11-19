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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Chapters
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaMapper.mapManga
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.AppScope
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.backup.SnapshotReason
import zoukan.desktop.compat.localized
import zoukan.desktop.tray.Notifications
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
    // ZOUKAN -->
    private val safetySnapshot: SafetySnapshot = SafetySnapshot.forApp(),
    /** Writes a local entry's new favorite state; tests replace it. */
    private val updateLocalManga: (suspend (Manga) -> Unit)? = null,
    // ZOUKAN <--
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val notifier = SyncNotifier(context)
    private val mangaRestorer by lazy { MangaRestorer() }
    private val mutex = Mutex()

    // ZOUKAN -->
    /**
     * Set from just before the service syncs until the merged library is all restored here (or the
     * service uploaded nothing). The service saves the server's new ETag itself, before doSync can
     * set it aside: if the app quits in between (SyncYomi still reports the sync to the server
     * then), the next sync must not trust that ETag. App state, so in no backup and never synced.
     */
    private fun restorePending() =
        Injekt.get<PreferenceStore>().getBoolean(Preference.appStateKey("zoukan_sync_restore_pending"), false)

    /**
     * Set when the last restore of the server's library ended with errors, until one ends without.
     * The next sync restores only the entries that differ here, so a failed section (categories,
     * settings, saved searches, feeds) whose entries all arrived would otherwise never be retried.
     */
    private fun sectionsPending() =
        Injekt.get<PreferenceStore>().getBoolean(Preference.appStateKey("zoukan_sync_sections_pending"), false)
    // ZOUKAN <--

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

        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /**
         * Whether a sync server address is safe to send the library and logins to: https anywhere, or
         * plain http only to this computer or the local network. The host name is never resolved
         * (a lookup would leak it outside DNS over HTTPS); only IP literals and local names count.
         */
        internal fun isAllowedSyncUrl(url: String): Boolean {
            val parsed = url.trim().toHttpUrlOrNull() ?: return false
            if (parsed.isHttps) return true
            val host = parsed.host.lowercase().trimEnd('.')
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".home.arpa")) {
                return true
            }
            IPV4.matchEntire(host)?.let { m ->
                val o = m.groupValues.drop(1).map { it.toInt() }
                if (o.any { it > 255 }) return false
                return o[0] == 127 || o[0] == 10 ||
                    (o[0] == 172 && o[1] in 16..31) ||
                    (o[0] == 192 && o[1] == 168) ||
                    (o[0] == 169 && o[1] == 254)
            }
            if (host.contains(':')) {
                // An IPv6 literal (HttpUrl gives it without brackets)
                val a = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() as? java.net.Inet6Address
                    ?: return false
                val first = a.address[0].toInt() and 0xFF
                val second = a.address[1].toInt() and 0xFF
                return a.isLoopbackAddress || a.isLinkLocalAddress || (first and 0xFE) == 0xFC ||
                    (first == 0xFE && (second and 0xC0) == 0x80)
            }
            return false
        }
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
        // ZOUKAN -->
        // Checked before the database and the network are touched
        if (!serverAllowed()) return false
        // ZOUKAN <--

        // Reset isSyncing in case it was left over or failed syncing during restore.
        handler.await(inTransaction = true) {
            mangasQueries.resetIsSyncing()
            chaptersQueries.resetIsSyncing()
        }
        // ZOUKAN -->
        // A sync that uploaded but quit before its restore ended: the ETag it saved is not this computer's
        if (restorePending().get()) syncPreferences.lastSyncEtag().delete()
        // ZOUKAN <--

        val databaseManga = handler.awaitList { mangasQueries.getMangasWithFavoriteTimestamp(::mapManga) }
        // ZOUKAN -->
        val backups = DesktopBackups(dirs.android)
        val backup = localBackup(backups, databaseManga)
        // ZOUKAN <--

        val syncData = SyncData(deviceId = syncPreferences.uniqueDeviceID(), backup = backup)

        val service = when (val type = SyncService.fromInt(syncPreferences.syncService().get())) {
            SyncService.SYNCYOMI -> SyncYomiSyncService(context, json, syncPreferences, notifier)
            SyncService.WEB_DAV -> WebDavSyncService(context, json, syncPreferences, notifier)
            else -> {
                logcat(LogPriority.ERROR) { "Sync service not available on desktop: $type" }
                null
            }
        }

        // ZOUKAN -->
        val etagBefore = syncPreferences.lastSyncEtag().get()
        restorePending().set(true)
        val remoteBackup = service?.doSync(syncData)
        if (remoteBackup == null) {
            // Nothing uploaded, so the ETag (if any) is still the one this computer had restored
            restorePending().delete()
            return false
        }
        // ZOUKAN <--

        // ZOUKAN -->
        // The server now has the merged library under this ETag, but this computer has it only once
        // it is all restored here. Until then the ETag is not kept: the services send it as
        // If-None-Match, and on "not modified" upload this computer's library over the server's,
        // which would delete the entries that never arrived here (a failed or interrupted restore,
        // or the first sync, which only uploads). Without it the next sync merges the server's again
        // An ETag the service did not change is not the uploaded library's: the server sent none, or
        // WebDAV's upload met a conflict
        val etag = syncPreferences.lastSyncEtag().get().takeIf { it != etagBefore }.orEmpty()
        syncPreferences.lastSyncEtag().delete()
        // ZOUKAN <--

        if (remoteBackup === syncData.backup) {
            // ZOUKAN -->
            keepEtag(etag)
            // ZOUKAN <--
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
        // ZOUKAN -->
        // The sections a restore with errors left behind are restored again, with no entry to restore
        val retrySections = favorites.isEmpty() && sectionsPending().get()
        if (!snapshotThenUpdateNonFavorites(favorites, nonFavorites, alwaysSnapshot = retrySections)) return false
        // ZOUKAN <--

        if (favorites.isEmpty() /* ZOUKAN --> */ && !retrySections /* ZOUKAN <-- */) {
            // ZOUKAN -->
            keepEtag(etag)
            // ZOUKAN <--
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
        // ZOUKAN -->
        if (errors.isEmpty()) keepEtag(etag) else syncPreferences.lastSyncEtag().delete()
        if (errors.isEmpty()) sectionsPending().delete() else sectionsPending().set(true)
        // ZOUKAN <--
        syncPreferences.lastSyncTimestamp().set(Date().time)
        if (errors.isEmpty()) {
            notifier.showSyncSuccess("Sync completed successfully")
        } else {
            notifier.showSyncError("Sync finished with ${errors.size} errors: ${errors.first().second}")
        }
        return true
    }

    // ZOUKAN -->
    /**
     * This computer's library as a sync uploads it: what Settings > Data sync > What to sync
     * chooses, from [databaseManga] (the library and the entries with a favorite time).
     */
    private suspend fun localBackup(backups: DesktopBackups, databaseManga: List<Manga>): Backup {
        val syncOptions = syncPreferences.getSyncSettings()
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
        val creator = backups.contentCreator()
        val backupManga = creator.backupMangas(databaseManga, backupOptions)
        return Backup(
            backupManga = backupManga,
            backupCategories = creator.backupCategories(backupOptions),
            backupSources = creator.backupSources(backupManga),
            backupPreferences = creator.backupAppPreferences(backupOptions),
            backupSourcePreferences = creator.backupSourcePreferences(backupOptions),
            backupExtensionStores = creator.backupExtensionStores(backupOptions),
            backupSavedSearches = creator.backupSavedSearches(backupOptions),
            backupFeeds = creator.backupFeeds(backupOptions),
        )
    }

    /**
     * Whether the sync server's address may be sent the library: false (and the user is told) for
     * plain http to a server outside this computer and the local network.
     */
    private fun serverAllowed(): Boolean {
        val serverUrl = when (SyncService.fromInt(syncPreferences.syncService().get())) {
            SyncService.WEB_DAV -> syncPreferences.webDavUrl().get()
            SyncService.SYNCYOMI -> syncPreferences.clientHost().get()
            else -> return true
        }
        if (!isAllowedSyncUrl(serverUrl)) {
            logcat(LogPriority.ERROR) { "Sync refused: plain http to a server outside the local network" }
            // Only the host: the address may carry a user name and password
            val host = serverUrl.trim().toHttpUrlOrNull()?.host?.take(100) ?: "?"
            notifier.showSyncError(localized(DMR.strings.sync_refused_plain_http, host))
            return false
        }
        if (serverUrl.trim().startsWith("http://", ignoreCase = true)) {
            logcat(LogPriority.WARN) { "Syncing over plain http to a local network server" }
        }
        return true
    }

    /**
     * Uploads this computer's library over the server's, whatever the server holds (no If-Match):
     * the way out of a library on the server that cannot be read, which every sync stops at. Only
     * on the user's confirmation (Settings > Data sync): what only the server held (from devices
     * that no longer sync, and the unreadable data itself) is gone. Another device that still has
     * its entries keeps its own ETag, so its next sync downloads this library, merges its own into
     * it (the merge is a union) and uploads the result. Nothing here changes: a safety backup of this
     * computer's library comes first all the same, as before the other destructive actions, and
     * without one nothing is sent. Afterwards the server holds this computer's library under the
     * ETag the service saved, so the next sync is an ordinary one.
     *
     * @return whether the server's library was replaced; the user is told either way
     */
    suspend fun replaceServerLibrary(): Boolean {
        if (mutex.isLocked) return false
        return mutex.withLock {
            _running.value = true
            try {
                replaceServerLibraryNow()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Replacing the server's library failed" }
                notifier.showSyncError(e.message)
                false
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun replaceServerLibraryNow(): Boolean {
        val type = SyncService.fromInt(syncPreferences.syncService().get())
        if (type !in SUPPORTED || !serverAllowed()) return false
        safetySnapshot.take(SnapshotReason.SYNC).onFailure { e ->
            notifier.showSyncError(localized(DMR.strings.server_library_not_replaced_no_safety_backup, SafetySnapshot.errorText(e)))
            return false
        }
        val databaseManga = handler.awaitList { mangasQueries.getMangasWithFavoriteTimestamp(::mapManga) }
        val syncData = SyncData(deviceId = syncPreferences.uniqueDeviceID(), backup = localBackup(DesktopBackups(dirs.android), databaseManga))
        // The ETag of the file being replaced is no version this computer has; the upload saves the new one
        syncPreferences.lastSyncEtag().delete()
        val replaced = when (type) {
            SyncService.WEB_DAV -> WebDavSyncService(context, json, syncPreferences, notifier).replaceRemote(syncData)
            SyncService.SYNCYOMI -> SyncYomiSyncService(context, json, syncPreferences, notifier).replaceRemote(syncData)
            else -> false
        }
        if (!replaced) return false
        // The server holds this computer's library: nothing is waiting to be restored here
        restorePending().delete()
        sectionsPending().delete()
        syncPreferences.lastSyncTimestamp().set(Date().time)
        lastFailure = 0L
        notifier.showSyncSuccess(localized(DMR.strings.server_library_replaced))
        return true
    }

    /** Saves [etag] again once the merged library is all restored here (see doSync). */
    private fun keepEtag(etag: String) {
        if (etag.isNotEmpty()) syncPreferences.lastSyncEtag().set(etag) else syncPreferences.lastSyncEtag().delete()
        restorePending().delete()
    }
    // ZOUKAN <--

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

    // ZOUKAN -->
    /**
     * Takes a safety snapshot before the sync changes the library, then writes the remote's
     * non-favorites over the local entries (which can take a local entry out of the library). A
     * copy of the library comes first so the sync can be undone; nobody is there to ask, so without
     * one the library is left as it is, the sync tries again later and the notification says why.
     *
     * The snapshot is taken when [favorites] will be restored or any local entry's favorite state
     * changes, even when the remote has no favorites at all, and with [alwaysSnapshot] (the remote's
     * other sections will be restored).
     *
     * @return false when the snapshot failed and nothing was written
     */
    internal suspend fun snapshotThenUpdateNonFavorites(
        favorites: List<BackupManga>,
        nonFavorites: List<BackupManga>,
        alwaysSnapshot: Boolean = false,
    ): Boolean {
        val changes = nonFavoriteChanges(nonFavorites)
        if (favorites.isNotEmpty() || changes.isNotEmpty() || alwaysSnapshot) {
            safetySnapshot.take(SnapshotReason.SYNC).onFailure { e ->
                notifier.showSyncError(localized(DMR.strings.sync_skipped_no_safety_backup, SafetySnapshot.errorText(e)))
                return false
            }
        }
        val update = updateLocalManga ?: { manga -> mangaRestorer.updateManga(manga) }
        changes.forEach { update(it) }
        return true
    }

    /** The local entries whose favorite state the remote's [nonFavorites] change, as they will be written. */
    private suspend fun nonFavoriteChanges(nonFavorites: List<BackupManga>): List<Manga> {
        if (nonFavorites.isEmpty()) return emptyList()
        val local = allManga().associateBy { Triple(it.source, it.url, it.title) }
        return nonFavorites.mapNotNull { remote ->
            local[Triple(remote.source, remote.url, remote.title)]
                ?.takeIf { it.favorite != remote.favorite }
                ?.copy(favorite = remote.favorite)
        }
    }
    // ZOUKAN <--
}
