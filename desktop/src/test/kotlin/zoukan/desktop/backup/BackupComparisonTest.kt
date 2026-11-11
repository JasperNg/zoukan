package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import okio.Buffer
import org.junit.jupiter.api.Test

/** The comparison finds what a round trip loses, so a passing round trip means something. */
class BackupComparisonTest {

    // A copy that shares nothing with the original
    private fun copy() = SyntheticBackup.create()

    @Test
    fun `a backup equals itself`() {
        BackupComparison(SyntheticBackup.create(), copy()).compare().differences.shouldBeEmpty()
    }

    @Test
    fun `a missing entry, chapter or category is a difference`() {
        val after = copy().let { it.copy(backupManga = it.backupManga.drop(1)) }
        after.backupManga.first().chapters = emptyList()
        after.backupCategories = after.backupCategories.drop(1)
        val differences = BackupComparison(SyntheticBackup.create(), after).compare().differences
        differences.any { "Synthetic Dex Title" in it && "the entry is missing" in it } shouldBe true
        differences.any { "chapter /chapters/123-1: the chapter is missing" in it } shouldBe true
        differences.any { "Category \"Reading\": the category is missing" in it } shouldBe true
    }

    @Test
    fun `a changed field is a difference`() {
        val after = copy()
        after.backupManga[0].chapters[0].read = false
        after.backupManga[0].customTitle = null
        after.backupManga[0].tracking[0].score = 1f
        val differences = BackupComparison(SyntheticBackup.create(), after).compare().differences
        differences shouldHaveSize 3
    }

    @Test
    fun `a setting that changed type is a difference`() {
        val after = copy()
        after.backupPreferences = after.backupPreferences.map {
            if (it.key == "pref_library_columns_portrait_key") BackupPreference(it.key, StringPreferenceValue("4")) else it
        }
        BackupComparison(SyntheticBackup.create(), after).compare().differences shouldHaveSize 1
    }

    @Test
    fun `category ids are compared by name`() {
        val after = copy()
        // A restore gives categories new ids; the default category follows its name
        after.backupCategories.forEach { it.id += 100 }
        after.backupPreferences = after.backupPreferences.map {
            when (it.key) {
                "default_category" -> BackupPreference(it.key, IntPreferenceValue(112))
                "library_update_categories" -> BackupPreference(
                    it.key,
                    eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue(setOf("111", "113")),
                )
                else -> it
            }
        }
        BackupComparison(SyntheticBackup.create(), after).compare().differences.shouldBeEmpty()
    }

    @Test
    fun `reports what it compared`() {
        val report = BackupComparison(SyntheticBackup.create(), copy()).compare()
        report.compared.keys shouldContain "entries"
        report.compared.keys shouldContain "tracking"
        report.compared.keys shouldContain "source settings"
    }

    @Test
    fun `the synthetic backup survives encoding`() {
        val buffer = Buffer()
        BackupCodec.write(SyntheticBackup.create(), buffer)
        val decoded = BackupCodec.decode(buffer)
        BackupComparison(SyntheticBackup.create(), decoded).compare().differences.shouldBeEmpty()
    }
}
