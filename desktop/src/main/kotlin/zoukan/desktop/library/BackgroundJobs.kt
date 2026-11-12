package zoukan.desktop.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.backup.AutoBackup
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.track.TrackChapters

/**
 * Background work while the app runs, in place of Android's WorkManager: the scheduled library
 * update and the automatic backup. Each is checked every minute against when it last ran, so work
 * missed while the app was closed runs soon after the next launch. Closing the window to the tray
 * keeps it running.
 */
class BackgroundJobs(
    private val libraryPreferences: LibraryPreferences,
    private val libraryUpdater: LibraryUpdater,
    private val autoBackup: AutoBackup,
    private val sourceManager: SourceManager,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // Let the extensions load first, so sources are there when the update starts
            sourceManager.isInitialized.first { it }
            delay(START_DELAY)
            while (isActive) {
                runCatching { tick() }.onFailure { logcat(LogPriority.ERROR, it) { "Background work failed" } }
                delay(CHECK_EVERY)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun tick(now: Long = System.currentTimeMillis()) {
        // A restore is rewriting the library: none of this may run until it is done, and the next
        // check retries
        if (RestoreGate.isRestoring) {
            logcat { "A backup restore is running; the background work waits" }
            return
        }
        if (libraryUpdateDue(now) && !libraryUpdater.isRunning && restrictionsMet()) {
            logcat { "Starting the scheduled library update" }
            libraryUpdater.start(manual = false)
        }
        if (autoBackup.isDue(now)) autoBackup.run()
        // Data sync on its interval, in place of SyncDataJob
        val sync = Injekt.get<eu.kanade.tachiyomi.data.sync.SyncManager>()
        if (sync.isDue(now)) sync.syncData()
        // E-Hentai gallery updates, in place of EHentaiUpdateWorker
        runCatching { Injekt.get<zoukan.desktop.exh.EhGalleryUpdater>().runIfDue() }
            .onFailure { logcat(LogPriority.WARN, it) { "Updating E-Hentai galleries failed" } }
        // Tracker updates that failed (offline, site down), in place of DelayedTrackingUpdateJob
        if (now - lastTrackingRetry >= TRACKING_RETRY_EVERY) {
            lastTrackingRetry = now
            runCatching { Injekt.get<TrackChapters>().retryDelayed() }
                .onFailure { logcat(LogPriority.WARN, it) { "Retrying tracker updates failed" } }
        }
    }

    private var lastTrackingRetry = 0L

    fun libraryUpdateDue(now: Long = System.currentTimeMillis()): Boolean {
        val hours = libraryPreferences.autoUpdateInterval().get()
        if (hours <= 0) return false
        return now - libraryPreferences.lastUpdatedTimestamp().get() >= hours * 60L * 60 * 1000
    }

    /**
     * The library update's device restrictions (Settings > Library) that a desktop can check:
     * "Charging" waits for mains power. Wi-Fi and unmetered network are Android's, which a
     * desktop cannot tell; they are kept in the setting, for Android, and not checked here.
     */
    private fun restrictionsMet(): Boolean {
        val restrictions = libraryPreferences.autoUpdateDeviceRestrictions().get()
        return LibraryPreferences.DEVICE_CHARGING !in restrictions || PowerStatus.onMainsPower()
    }

    companion object {
        private const val START_DELAY = 20_000L
        private const val CHECK_EVERY = 60_000L
        private const val TRACKING_RETRY_EVERY = 15 * 60_000L
    }
}
