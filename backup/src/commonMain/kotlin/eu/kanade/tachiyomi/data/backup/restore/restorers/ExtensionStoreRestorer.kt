package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionStoreRestorer(
    // ZOUKAN -->
    // Through the handler, not the Database: the restore runs this next to the other restorers,
    // and on desktop a write from the caller's thread uses its own connection, which makes a
    // concurrent restore transaction fail with SQLITE_BUSY
    private val handler: DatabaseHandler = Injekt.get(),
    // ZOUKAN <--
) {

    suspend operator fun invoke(
        backupStore: BackupExtensionStore,
    ) {
        // ZOUKAN -->
        val indexUrl = indexUrlOf(backupStore)
        handler.await {
            extension_storeQueries.upsert(
                // ZOUKAN <--
                indexUrl = indexUrl,
                name = backupStore.name,
                badgeLabel = backupStore.badgeLabel ?: backupStore.name,
                signingKey = backupStore.signingKey,
                contactWebsite = backupStore.contactWebsite,
                contactDiscord = backupStore.contactDiscord,
                isLegacy = backupStore.isLegacy ?: true,
                extensionListUrl = backupStore.extensionListUrl,
            )
        }
    }

    // ZOUKAN -->
    companion object {
        /** The index address a restore writes for [backupStore] (the desktop compares stores by it). */
        fun indexUrlOf(backupStore: BackupExtensionStore): String {
            // KMK -->
            return if (backupStore.isLegacy == null) {
                backupStore.indexUrl.removeSuffix("/index.min.json").removeSuffix("/index.json")
                    .removeSuffix("/repo.json") + "/repo.json"
            } else {
                backupStore.indexUrl
            }
            // KMK <--
        }
    }
    // ZOUKAN <--
}
