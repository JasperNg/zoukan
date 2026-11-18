package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * [FullFieldBackup], the backup with every optional field set that `BackupRoundTripTest` restores and
 * backs up again, keeps up with the backup format: every field of every backup model class (and
 * every kind of preference value) is set to something other than its default in it, or in its
 * legacy variant. A field added to the shared backup models without a value there fails here, so
 * the round trip keeps covering the whole format.
 */
@OptIn(ExperimentalSerializationApi::class)
class SyntheticBackupCoverageTest {

    /** Without defaults, so a field equal to its default is left out and counts as not set. */
    private val json = Json { encodeDefaults = false }

    /** Fields set by class serial name; every class and sealed subclass reachable from [Backup]. */
    private class Coverage {
        val set = mutableMapOf<String, MutableSet<String>>()
        val classes = mutableMapOf<String, SerialDescriptor>()
    }

    private fun collectClasses(descriptor: SerialDescriptor, coverage: Coverage) {
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (coverage.classes.put(descriptor.serialName.removeSuffix("?"), descriptor) != null) return
                descriptor.elementDescriptors.forEach { collectClasses(it, coverage) }
            }
            StructureKind.LIST, StructureKind.MAP -> descriptor.elementDescriptors.forEach { collectClasses(it, coverage) }
            PolymorphicKind.SEALED -> descriptor.getElementDescriptor(1).elementDescriptors.forEach { collectClasses(it, coverage) }
            else -> Unit
        }
    }

    private fun visit(descriptor: SerialDescriptor, element: JsonElement, coverage: Coverage) {
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = element as? JsonObject ?: return
                val fields = coverage.set.getOrPut(descriptor.serialName.removeSuffix("?")) { mutableSetOf() }
                for ((name, value) in obj) {
                    val index = descriptor.getElementIndex(name)
                    fields += name
                    visit(descriptor.getElementDescriptor(index), value, coverage)
                }
            }
            StructureKind.LIST -> (element as? JsonArray)?.forEach { visit(descriptor.getElementDescriptor(0), it, coverage) }
            PolymorphicKind.SEALED -> {
                val obj = element as? JsonObject ?: return
                val type = obj.getValue("type").jsonPrimitive.content
                val subclass = descriptor.getElementDescriptor(1).elementDescriptors.first { it.serialName == type }
                visit(subclass, JsonObject(obj - "type"), coverage)
            }
            else -> Unit
        }
    }

    @Test
    fun `every field of the backup format is set in the synthetic backup`() {
        val coverage = Coverage()
        val root = Backup.serializer().descriptor
        collectClasses(root, coverage)
        for (legacy in listOf(false, true)) {
            visit(root, json.encodeToJsonElement(Backup.serializer(), FullFieldBackup.create(legacy)), coverage)
        }

        val missing = coverage.classes.flatMap { (name, descriptor) ->
            val set = coverage.set[name].orEmpty()
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.filter { it !in set }.map { "$name.$it" }
        }
        missing.shouldBeEmpty()
    }

    @Test
    fun `self-check - a field left at its default is reported`() {
        val coverage = Coverage()
        val root = Backup.serializer().descriptor
        collectClasses(root, coverage)
        val backup = FullFieldBackup.create().copy(backupFeeds = emptyList())
        visit(root, json.encodeToJsonElement(Backup.serializer(), backup), coverage)

        val missing = coverage.classes.flatMap { (name, descriptor) ->
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.filter { it !in coverage.set[name].orEmpty() }.map { "$name.$it" }
        }
        missing shouldContain "eu.kanade.tachiyomi.data.backup.models.Backup.backupFeeds"
    }
}
