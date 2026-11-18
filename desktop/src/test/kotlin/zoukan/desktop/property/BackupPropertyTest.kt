package zoukan.desktop.property

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.Buffer
import org.junit.jupiter.api.Test

/**
 * Any backup survives being written and read back unchanged, through the path the apps use for a
 * `.tachibk` file: [BackupCodec.write] (protobuf, then gzip) and [BackupCodec.decode].
 *
 * The two backups are compared as JSON with every default written out, so a field the protobuf
 * round trip drops, or a null that comes back as an empty value, shows as a difference at its path.
 */
class BackupPropertyTest {

    private val backups = arbFrom { BackupArbs(it).backup() }

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        allowSpecialFloatingPointValues = true
    }

    @Test
    fun `any backup survives write then read unchanged`() = runBlocking<Unit> {
        val found = Counterexamples<String>()
        checkAll(PropertyRuns.config(ITERATIONS), backups) { backup ->
            val file = Buffer().also { BackupCodec.write(backup, it) }
            val read = BackupCodec.decode(file)
            differences(tree(backup), tree(read), "backup").forEach { (path, detail) ->
                val kind = path.replace(Regex("""\[\d+]"""), "[]")
                // `an empty custom genre list survives write then read`: only an empty list read back as null
                val known = kind == "backup.backupManga[].customGenre" && detail == "was [], is null"
                found.add(if (known) "$kind (empty list read back as null)" else kind, "", detail)
            }
        }
        val unknown = found.kinds() - KNOWN_DEFECTS
        if (found.kinds().isNotEmpty()) println("Known backup round-trip defects seen:\n" + found.report(found.kinds() intersect KNOWN_DEFECTS))
        check(unknown.isEmpty()) { "Fields changed by a write and read (seed ${PropertyRuns.seed}):\n" + found.report(unknown) }
    }

    private fun tree(backup: Backup): JsonElement = json.encodeToJsonElement(Backup.serializer(), backup)

    /** Every path where [a] and [b] differ, with both values. */
    private fun differences(a: JsonElement, b: JsonElement, path: String): List<Pair<String, String>> = when {
        a is JsonObject && b is JsonObject -> (a.keys + b.keys).flatMap { key ->
            val x = a[key]
            val y = b[key]
            if (x == null || y == null) listOf("$path.$key" to "was ${x ?: "absent"}, is ${y ?: "absent"}") else differences(x, y, "$path.$key")
        }
        a is JsonArray && b is JsonArray && a.size == b.size -> a.indices.flatMap { differences(a[it], b[it], "$path[$it]") }
        a == b -> emptyList()
        else -> listOf(path to "was ${a.toString().take(200)}, is ${b.toString().take(200)}")
    }

    companion object {
        private const val ITERATIONS = 200

        /** Changes a round trip is known to make; each has a disabled test in [BackupKnownDefectsTest]. */
        val KNOWN_DEFECTS: Set<String> = setOf("backup.backupManga[].customGenre (empty list read back as null)")
    }
}
