package android.preference

import android.content.Context
import android.content.SharedPreferences

/**
 * Created by nulldev on 3/26/17.
 */

class PreferenceManager {
    companion object {
        // ZOUKAN -->
        /** The name Android's PreferenceManager used before (the package name alone), migrated once. */
        private const val LEGACY_DEFAULT_PACKAGE = "eu.kanade.tachiyomi"

        private val migrated = HashSet<String>()

        /** As Android: `<package name>_preferences`, the file the app's own settings are in too. */
        @JvmStatic
        fun getDefaultSharedPreferencesName(context: Context): String = context.packageName + "_preferences"

        @JvmStatic
        fun getDefaultSharedPreferencesMode(): Int = Context.MODE_PRIVATE

        @JvmStatic
        fun getDefaultSharedPreferences(context: Context): SharedPreferences {
            val name = getDefaultSharedPreferencesName(context)
            val preferences = context.getSharedPreferences(name, getDefaultSharedPreferencesMode())!!
            migrateLegacy(context, name, preferences)
            return preferences
        }

        /**
         * Earlier versions kept the default preferences under the package name alone
         * (`app.komikku`, or `eu.kanade.tachiyomi` before the package name was configured). Their
         * values move into the default file once, where a key is not set there already, and the old
         * file is deleted.
         */
        private fun migrateLegacy(
            context: Context,
            name: String,
            target: SharedPreferences,
        ) {
            synchronized(migrated) {
                // Per file, not per name: each data folder (or test) migrates its own
                if (!migrated.add(context.getSharedPreferencesPath(name)?.path ?: name)) return
                listOf(context.packageName, LEGACY_DEFAULT_PACKAGE)
                    .distinct()
                    .filter { it != name && context.getSharedPreferencesPath(it)?.exists() == true }
                    .forEach { legacyName ->
                        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
                        val existing = target.all.keys
                        val editor = target.edit()
                        legacy.all.forEach { (key, value) ->
                            if (key in existing) return@forEach
                            @Suppress("UNCHECKED_CAST")
                            when (value) {
                                is String -> editor.putString(key, value)
                                is Int -> editor.putInt(key, value)
                                is Long -> editor.putLong(key, value)
                                is Float -> editor.putFloat(key, value)
                                is Boolean -> editor.putBoolean(key, value)
                                is Set<*> -> editor.putStringSet(key, value as Set<String>)
                                is Double -> editor.putFloat(key, value.toFloat())
                                null -> {}
                                else -> editor.putString(key, value.toString())
                            }
                        }
                        if (editor.commit()) context.deleteSharedPreferences(legacyName)
                    }
            }
        }
        // ZOUKAN <--
    }
}
