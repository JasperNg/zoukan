package zoukan.desktop.backup

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.create.BackupContentCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupContentRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import mihon.domain.extension.interactor.GetExtensionStores
import okio.sink
import okio.source
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.extension.DesktopSourceManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Creates and restores `.tachibk` backups on desktop with the code the Android app uses (the shared
 * `backup` module). Two differences, both so a restore and backup on desktop loses nothing:
 *
 * - Source settings and manga metadata are backed up even when the source's extension is not
 *   installed (Android backs them up only for installed sources).
 * - A restore remembers the source names the backup lists, so a later backup lists them too.
 */
class DesktopBackups(
    /** The AndroidCompat folder, whose `settings/` holds one file per SharedPreferences name. */
    private val androidDir: File,
    private val stubSourceRepository: StubSourceRepository = Injekt.get(),
    /** Waits until the source manager knows the names of sources without an extension. */
    private val awaitSourceNames: suspend () -> Unit = {
        (Injekt.get<SourceManager>() as? DesktopSourceManager)?.awaitStubs()
    },
) {

    fun contentCreator(): BackupContentCreator = BackupContentCreator(
        mangaBackupCreator = MangaBackupCreator(requireMetadataSource = false),
        preferenceBackupCreator = PreferenceBackupCreator(extraSourcePreferenceKeys = ::storedSourcePreferenceKeys),
    )

    /** Builds a backup and writes it to [file], gzipped, as the Android app does. */
    suspend fun create(file: File, options: BackupOptions = BackupOptions(), replaceExisting: Boolean = true): Backup {
        // Right after start-up (the command line) the stubs may not be read yet, and the backup
        // would list its sources with blank names
        awaitSourceNames()
        val backup = contentCreator().create(options)
        file.absoluteFile.parentFile?.mkdirs()
        val temp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        try {
            temp.sink().use { BackupCodec.write(backup, it) }
            // Make sure it reads back before it replaces anything
            temp.source().use { BackupCodec.decode(it) }
            moveIntoPlace(temp, file, replaceExisting)
        } finally {
            // A failed write or check leaves nothing behind (automatic backups would pile them up)
            temp.delete()
        }
        return backup
    }

    /** Reads a backup file, gzipped or not. */
    fun read(file: File): Backup = file.source().use { BackupCodec.decode(it) }

    /**
     * Restores [backup].
     *
     * @return the errors met, with when they happened.
     */
    suspend fun restore(
        backup: Backup,
        options: RestoreOptions = RestoreOptions(),
        isSync: Boolean = false,
        allowSensitive: Boolean = false,
        onProgress: suspend (BackupContentRestorer.Step, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Pair<Date, String>> {
        backup.backupSources
            .filter { it.name.isNotBlank() && it.name != it.sourceId.toString() }
            .forEach { source ->
                if (stubSourceRepository.getStubSource(source.sourceId) == null) {
                    stubSourceRepository.upsertStubSource(source.sourceId, "", source.name)
                }
            }
        return BackupContentRestorer(isSync = isSync, onProgress = onProgress).restore(withSafePreferences(backup, allowSensitive), options)
    }

    /**
     * The extension stores in [backup] that a restore would add or change, by the index address
     * the restore writes. A restore replaces a known store's signing key, extension list address
     * and website and Discord links, so a change to any of them needs the same confirmation as a
     * new store.
     */
    suspend fun newExtensionStores(
        backup: Backup,
        getStores: GetExtensionStores = Injekt.get(),
    ): List<BackupExtensionStore> {
        val known = getStores.get().associateBy { it.indexUrl }
        return backup.backupExtensionStores.filter {
            val current = known[ExtensionStoreRestorer.indexUrlOf(it)]
            current == null ||
                current.signingKey != it.signingKey ||
                current.extensionListUrl != it.extensionListUrl ||
                current.contact.website != it.contactWebsite ||
                current.contact.discord != it.contactDiscord
        }
    }

    /**
     * [backup] without the extension stores [newExtensionStores] reports, and those stores, for a
     * restore nobody is asked to confirm (data sync, the command line): it may keep stores the
     * user already trusts with the same details, but never adds one or changes a signing key,
     * extension list address or website and Discord links.
     */
    suspend fun withoutNewExtensionStores(
        backup: Backup,
        getStores: GetExtensionStores = Injekt.get(),
    ): Pair<Backup, List<BackupExtensionStore>> {
        val new = newExtensionStores(backup, getStores)
        if (new.isEmpty()) return backup to emptyList()
        return backup.copy(backupExtensionStores = backup.backupExtensionStores.filterNot { it in new }) to new
    }

    /** The `source_<id>` SharedPreferences that have a file, installed or not. */
    fun storedSourcePreferenceKeys(): List<String> =
        File(androidDir, "settings")
            .listFiles { file -> SOURCE_PREFS_FILE.matches(file.name) }
            .orEmpty()
            .map { it.name.removeSuffix(".xml") }

    companion object {
        /** With [replaceExisting] false an existing [file] is kept and `FileAlreadyExistsException` is thrown. */
        internal fun moveIntoPlace(temp: File, file: File, replaceExisting: Boolean) {
            if (replaceExisting) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(temp.toPath(), file.toPath())
            }
        }

        /**
         * [backup] without the preferences a backup file must not set. The app never writes
         * app-state keys into a backup (trusted extensions, the storage folder and other state of
         * this computer), so one that carries them was made by hand; restoring them could trust an
         * extension nobody approved or move the storage folder to another machine. A restore also
         * does not switch the app lock off. Source settings go only to `source_<id>` files, the
         * only ones a backup makes, so a backup cannot write into the app's own settings file
         * (and switch the lock off there) by naming it as a source. Sensitive keys
         * ([isSensitive]) are written only with [allowSensitive], which the user gives by
         * confirming them.
         */
        fun withSafePreferences(
            backup: Backup,
            allowSensitive: Boolean = false,
            lockIsOn: () -> Boolean = { runCatching { Injekt.get<SecurityPreferences>().useAuthenticator().get() }.getOrDefault(false) },
        ): Backup {
            fun allowed(preference: BackupPreference): Boolean = when {
                Preference.isAppState(preference.key) -> false
                isSensitive(preference.key) -> allowSensitive
                preference.key == LOCK_KEY -> (preference.value as? BooleanPreferenceValue)?.value == true || !lockIsOn()
                else -> true
            }
            return backup.copy(
                backupPreferences = backup.backupPreferences.filter(::allowed),
                backupSourcePreferences = backup.backupSourcePreferences
                    .filter { SOURCE_PREFS_KEY.matches(it.sourceKey) }
                    .map { source ->
                        source.copy(prefs = source.prefs.filter { !Preference.isAppState(it.key) && (allowSensitive || !isSensitive(it.key)) })
                    },
            )
        }

        /** The distinct sensitive keys in [backup] that a restore would write only when confirmed. */
        fun sensitiveKeys(backup: Backup): List<String> =
            (backup.backupPreferences.map { it.key } + backup.backupSourcePreferences.filter { SOURCE_PREFS_KEY.matches(it.sourceKey) }.flatMap { source -> source.prefs.map { it.key } })
                .filter { !Preference.isAppState(it) && isSensitive(it) }
                .distinct()

        private const val LOCK_KEY = "use_biometric_lock"

        /**
         * Settings a restore writes only when the user confirms them: they decide when the app
         * locks, which DNS it uses, where sync sends the library and whether it sends logins too,
         * and whether the app talks to Discord or checks for updates. Never written by a restore
         * nobody confirms (data sync, the command line).
         */
        internal val SENSITIVE_KEYS = setOf(
            "biometric_days", "biometric_time_ranges", "lock_app_after", "secure_screen_v2",
            "doh_provider",
            "sync_service", "connection_sync_client_host", "connection_sync_client_api_key", "sync_privateSettings",
            "connection_webdav_url", "connection_webdav_username", "connection_webdav_password", "connection_webdav_folder",
            "zoukan_update_check", "zoukan_update_prereleases",
            "pref_enable_discord_rpc",
        )

        internal fun isSensitive(key: String) = key in SENSITIVE_KEYS || Preference.isPrivate(key)

        private val SOURCE_PREFS_FILE = Regex("""source_-?\d+\.xml""")
        private val SOURCE_PREFS_KEY = Regex("""source_-?\d+""")

        /** The file name the Android app uses, with Zoukan's name. */
        fun defaultFileName(): String =
            "zoukan_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))}.tachibk"
    }
}

/** For reading backup files without starting the runtime. */
object NoStubSources : StubSourceRepository {
    override fun subscribeAll() = kotlinx.coroutines.flow.flowOf(emptyList<tachiyomi.domain.source.model.StubSource>())
    override suspend fun getStubSource(id: Long) = null
    override suspend fun upsertStubSource(id: Long, lang: String, name: String) = Unit
}
