package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
import okio.Buffer
import okio.sink
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.testing.TestHome
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The backup with every optional field of the format set: [SyntheticBackup] (which the
 * `zoukan backup synthetic` command also writes) plus the fields it leaves at their defaults, a
 * chapter's `lastModifiedAt` and `version`. Built from the shared backup model classes, so it stays
 * in step with the format; `SyntheticBackupCoverageTest` fails when a field is added to the models
 * and set in neither.
 */
object FullFieldBackup {
    fun create(legacy: Boolean = false): Backup = SyntheticBackup.create(legacy).apply {
        backupManga.flatMap { it.chapters }.forEachIndexed { i, chapter ->
            if (chapter.lastModifiedAt == 0L) chapter.lastModifiedAt = 1_700_010_000L + i
            if (chapter.version == 0L) chapter.version = i + 1L
        }
    }
}

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
                    StringSetPreferenceValue(setOf("111", "113")),
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

/**
 * The Phase 3 gate: every fixture survives a restore and backup on desktop with no field lost.
 *
 * Fixtures are the synthetic full-field backups ([FullFieldBackup], checked to set every field of
 * the format by `SyntheticBackupCoverageTest`) plus every `.tachibk` and `.proto.gz` in
 * `src/test/resources/backups/` (real exports from Komikku and other apps): a backup from another
 * Android version is tested by dropping it into that folder. Each runs
 * `zoukan backup check` in its own JVM, because the desktop runtime starts once per process. The
 * Markdown reports go to `build/reports/backup/`.
 */
class BackupRoundTripTest {

    @TempDir
    lateinit var temp: File

    @TestFactory
    fun `fixtures round-trip with no field lost`(): List<DynamicTest> {
        val synthetic = listOf(false, true).map { legacy ->
            DynamicTest.dynamicTest(if (legacy) "synthetic, legacy fields" else "synthetic, every field") {
                val file = File(temp, if (legacy) "synthetic-legacy.tachibk" else "synthetic.tachibk")
                file.sink().use { BackupCodec.write(FullFieldBackup.create(legacy), it) }
                check(file)
            }
        }
        val real = fixtureDir.listFiles { f -> f.name.endsWith(".tachibk") || f.name.endsWith(".proto.gz") }
            .orEmpty()
            .sortedBy { it.name }
            .map { file -> DynamicTest.dynamicTest(file.name) { check(file) } }
        return synthetic + real
    }

    private fun check(file: File) {
        val reports = File("build/reports/backup").apply { mkdirs() }
        val report = File(reports, "${file.name.substringBefore('.')}.md")
        val java = File(System.getProperty("java.home"), "bin/java").path
        // The class path is longer than Windows' 32K command line, so it goes in an argument file.
        // Forward slashes, because a quoted argument file value treats backslashes as escapes.
        val classPath = System.getProperty("java.class.path").replace('\\', '/')
        val argFile = File(temp, "classpath.args").apply { writeText("-cp \"$classPath\"\n") }
        // The test home and data folder, so the app does not fall back to the real ones
        val process = ProcessBuilder(
            listOf(java, "-Djava.awt.headless=true") + TestHome.childJvmArguments() + listOf(
                "@${argFile.absolutePath}",
                "zoukan.desktop.ToolsMainKt",
                "backup",
                "check",
                file.absolutePath,
                "--report",
                report.absolutePath,
            ),
        )
            .redirectErrorStream(true)
            .redirectOutput(File(temp, "${file.name}.log"))
            .start()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        val log = File(temp, "${file.name}.log").readText()
        if (!finished || process.exitValue() != 0) println(log)
        finished shouldBe true
        process.exitValue() shouldBe 0
    }

    private val fixtureDir: File
        get() = File(javaClass.getResource("/backups/README.md")!!.toURI()).parentFile
}
