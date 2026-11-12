package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.io.File

/** A backup file made by hand must not set this computer's own state. */
class RestoreFilterTest {
    private val trusted = BackupPreference(
        Preference.appStateKey("trusted_extensions"),
        StringSetPreferenceValue(setOf("evil.pkg:1:deadbeef")),
    )
    private val storage = BackupPreference(Preference.appStateKey("storage_dir"), StringPreferenceValue("\\\\evil\\share"))
    private val theme = BackupPreference("pref_app_theme", StringPreferenceValue("MONET"))
    private val token = BackupPreference(Preference.privateKey("track_token_1"), StringPreferenceValue("token"))
    private val lockOff = BackupPreference("use_biometric_lock", BooleanPreferenceValue(false))
    private val lockOn = BackupPreference("use_biometric_lock", BooleanPreferenceValue(true))

    private fun filtered(vararg preferences: BackupPreference, lockIsOn: Boolean = false): List<BackupPreference> =
        DesktopBackups.withSafePreferences(Backup(backupManga = emptyList(), backupPreferences = preferences.toList())) { lockIsOn }
            .backupPreferences

    @Test
    fun `app-state keys are dropped, settings and private settings stay`() {
        filtered(trusted, storage, theme, token) shouldBe listOf(theme, token)
    }

    @Test
    fun `a restore does not switch the app lock off`() {
        filtered(lockOff, lockIsOn = true) shouldBe emptyList()
        filtered(lockOff, lockIsOn = false) shouldBe listOf(lockOff)
        filtered(lockOn, lockIsOn = true) shouldBe listOf(lockOn)
    }

    @Test
    fun `source settings lose app-state keys too`() {
        val backup = Backup(
            backupManga = emptyList(),
            backupSourcePreferences = listOf(BackupSourcePreferences("source_1", listOf(trusted, theme))),
        )
        DesktopBackups.withSafePreferences(backup) { false }.backupSourcePreferences.single().prefs shouldBe listOf(theme)
    }

    @Test
    fun `source settings go only to source files, not the app's own settings`() {
        val backup = Backup(
            backupManga = emptyList(),
            backupSourcePreferences = listOf(
                BackupSourcePreferences("source_-42", listOf(theme)),
                BackupSourcePreferences("app.komikku.zoukan_preferences", listOf(lockOff)),
                BackupSourcePreferences("source_1_extra", listOf(theme)),
            ),
        )
        DesktopBackups.withSafePreferences(backup) { true }.backupSourcePreferences.map { it.sourceKey } shouldBe
            listOf("source_-42")
    }

    @Test
    fun `a backup that changes a known store's signing key needs the same confirmation as a new store`() {
        val known = ExtensionStore(
            indexUrl = "https://example.org/repo.json",
            name = "Example",
            badgeLabel = "Example",
            signingKey = "a".repeat(64),
            contact = ExtensionStore.Contact("https://example.org", null),
            isLegacy = true,
            extensionListUrl = null,
        )
        val repository = mockk<ExtensionStoreRepository> { coEvery { getAll() } returns listOf(known) }
        fun store(url: String, key: String, isLegacy: Boolean? = true) =
            BackupExtensionStore(url, "Example", null, key, "https://example.org", null, isLegacy, null)
        val same = store("https://example.org/repo.json", "a".repeat(64))
        // An old backup names the index, which the restore writes as repo.json
        val legacySame = store("https://example.org/index.min.json", "a".repeat(64), isLegacy = null)
        val newKey = store("https://example.org/repo.json", "b".repeat(64))
        val newStore = store("https://other.example/repo.json", "a".repeat(64))
        val backup = Backup(backupManga = emptyList(), backupExtensionStores = listOf(same, legacySame, newKey, newStore))
        runBlocking {
            DesktopBackups(File("unused"), NoStubSources).newExtensionStores(backup, GetExtensionStores(repository)) shouldBe
                listOf(newKey, newStore)
        }
    }
}
