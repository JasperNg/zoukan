package tachiyomi.core.common.preference

import android.content.SharedPreferences

/**
 * An in-memory SharedPreferences that behaves as Android's does where the store depends on it: a
 * typed getter throws ClassCastException for a value of another type, edits apply as a batch, and
 * listeners hear each changed key (and null for clear).
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = values.toMap()

    private inline fun <reified T> typed(key: String, default: T): T {
        val value = values[key] ?: return default
        return value as? T ?: throw ClassCastException("$key is a ${value::class.simpleName}")
    }

    override fun getString(key: String, defValue: String?): String? = typed(key, defValue)

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        typed<Set<*>?>(key, defValues) as Set<String>?

    override fun getInt(key: String, defValue: Int): Int = typed(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = typed(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = typed(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = typed(key, defValue)
    override fun contains(key: String): Boolean = key in values

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    val listenerCount: Int get() = listeners.size

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val puts = linkedMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) {
                values.clear()
                listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, null) }
            }
            puts.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
                listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
