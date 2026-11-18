package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okio.Buffer
import org.junit.jupiter.api.Test

/**
 * The `.tachibk` format as BackupCodec reads and writes it, at the edges the desktop's
 * BackupCodecTest does not reach: every JSON signature, the exact size cap, malformed protobuf,
 * fields from a newer version, and the typed preference values a backup carries.
 */
class BackupCodecFormatTest {
    @Test
    fun `each JSON start is reported as a JSON backup`() {
        listOf("{}", "{\"version\":2}", "{\n  \"version\": 2\n}").forEach { json ->
            shouldThrow<BackupCodec.InvalidBackupException> { BackupCodec.decode(Buffer().writeUtf8(json)) }
                .error shouldBe BackupCodec.Error.JSON
        }
    }

    @Test
    fun `a backup exactly at the size cap is read`() {
        val bytes = BackupCodec.encode(Backup(listOf(BackupManga(source = 1, url = "/m/1", title = "A"))))
        BackupCodec.decode(Buffer().write(bytes), maxBytes = bytes.size.toLong()).backupManga.single().title shouldBe "A"
        shouldThrow<BackupCodec.InvalidBackupException> {
            BackupCodec.decode(Buffer().write(bytes), maxBytes = bytes.size - 1L)
        }.error shouldBe BackupCodec.Error.UNKNOWN
    }

    @Test
    fun `malformed protobuf is an invalid backup, not a crash`() {
        val malformed = listOf(
            // Field 1 claims 5 bytes, 1 follows
            byteArrayOf(0x0a, 0x05, 0x01),
            // An unknown field (99) whose varint never ends
            byteArrayOf(0x98.toByte(), 0x06, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
            // The manga list (field 1) sent as a number
            byteArrayOf(0x08, 0x01),
            // Wire type 7 does not exist
            byteArrayOf(0x0f, 0x00),
        )
        malformed.forEach { bytes ->
            shouldThrow<BackupCodec.InvalidBackupException> { BackupCodec.decode(bytes) }.error shouldBe BackupCodec.Error.UNKNOWN
        }
    }

    @Test
    fun `fields from a newer version are skipped`() {
        val bytes = BackupCodec.encode(Backup(listOf(BackupManga(source = 1, url = "/m/1"))))
        // Field 999 (varint 1) and field 998 (a short string), which this version does not know
        val newer = bytes + byteArrayOf(0xb8.toByte(), 0x3e, 0x01) + byteArrayOf(0xb2.toByte(), 0x3e, 0x02, 0x68, 0x69)
        BackupCodec.decode(newer).backupManga.single().url shouldBe "/m/1"
    }

    @Test
    fun `typed preferences, source settings, categories and stores survive a write and a read`() {
        val prefs = listOf(
            BackupPreference("int", IntPreferenceValue(-3)),
            BackupPreference("long", LongPreferenceValue(Long.MAX_VALUE)),
            BackupPreference("float", FloatPreferenceValue(0.25f)),
            BackupPreference("text", StringPreferenceValue("")),
            BackupPreference("bool", BooleanPreferenceValue(false)),
            BackupPreference("set", StringSetPreferenceValue(setOf("a", "b"))),
        )
        val backup = Backup(
            backupManga = emptyList(),
            backupCategories = listOf(BackupCategory(name = "Reading", order = 2, id = 5, flags = 64, hidden = true)),
            backupPreferences = prefs,
            backupSourcePreferences = listOf(BackupSourcePreferences("source_1", prefs.take(2))),
            backupExtensionStores = listOf(
                BackupExtensionStore(
                    indexUrl = "https://store.example/index.min.json",
                    name = "Store",
                    badgeLabel = null,
                    signingKey = "ab12",
                    contactWebsite = "https://store.example",
                    contactDiscord = null,
                    isLegacy = null,
                    extensionListUrl = null,
                ),
            ),
        )
        val sink = Buffer()
        BackupCodec.write(backup, sink)
        val read = BackupCodec.decode(sink)

        read.backupPreferences shouldBe prefs
        read.backupSourcePreferences shouldBe backup.backupSourcePreferences
        read.backupCategories.single().let {
            listOf(it.name, it.order, it.id, it.flags, it.hidden) shouldBe listOf("Reading", 2L, 5L, 64L, true)
        }
        // A null isLegacy marks a store from before the extension store format (see ExtensionStoreRestorer)
        read.backupExtensionStores.single().let {
            listOf(it.indexUrl, it.badgeLabel, it.isLegacy, it.contactDiscord) shouldBe
                listOf("https://store.example/index.min.json", null, null, null)
        }
    }
}
