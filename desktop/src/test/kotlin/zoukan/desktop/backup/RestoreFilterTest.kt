package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

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
}
