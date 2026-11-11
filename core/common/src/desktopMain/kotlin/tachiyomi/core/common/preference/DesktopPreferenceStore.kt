// ZOUKAN -->
package tachiyomi.core.common.preference

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import tachiyomi.core.common.preference.AndroidPreference.BooleanPrimitive
import tachiyomi.core.common.preference.AndroidPreference.FloatPrimitive
import tachiyomi.core.common.preference.AndroidPreference.IntPrimitive
import tachiyomi.core.common.preference.AndroidPreference.LongPrimitive
import tachiyomi.core.common.preference.AndroidPreference.ObjectAsInt
import tachiyomi.core.common.preference.AndroidPreference.ObjectAsString
import tachiyomi.core.common.preference.AndroidPreference.StringPrimitive
import tachiyomi.core.common.preference.AndroidPreference.StringSetPrimitive

/**
 * The desktop PreferenceStore: the same preferences and keys as AndroidPreferenceStore, on the
 * SharedPreferences of the Android compatibility layer (a file per preferences name).
 */
class DesktopPreferenceStore(
    private val sharedPreferences: SharedPreferences,
) : PreferenceStore {

    private val keyFlow = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key: String? -> trySend(key) }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override fun getString(key: String, defaultValue: String): Preference<String> =
        StringPrimitive(sharedPreferences, keyFlow, key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        LongPrimitive(sharedPreferences, keyFlow, key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        IntPrimitive(sharedPreferences, keyFlow, key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        FloatPrimitive(sharedPreferences, keyFlow, key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        BooleanPrimitive(sharedPreferences, keyFlow, key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        StringSetPrimitive(sharedPreferences, keyFlow, key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = ObjectAsString(sharedPreferences, keyFlow, key, defaultValue, serializer, deserializer)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = ObjectAsInt(sharedPreferences, keyFlow, key, defaultValue, serializer, deserializer)

    override fun getAll(): Map<String, *> = sharedPreferences.all ?: emptyMap<String, Any>()
}
// ZOUKAN <--
