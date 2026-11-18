package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import exh.source.EH_OLD_ID
import exh.source.EH_SOURCE_ID
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The restore rules of PreferenceRestorer for app settings: values go in only over a value of the
 * same type (Zoukan's change, so a restore cannot break a setting), category settings follow the
 * categories by name, pinned sources move to the current source IDs, and every problem is
 * reported. The desktop's RestoreFilterTest covers which keys a restore may write at all.
 */
class PreferenceRestorerTest {
    /** A PreferenceStore over a map, as SharedPreferences keeps typed values. */
    private class MapPreferenceStore(initial: Map<String, Any> = emptyMap()) : PreferenceStore {
        val values = initial.toMutableMap()

        /** Keys whose writes throw [failure]. */
        val failing = mutableMapOf<String, Throwable>()

        private inner class MapPreference<T : Any>(private val key: String, private val default: T) : Preference<T> {
            private val flow = MutableStateFlow(Unit)

            override fun key() = key

            @Suppress("UNCHECKED_CAST")
            override fun get(): T = values[key] as T? ?: default

            override fun set(value: T) {
                failing[key]?.let { throw it }
                values[key] = value
                flow.value = Unit
            }

            override fun isSet() = key in values

            override fun delete() {
                values.remove(key)
            }

            override fun defaultValue() = default

            override fun changes(): Flow<T> = flow.map { get() }

            override fun stateIn(scope: CoroutineScope): StateFlow<T> = throw UnsupportedOperationException()
        }

        override fun getString(key: String, defaultValue: String): Preference<String> = MapPreference(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long): Preference<Long> = MapPreference(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int): Preference<Int> = MapPreference(key, defaultValue)
        override fun getFloat(key: String, defaultValue: Float): Preference<Float> = MapPreference(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = MapPreference(key, defaultValue)
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
            MapPreference(key, defaultValue)

        override fun <T> getObjectFromString(
            key: String,
            defaultValue: T,
            serializer: (T) -> String,
            deserializer: (String) -> T,
        ): Preference<T> = throw UnsupportedOperationException()

        override fun <T> getObjectFromInt(
            key: String,
            defaultValue: T,
            serializer: (T) -> Int,
            deserializer: (Int) -> T,
        ): Preference<T> = throw UnsupportedOperationException()

        override fun getAll(): Map<String, *> = values.toMap()
    }

    private fun category(id: Long, name: String) = Category(id = id, name = name, order = id, flags = 0, hidden = false)

    /** On this device "Reading" is category 7 and "Done" category 8; in the backup they were 1 and 2. */
    private val localCategories = listOf(category(7, "Reading"), category(8, "Done"))
    private val backupCategories = listOf(
        BackupCategory(name = "Reading", id = 1),
        BackupCategory(name = "Done", id = 2),
        BackupCategory(name = "Gone", id = 3),
    )

    private fun restorer(store: PreferenceStore, onRestored: () -> Unit = {}) = PreferenceRestorer(
        onAppPreferencesRestored = onRestored,
        getCategories = mockk<GetCategories> { coEvery { await() } returns localCategories },
        preferenceStore = store,
    )

    @Test
    fun `values of every type are written where the key is new or already holds that type`() = runTest {
        val store = MapPreferenceStore(mapOf("reader_theme" to 1, "flag" to false))
        val problems = restorer(store).restoreApp(
            listOf(
                BackupPreference("reader_theme", IntPreferenceValue(3)),
                BackupPreference("flag", BooleanPreferenceValue(true)),
                BackupPreference("long", LongPreferenceValue(5L)),
                BackupPreference("float", FloatPreferenceValue(1.5f)),
                BackupPreference("text", StringPreferenceValue("x")),
                BackupPreference("set", StringSetPreferenceValue(setOf("a"))),
            ),
            null,
        )
        problems.shouldBeEmpty()
        store.values shouldBe mapOf(
            "reader_theme" to 3,
            "flag" to true,
            "long" to 5L,
            "float" to 1.5f,
            "text" to "x",
            "set" to setOf("a"),
        )
    }

    @Test
    fun `a text or set value is not written over a value of another type, and each is reported`() = runTest {
        val store = MapPreferenceStore(mapOf("text_now_int" to 4, "set_now_text" to "plain"))
        val problems = restorer(store).restoreApp(
            listOf(
                BackupPreference("text_now_int", StringPreferenceValue("x")),
                BackupPreference("set_now_text", StringSetPreferenceValue(setOf("a"))),
            ),
            null,
        )
        store.values shouldBe mapOf("text_now_int" to 4, "set_now_text" to "plain")
        problems.size shouldBe 2
        problems[0] shouldContain "text_now_int"
        problems[1] shouldContain "set_now_text"
    }

    @Test
    fun `the default category follows its name to this device's category, or is left alone`() = runTest {
        val key = LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY
        val store = MapPreferenceStore()
        restorer(store).restoreApp(listOf(BackupPreference(key, IntPreferenceValue(2))), backupCategories)
        store.values[key] shouldBe 8

        // A category this device does not have, and one the backup does not list
        val untouched = MapPreferenceStore(mapOf(key to 7))
        restorer(untouched).restoreApp(listOf(BackupPreference(key, IntPreferenceValue(3))), backupCategories)
        restorer(untouched).restoreApp(listOf(BackupPreference(key, IntPreferenceValue(42))), backupCategories)
        untouched.values[key] shouldBe 7
    }

    @Test
    fun `category sets follow the names and are added to what this device has`() = runTest {
        val key = LibraryPreferences.categoryPreferenceKeys.first { it != LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY }
        val store = MapPreferenceStore(mapOf(key to setOf("9")))
        restorer(store).restoreApp(listOf(BackupPreference(key, StringSetPreferenceValue(setOf("1", "3", "99")))), backupCategories)
        store.values[key] shouldBe setOf("9", "7")

        // Nothing that maps leaves the setting as it is
        val untouched = MapPreferenceStore(mapOf(key to setOf("9")))
        restorer(untouched).restoreApp(listOf(BackupPreference(key, StringSetPreferenceValue(setOf("3")))), backupCategories)
        untouched.values[key] shouldBe setOf("9")
    }

    @Test
    fun `pinned sources move to the current source IDs, and an empty set changes nothing`() = runTest {
        val store = MapPreferenceStore(mapOf(PINNED_SOURCES_PREF_KEY to setOf("123")))
        restorer(store).restoreApp(
            listOf(BackupPreference(PINNED_SOURCES_PREF_KEY, StringSetPreferenceValue(emptySet()))),
            null,
        )
        store.values[PINNED_SOURCES_PREF_KEY] shouldBe setOf("123")

        restorer(store).restoreApp(
            listOf(BackupPreference(PINNED_SOURCES_PREF_KEY, StringSetPreferenceValue(setOf("1", EH_OLD_ID.toString())))),
            null,
        )
        store.values[PINNED_SOURCES_PREF_KEY] shouldBe setOf("1", EH_SOURCE_ID.toString())
    }

    @Test
    fun `a failing setting is reported and the others are still restored, then the app hears of it`() = runTest {
        val store = MapPreferenceStore().apply { failing["bad"] = IllegalStateException("disk full") }
        var told = 0
        val problems = restorer(store) { told++ }.restoreApp(
            listOf(
                BackupPreference("bad", IntPreferenceValue(1)),
                BackupPreference("good", IntPreferenceValue(2)),
            ),
            null,
        )
        problems.single() shouldContain "disk full"
        store.values shouldBe mapOf("good" to 2)
        told shouldBe 1
    }

    @Test
    fun `a cancelled restore stops instead of being reported as a failed setting`() = runTest {
        val store = MapPreferenceStore().apply { failing["first"] = CancellationException("cancelled") }
        var told = 0
        shouldThrow<CancellationException> {
            restorer(store) { told++ }.restoreApp(
                listOf(
                    BackupPreference("first", IntPreferenceValue(1)),
                    BackupPreference("second", IntPreferenceValue(2)),
                ),
                null,
            )
        }
        store.values shouldBe emptyMap()
        told shouldBe 0
    }
}
