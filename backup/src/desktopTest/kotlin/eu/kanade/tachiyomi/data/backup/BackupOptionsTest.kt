package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import exh.EXHMigrations
import exh.source.EH_OLD_ID
import exh.source.EH_SOURCE_ID
import exh.source.EXH_OLD_ID
import exh.source.EXH_SOURCE_ID
import exh.source.NHENTAI_OLD_ID
import exh.source.NHENTAI_SOURCE_ID
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The choices a backup is made and restored with, which both apps keep as a boolean array (the
 * Android app passes it to its jobs, the desktop saves it), and the store and source-ID rules a
 * restore applies.
 */
class BackupOptionsTest {
    private val noBackup = BackupOptions.fromBooleanArray(BooleanArray(12))
    private val noRestore = RestoreOptions.fromBooleanArray(BooleanArray(6))

    @Test
    fun `every backup choice survives the boolean array on its own`() {
        BackupOptions().asBooleanArray().size shouldBe 12
        (0 until 12).forEach { i ->
            val array = BooleanArray(12) { it == i }
            BackupOptions.fromBooleanArray(array).asBooleanArray().toList() shouldBe array.toList()
        }
        BackupOptions.fromBooleanArray(BackupOptions().asBooleanArray()) shouldBe BackupOptions()
    }

    @Test
    fun `every restore choice survives the boolean array on its own`() {
        RestoreOptions().asBooleanArray().size shouldBe 6
        (0 until 6).forEach { i ->
            val array = BooleanArray(6) { it == i }
            RestoreOptions.fromBooleanArray(array).asBooleanArray().toList() shouldBe array.toList()
        }
    }

    @Test
    fun `each listed choice sets only its own flag`() {
        (BackupOptions.libraryOptions + BackupOptions.settingsOptions).forEach { entry ->
            val set = entry.setter(noBackup, true)
            entry.getter(set) shouldBe true
            set.asBooleanArray().count { it } shouldBe 1
        }
        RestoreOptions.options.forEach { entry ->
            val set = entry.setter(noRestore, true)
            entry.getter(set) shouldBe true
            set.asBooleanArray().count { it } shouldBe 1
        }
        // Every flag of the array has a choice on screen
        (BackupOptions.libraryOptions + BackupOptions.settingsOptions).size shouldBe 12
        RestoreOptions.options.size shouldBe 6
    }

    @Test
    fun `a backup or restore needs something of its own to save`() {
        noBackup.canCreate() shouldBe false
        // Parts of library entries and private settings make no backup on their own
        noBackup.copy(chapters = true, tracking = true, history = true, readEntries = true, customInfo = true, privateSettings = true)
            .canCreate() shouldBe false
        noBackup.copy(savedSearchesFeeds = true).canCreate() shouldBe true
        noRestore.canRestore() shouldBe false
        noRestore.copy(extensionStores = true).canRestore() shouldBe true
    }

    @Test
    fun `parts of library entries and private settings are offered only with what they belong to`() {
        // Without library entries only the entries themselves, categories, and saved searches and feeds
        BackupOptions.libraryOptions.count { it.enabled(noBackup) } shouldBe 3
        BackupOptions.libraryOptions.filter { it.enabled(noBackup) }.map { it.setter(noBackup, true) } shouldBe listOf(
            noBackup.copy(libraryEntries = true),
            noBackup.copy(categories = true),
            noBackup.copy(savedSearchesFeeds = true),
        )
        BackupOptions.libraryOptions.all { it.enabled(noBackup.copy(libraryEntries = true)) } shouldBe true

        val privateSettings = BackupOptions.settingsOptions.single { it.setter(noBackup, true).privateSettings }
        privateSettings.enabled(noBackup) shouldBe false
        privateSettings.enabled(noBackup.copy(appSettings = true)) shouldBe true
        privateSettings.enabled(noBackup.copy(sourceSettings = true)) shouldBe true
    }

    private fun store(indexUrl: String, isLegacy: Boolean?) = BackupExtensionStore(
        indexUrl = indexUrl,
        name = "Store",
        badgeLabel = null,
        signingKey = "ab12",
        contactWebsite = "https://store.example",
        contactDiscord = null,
        isLegacy = isLegacy,
        extensionListUrl = null,
    )

    @Test
    fun `a store from an old backup is restored at its repo json address`() {
        listOf(
            "https://store.example/index.min.json",
            "https://store.example/index.json",
            "https://store.example/repo.json",
            "https://store.example",
        ).forEach { ExtensionStoreRestorer.indexUrlOf(store(it, isLegacy = null)) shouldBe "https://store.example/repo.json" }
    }

    @Test
    fun `a store from a current backup keeps its address as it is`() {
        listOf(true, false).forEach { legacy ->
            ExtensionStoreRestorer.indexUrlOf(store("https://store.example/index.min.json", legacy)) shouldBe
                "https://store.example/index.min.json"
        }
    }

    @Test
    fun `old source IDs in a set move to the current ones, and others stay`() {
        EXHMigrations.migrateSourceIds(
            setOf("1", EH_OLD_ID.toString(), EXH_OLD_ID.toString(), NHENTAI_OLD_ID.toString()),
        ) shouldBe setOf("1", EH_SOURCE_ID.toString(), EXH_SOURCE_ID.toString(), NHENTAI_SOURCE_ID.toString())
        EXHMigrations.migrateSourceIds(emptySet()) shouldBe emptySet()
    }
}
