package zoukan.desktop.testkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * An in-memory [PreferenceStore] backed by a [MutableStateFlow], so writes, deletes,
 * [getAll], [getStringSet], and [Preference.changes] behave consistently across reads.
 */
class TestPreferenceStore(
    initial: Map<String, Any?> = emptyMap(),
) : PreferenceStore {
    private val values = MutableStateFlow(initial)

    private inner class MapPreference<T, S>(
        private val key: String,
        private val default: T,
        private val encode: (T) -> S,
        private val decode: (S) -> T,
    ) : Preference<T> {
        override fun key(): String = key

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = if (key in values.value) decode(values.value[key] as S) else default

        override fun set(value: T) {
            values.update { it + (key to encode(value)) }
        }

        override fun isSet(): Boolean = key in values.value

        override fun delete() {
            values.update { it - key }
        }

        override fun defaultValue(): T = default

        override fun changes(): Flow<T> = values.map { get() }.distinctUntilChanged()

        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())
    }

    private fun <T> direct(key: String, defaultValue: T): Preference<T> =
        MapPreference(key, defaultValue, { it }, { it })

    override fun getString(key: String, defaultValue: String): Preference<String> = direct(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = direct(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = direct(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = direct(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = direct(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        direct(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = MapPreference(key, defaultValue, serializer, deserializer)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = MapPreference(key, defaultValue, serializer, deserializer)

    override fun getAll(): Map<String, *> = values.value
}
