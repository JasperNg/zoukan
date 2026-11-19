package zoukan.desktop.property

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.metadata.BackupSearchTitle
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.manga.EditManga
import zoukan.desktop.secrets.FileCipher
import java.io.File
import java.util.Properties
import kotlin.random.Random

/**
 * Random backups in the shared format: entries with chapters, categories, history, tracking and
 * the fork's own fields (merged entries, metadata, custom info, saved searches, feeds, extension
 * stores), each optional field sometimes set and sometimes left out, numbers at their edges.
 *
 * Text is valid Unicode only: everything in a backup comes from SQLite or the preferences files,
 * which keep text as UTF-8 and cannot hold half a surrogate pair.
 */
class BackupArbs(private val r: Random) {

    fun backup(): Backup = Backup(
        backupManga = list(3) { manga() },
        backupCategories = list(3) { category() },
        backupSources = list(2) { BackupSource(name = text(), sourceId = long()) },
        backupPreferences = list(4) { preference() },
        backupSourcePreferences = list(2) { BackupSourcePreferences(text(), list(3) { preference() }) },
        backupExtensionStores = list(2) { extensionStore() },
        backupSavedSearches = list(2) { savedSearch() },
        backupFeeds = list(2) { BackupFeed(source = long(), global = r.nextBoolean(), savedSearch = maybe { savedSearch() }) },
    )

    private fun manga() = BackupManga(
        source = long(),
        url = text(),
        title = text(),
        artist = maybe { text() },
        author = maybe { text() },
        description = maybe { text() },
        genre = list(3) { text() },
        status = int(),
        thumbnailUrl = maybe { text() },
        dateAdded = long(),
        viewer = int(),
        chapters = list(4) { chapter() },
        categories = list(3) { long() },
        tracking = list(2) { tracking() },
        favorite = r.nextBoolean(),
        chapterFlags = int(),
        viewer_flags = maybe { int() },
        history = list(3) { BackupHistory(url = text(), lastRead = long(), readDuration = long()) },
        updateStrategy = UpdateStrategy.entries.random(r),
        lastModifiedAt = long(),
        favoriteModifiedAt = maybe { long() },
        excludedScanlators = list(2) { text() },
        version = long(),
        notes = text(),
        initialized = r.nextBoolean(),
        memo = bytes(),
        mergedMangaReferences = list(2) { mergedReference() },
        flatMetadata = maybe { flatMetadata() },
        customStatus = int(),
        customThumbnailUrl = maybe { text() },
        customTitle = maybe { text() },
        customArtist = maybe { text() },
        customAuthor = maybe { text() },
        customDescription = maybe { text() },
        customGenre = maybe { list(3) { text() } },
    )

    private fun chapter() = BackupChapter(
        url = text(),
        name = text(),
        scanlator = maybe { text() },
        read = r.nextBoolean(),
        bookmark = r.nextBoolean(),
        lastPageRead = long(),
        dateFetch = long(),
        dateUpload = long(),
        chapterNumber = float(),
        sourceOrder = long(),
        lastModifiedAt = long(),
        version = long(),
        memo = bytes(),
    )

    @Suppress("DEPRECATION")
    private fun tracking() = BackupTracking(
        syncId = int(),
        libraryId = long(),
        mediaIdInt = int(),
        trackingUrl = text(),
        title = text(),
        lastChapterRead = float(),
        totalChapters = int(),
        score = float(),
        status = int(),
        startedReadingDate = long(),
        finishedReadingDate = long(),
        private = r.nextBoolean(),
        mediaId = long(),
    )

    private fun category() = BackupCategory(name = text(), order = long(), id = long(), flags = long(), hidden = r.nextBoolean())

    private fun preference() = BackupPreference(key = text(), value = preferenceValue())

    private fun preferenceValue(): PreferenceValue = when (r.nextInt(6)) {
        0 -> IntPreferenceValue(int())
        1 -> LongPreferenceValue(long())
        2 -> FloatPreferenceValue(float())
        3 -> StringPreferenceValue(text())
        4 -> BooleanPreferenceValue(r.nextBoolean())
        else -> StringSetPreferenceValue(list(4) { text() }.toSet())
    }

    private fun extensionStore() = BackupExtensionStore(
        indexUrl = text(),
        name = text(),
        badgeLabel = maybe { text() },
        signingKey = text(),
        contactWebsite = text(),
        contactDiscord = maybe { text() },
        isLegacy = maybe { r.nextBoolean() },
        extensionListUrl = maybe { text() },
    )

    private fun savedSearch() = BackupSavedSearch(name = text(), query = text(), filterList = text(), source = long())

    private fun mergedReference() = BackupMergedMangaReference(
        isInfoManga = r.nextBoolean(),
        getChapterUpdates = r.nextBoolean(),
        chapterSortMode = int(),
        chapterPriority = int(),
        downloadChapters = r.nextBoolean(),
        mergeUrl = text(),
        mangaUrl = text(),
        mangaSourceId = long(),
    )

    private fun flatMetadata() = BackupFlatMetadata(
        searchMetadata = BackupSearchMetadata(uploader = maybe { text() }, extra = text(), indexedExtra = maybe { text() }, extraVersion = int()),
        searchTags = list(3) { BackupSearchTag(namespace = maybe { text() }, name = text(), type = int()) },
        searchTitles = list(2) { BackupSearchTitle(title = text(), type = int()) },
    )

    private fun text(): String = NastyStrings.next(r, allowLoneSurrogates = false, maxPieces = 4, padding = r.nextInt(10) == 0)

    private fun int(): Int = when (r.nextInt(6)) {
        0 -> 0
        1 -> Int.MIN_VALUE
        2 -> Int.MAX_VALUE
        3 -> -1
        else -> r.nextInt()
    }

    private fun long(): Long = when (r.nextInt(6)) {
        0 -> 0L
        1 -> Long.MIN_VALUE
        2 -> Long.MAX_VALUE
        3 -> -1L
        else -> r.nextLong()
    }

    private fun float(): Float = when (r.nextInt(8)) {
        0 -> 0f
        1 -> -0f
        2 -> Float.NaN
        3 -> Float.POSITIVE_INFINITY
        4 -> Float.MIN_VALUE
        5 -> Float.MAX_VALUE
        else -> r.nextFloat() * 2000 - 1000
    }

    private fun bytes(): ByteArray = when (r.nextInt(4)) {
        0 -> byteArrayOf()
        1 -> "{}".toByteArray()
        2 -> """{"note":"${NastyStrings.next(r, allowLoneSurrogates = false, maxPieces = 2, padding = false).replace("\\", "").replace("\"", "")}"}""".toByteArray()
        else -> r.nextBytes(r.nextInt(0, 40))
    }

    private fun <T> list(max: Int, item: () -> T): List<T> = List(r.nextInt(0, max + 1)) { item() }

    private fun <T> maybe(value: () -> T): T? = if (r.nextInt(3) == 0) null else value()
}

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
                // `an empty custom genre list is read back as no custom genre`: only an empty list read back as null
                val known = kind == "backup.backupManga[].customGenre" && detail == "was [], is null"
                found.add(if (known) "$kind (empty list read back as null)" else kind, "", detail)
            }
        }
        val unknown = found.kinds() - FORMAT_LIMITS
        if (found.kinds().isNotEmpty()) println("Backup format limits seen:\n" + found.report(found.kinds() intersect FORMAT_LIMITS))
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

        /** Changes the backup format cannot avoid; each is pinned in [BackupFormatLimitsTest]. */
        val FORMAT_LIMITS: Set<String> = setOf("backup.backupManga[].customGenre (empty list read back as null)")
    }
}

/**
 * Round-trip changes [BackupPropertyTest] found that the backup format (shared with the Android
 * app, so its field numbers and types cannot change) cannot avoid, pinned so that a change to them
 * is noticed. `desktop/PARITY.md` (the "Backups (.tachibk)" row) describes each.
 */
class BackupFormatLimitsTest {

    /**
     * Protobuf writes no field for an empty list, and `customGenre` (805) defaults to null, so an
     * empty custom genre list is read back as no custom genre. Neither app saves an empty one (the
     * next test), so a backup either app wrote loses nothing.
     */
    @Test
    fun `an empty custom genre list is read back as no custom genre`() {
        val backup = Backup(listOf(BackupManga(source = 1, url = "/m/1", customGenre = emptyList())))
        val file = Buffer().also { BackupCodec.write(backup, it) }
        BackupCodec.decode(file).backupManga.single().customGenre.shouldBeNull()
    }

    @Test
    fun `a non-empty custom genre list survives write then read`() {
        val backup = Backup(listOf(BackupManga(source = 1, url = "/m/1", customGenre = listOf("Custom"))))
        val file = Buffer().also { BackupCodec.write(backup, it) }
        BackupCodec.decode(file).backupManga.single().customGenre shouldBe listOf("Custom")
    }

    /** As Android's MangaScreenModel.updateMangaInfo: clearing every genre puts back the source's. */
    @Test
    fun `clearing every genre in the editor saves no custom genre`() = runBlocking {
        val saved = slot<CustomMangaInfo>()
        val setCustomMangaInfo = mockk<SetCustomMangaInfo> { every { set(capture(saved)) } returns Unit }
        val editManga = EditManga(setCustomMangaInfo, mockk { coEvery { await(any()) } returns true }, mockk())
        val manga = Manga.create().copy(id = 1, source = 1, url = "/m/1", ogTitle = "Title", ogGenre = listOf("Action"))

        editManga.saveInfo(manga, EditManga.Info("Title", "", "", "", genres = emptyList(), status = 0))

        saved.captured.genre.shouldBeNull()
    }
}

/**
 * Any preference value of every type the store has survives being written, saved to its file and
 * read back by a new store on that file, in a plain file and in a file encrypted with the file key
 * (as the app keeps them): keys and values with odd characters, empty strings and empty sets,
 * numbers at their edges.
 */
class PreferencePropertyTest {

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null
    private var previousCodec: SettingsFileCodec? = null
    private var file = 0

    @BeforeEach
    fun setRoot() {
        previousRoot = System.getProperty(property)
        previousCodec = SettingsFileCodec.current
        System.setProperty(property, root.absolutePath)
    }

    @OptIn(ForTestsOnly::class)
    @AfterEach
    fun restoreRoot() {
        JavaSharedPreferences.awaitPendingSaves()
        SettingsFileCodec.current = previousCodec
        previousRoot?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    /** A key, a value of one type, and how to make that type's preference in a store. */
    private class Case(val key: String, val value: Any, val type: String, val preference: (PreferenceStore, String) -> Preference<*>) {
        override fun toString() = "$type \"${escaped(key)}\" = ${if (value is String) "\"${escaped(value)}\"" else value}"
    }

    private data class Item(val id: Int, val label: String)

    private fun itemToString(item: Item) = "${item.id}|${item.label}"

    private fun itemFromString(s: String) = Item(s.substringBefore('|').toInt(), s.substringAfter('|'))

    /** Odd keys with plain values, or plain keys with odd values, so a failure says which of the two it is. */
    private fun cases(oddKeys: Boolean) = arbFrom { r ->
        val key = when {
            !oddKeys -> "pref_${r.nextInt(1_000_000)}"
            r.nextInt(6) == 0 -> listOf("pref_display_mode", "a.size", "a.0", "__zoukan_type__.x", "source_123_password", "a=b", "a:b", "#x", "!x", " spaced key ").random(r)
            else -> NastyStrings.next(r, maxPieces = 5, padding = false).ifEmpty { "k" }
        }
        val text = if (oddKeys) "value ${r.nextInt()}" else NastyStrings.next(r)
        fun texts() = List(r.nextInt(0, 5)) { if (oddKeys) "v${r.nextInt()}" else NastyStrings.next(r, maxPieces = 3) }.toSet()
        when (r.nextInt(8)) {
            0 -> Case(key, text, "String") { s, k -> s.getString(k, DEFAULT_STRING) }
            1 -> Case(key, listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, r.nextInt()).random(r), "Int") { s, k -> s.getInt(k, 7) }
            2 -> Case(key, listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, r.nextLong()).random(r), "Long") { s, k -> s.getLong(k, 7L) }
            3 -> Case(
                key,
                listOf(0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE, r.nextFloat() * 1e6f - 5e5f).random(r),
                "Float",
            ) { s, k -> s.getFloat(k, 7f) }
            4 -> Case(key, r.nextBoolean(), "Boolean") { s, k -> s.getBoolean(k, false) }
            5 -> Case(key, texts(), "Set<String>") { s, k -> s.getStringSet(k, setOf(DEFAULT_STRING)) }
            6 -> Case(key, Item(r.nextInt(), text.replace("|", "")), "Object") { s, k ->
                s.getObjectFromString(k, Item(0, DEFAULT_STRING), ::itemToString, ::itemFromString)
            }
            else -> Case(key, "", "String") { s, k -> s.getString(k, DEFAULT_STRING) }
        }
    }

    @Test
    fun `any value survives write then read in a plain file`() = roundTrips(codec = null, oddKeys = false)

    @Test
    fun `any value survives write then read in an encrypted file`() = roundTrips(codec = FileCipher(ByteArray(32) { 3 }), oddKeys = false)

    @Test
    fun `any key keeps its value in a plain file`() = roundTrips(codec = null, oddKeys = true)

    @Test
    fun `any key keeps its value in an encrypted file`() = roundTrips(codec = FileCipher(ByteArray(32) { 3 }), oddKeys = true)

    @OptIn(ForTestsOnly::class)
    private fun roundTrips(codec: SettingsFileCodec?, oddKeys: Boolean) = runBlocking<Unit> {
        SettingsFileCodec.current = codec
        val found = Counterexamples<String>()
        checkAll(PropertyRuns.config(ITERATIONS), cases(oddKeys)) { case ->
            val part = if (oddKeys) "key" else "value"
            val name = "property_${file++}"
            @Suppress("UNCHECKED_CAST")
            (case.preference(DesktopPreferenceStore(JavaSharedPreferences(name)), case.key) as Preference<Any>).set(case.value)
            JavaSharedPreferences.awaitPendingSaves()

            val stored = File(root, "settings/$name.xml").readBytes()
            if (codec != null && !codec.isEncoded(stored)) found.add("$part ${case.type}: file not encrypted", case.key, case.toString())

            val reopened = JavaSharedPreferences(name)
            val read = case.preference(DesktopPreferenceStore(reopened), case.key).get()
            // Boxed equality: NaN equals NaN, -0.0 does not equal 0.0
            if ((read as Any?) != case.value) found.add(kindOfChange(part, case), case.key, "$case, read back ${show(read)}")
            if (File(root, "settings/$name.xml.unreadable").exists()) found.add("$part ${case.type}: file unreadable", case.key, case.toString())
        }
        check(found.kinds().isEmpty()) { "Preferences that did not survive (seed ${PropertyRuns.seed}):\n" + found.report(found.kinds()) }
    }

    /** A changed value, by what is odd in the key or the value: the characters XML cannot keep as they are. */
    private fun kindOfChange(part: String, case: Case): String {
        val odd = if (part == "key") case.key else (case.value as? String) ?: (case.value as? Item)?.label ?: (case.value as? Set<*>)?.joinToString("") ?: ""
        val why = when {
            '\r' in odd -> "carriage return"
            '\t' in odd || '\n' in odd -> "tab or line break"
            PortableName.hasLoneSurrogate(odd) -> "lone surrogate"
            odd.any { it.code < 0x20 } -> "control character"
            else -> "other"
        }
        return "$part with a $why changed"
    }

    private fun show(value: Any?) = if (value is String) "\"${escaped(value)}\"" else value.toString()

    companion object {
        private const val ITERATIONS = 300
        private const val DEFAULT_STRING = "\u0000default"
    }
}

/**
 * Preferences [PreferencePropertyTest] found that did not survive a save and a reload, with the
 * smallest inputs found. The settings file is Java's Properties XML, which writes keys as XML
 * attributes and values as text without escaping these characters, so the XML parser normalised
 * them on reading; the save now writes them as character references (JavaSharedPreferences
 * `keepLineBreaks`).
 */
class PreferenceKnownDefectsTest {

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null

    @BeforeEach
    fun setRoot() {
        previousRoot = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
    }

    @AfterEach
    fun restoreRoot() {
        previousRoot?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    private fun reloaded(key: String, value: String): Map<String, *> {
        JavaSharedPreferences("known_defects").edit().putString(key, value).commit()
        return JavaSharedPreferences("known_defects").all
    }

    @Test
    fun `a key with a tab or a line break keeps its value`() {
        for (key in listOf("a\tb", "a\nb", "a\rb")) {
            reloaded(key, "v")[key] shouldBe "v"
        }
    }

    @Test
    fun `a carriage return in a value survives`() {
        reloaded("k", "a\rb")["k"] shouldBe "a\rb"
        reloaded("k", "a\r\nb")["k"] shouldBe "a\r\nb"
    }

    @Test
    fun `a key with a line break keeps its Set, its type and its neighbours`() {
        JavaSharedPreferences("neighbours").edit()
            .putStringSet("s\tet\r", setOf("a\rb", "c\r\n", "\r"))
            .putInt("i\nnt", 5)
            .putString("i nt", "plain")
            .putString("q\"<&>'\t", "<&>\"'\r]]>")
            .putString("&#13;\r", "&#13;&#10;&amp;\r")
            .putString("long", "x\r\ny\t".repeat(100_000))
            .commit()
        val all = JavaSharedPreferences("neighbours").all
        all shouldBe mapOf(
            "s\tet\r" to setOf("a\rb", "c\r\n", "\r"),
            "i\nnt" to 5,
            "i nt" to "plain",
            "q\"<&>'\t" to "<&>\"'\r]]>",
            "&#13;\r" to "&#13;&#10;&amp;\r",
            "long" to "x\r\ny\t".repeat(100_000),
        )
    }

    @OptIn(ForTestsOnly::class)
    @Test
    fun `an encrypted file keeps a carriage return in a key and a value`() {
        val previous = SettingsFileCodec.current
        try {
            SettingsFileCodec.current = FileCipher(ByteArray(32) { 3 })
            reloaded("a\rb\tc", "x\ry")["a\rb\tc"] shouldBe "x\ry"
        } finally {
            SettingsFileCodec.current = previous
        }
    }

    @Test
    fun `a file written now reads the same with plain Properties, as an older version reads it`() {
        JavaSharedPreferences("downgrade").edit().putString("a\tb\nc\rd", "x\ry\r\nz").commit()
        val properties = Properties()
        JavaSharedPreferences.fileFor("downgrade").inputStream().use { properties.loadFromXML(it) }
        properties.getProperty("a\tb\nc\rd") shouldBe "x\ry\r\nz"
    }

    @Test
    fun `a file written before the fix still reads as it did`() {
        // As older versions wrote it: raw line breaks, which the parser normalises as before
        val file = JavaSharedPreferences.fileFor("older")
        file.parentFile.mkdirs()
        file.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" +
                "<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">\n" +
                "<properties>\n<entry key=\"k\">a\r\nb&amp;#13;</entry>\n<entry key=\"e\"/>\n</properties>\n",
        )
        JavaSharedPreferences("older").all shouldBe mapOf("k" to "a\nb&#13;", "e" to "")
    }
}
