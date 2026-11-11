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
import mihon.domain.extension.interactor.GetExtensionStores
import okio.sink
import okio.source
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.repository.StubSourceRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
) {

    fun contentCreator(): BackupContentCreator = BackupContentCreator(
        mangaBackupCreator = MangaBackupCreator(requireMetadataSource = false),
        preferenceBackupCreator = PreferenceBackupCreator(extraSourcePreferenceKeys = ::storedSourcePreferenceKeys),
    )

    /** Builds a backup and writes it to [file], gzipped, as the Android app does. */
    suspend fun create(file: File, options: BackupOptions = BackupOptions()): Backup {
        val backup = contentCreator().create(options)
        file.absoluteFile.parentFile?.mkdirs()
        val temp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        temp.sink().use { BackupCodec.write(backup, it) }
        // Make sure it reads back before it replaces anything
        temp.source().use { BackupCodec.decode(it) }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
        onProgress: suspend (BackupContentRestorer.Step, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Pair<Date, String>> {
        backup.backupSources
            .filter { it.name.isNotBlank() && it.name != it.sourceId.toString() }
            .forEach { source ->
                if (stubSourceRepository.getStubSource(source.sourceId) == null) {
                    stubSourceRepository.upsertStubSource(source.sourceId, "", source.name)
                }
            }
        return BackupContentRestorer(isSync = isSync, onProgress = onProgress).restore(withSafePreferences(backup), options)
    }

    /** The extension stores in [backup] that are not added yet, by index address. */
    suspend fun newExtensionStores(
        backup: Backup,
        getStores: GetExtensionStores = Injekt.get(),
    ): List<BackupExtensionStore> {
        val known = getStores.get().map { it.indexUrl }.toSet()
        return backup.backupExtensionStores.filter { it.indexUrl !in known }
    }

    /** The `source_<id>` SharedPreferences that have a file, installed or not. */
    fun storedSourcePreferenceKeys(): List<String> =
        File(androidDir, "settings")
            .listFiles { file -> SOURCE_PREFS_FILE.matches(file.name) }
            .orEmpty()
            .map { it.name.removeSuffix(".xml") }

    companion object {
        /**
         * [backup] without the preferences a backup file must not set. The app never writes
         * app-state keys into a backup (trusted extensions, the storage folder and other state of
         * this computer), so one that carries them was made by hand; restoring them could trust an
         * extension nobody approved or move the storage folder to another machine. A restore also
         * does not switch the app lock off.
         */
        fun withSafePreferences(
            backup: Backup,
            lockIsOn: () -> Boolean = { runCatching { Injekt.get<SecurityPreferences>().useAuthenticator().get() }.getOrDefault(false) },
        ): Backup {
            fun allowed(preference: BackupPreference): Boolean = when {
                Preference.isAppState(preference.key) -> false
                preference.key == LOCK_KEY -> (preference.value as? BooleanPreferenceValue)?.value == true || !lockIsOn()
                else -> true
            }
            return backup.copy(
                backupPreferences = backup.backupPreferences.filter(::allowed),
                backupSourcePreferences = backup.backupSourcePreferences.map { source ->
                    source.copy(prefs = source.prefs.filterNot { Preference.isAppState(it.key) })
                },
            )
        }

        private const val LOCK_KEY = "use_biometric_lock"

        private val SOURCE_PREFS_FILE = Regex("""source_-?\d+\.xml""")

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
