package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.source.sourcePreferences
import exh.EXHMigrations
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PreferenceRestorer(
    // ZOUKAN -->
    /**
     * Runs after app settings are restored. The Android app reschedules its library update,
     * backup and app update jobs here.
     */
    private val onAppPreferencesRestored: () -> Unit = {},
    // ZOUKAN <--
    private val getCategories: GetCategories = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) {
    // ZOUKAN -->
    /** @return a message for each setting that was not restored. */
    // ZOUKAN <--
    suspend fun restoreApp(
        preferences: List<BackupPreference>,
        backupCategories: List<BackupCategory>?,
    ): List<String> {
        val problems = restorePreferences(
            preferences,
            preferenceStore,
            backupCategories,
        )

        // ZOUKAN -->
        onAppPreferencesRestored()
        return problems
        // ZOUKAN <--
    }

    // ZOUKAN -->
    /** @return a message for each setting that was not restored. */
    // ZOUKAN <--
    suspend fun restoreSource(preferences: List<BackupSourcePreferences>): List<String> {
        // ZOUKAN -->
        return preferences.flatMap {
            val sourcePrefs = sourcePreferenceStore(sourcePreferences(it.sourceKey))
            restorePreferences(it.prefs, sourcePrefs)
        }
        // ZOUKAN <--
    }

    private suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        backupCategories: List<BackupCategory>? = null,
    ): List<String> {
        // ZOUKAN -->
        val problems = mutableListOf<String>()
        // ZOUKAN <--
        val allCategories = if (backupCategories != null) getCategories.await() else emptyList()
        val categoriesByName = allCategories.associateBy { it.name }
        val backupCategoriesById = backupCategories?.associateBy { it.id.toString() }.orEmpty()
        val prefs = preferenceStore.getAll()
        toRestore.forEach { (key, value) ->
            try {
                when (value) {
                    is IntPreferenceValue -> {
                        // ZOUKAN -->
                        if (prefs[key] is Int? || prefs[key].parsesAs(String::toIntOrNull)) {
                            // ZOUKAN <--
                            val newValue = if (key == LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY) {
                                backupCategoriesById[value.value.toString()]
                                    ?.let { categoriesByName[it.name]?.id?.toInt() }
                            } else {
                                value.value
                            }

                            newValue?.let { preferenceStore.getInt(key).set(it) }
                            // ZOUKAN -->
                        } else {
                            problems += notRestored(key)
                            // ZOUKAN <--
                        }
                    }
                    is LongPreferenceValue -> {
                        // ZOUKAN -->
                        if (prefs[key] is Long? || prefs[key].parsesAs(String::toLongOrNull)) {
                            // ZOUKAN <--
                            preferenceStore.getLong(key).set(value.value)
                            // ZOUKAN -->
                        } else {
                            problems += notRestored(key)
                            // ZOUKAN <--
                        }
                    }
                    is FloatPreferenceValue -> {
                        // ZOUKAN -->
                        if (prefs[key] is Float? || prefs[key].parsesAs(String::toFloatOrNull)) {
                            // ZOUKAN <--
                            preferenceStore.getFloat(key).set(value.value)
                            // ZOUKAN -->
                        } else {
                            problems += notRestored(key)
                            // ZOUKAN <--
                        }
                    }
                    is StringPreferenceValue -> {
                        if (prefs[key] is String?) {
                            preferenceStore.getString(key).set(value.value)
                            // ZOUKAN -->
                        } else {
                            problems += notRestored(key)
                            // ZOUKAN <--
                        }
                    }
                    is BooleanPreferenceValue -> {
                        // ZOUKAN -->
                        if (prefs[key] is Boolean? || prefs[key].parsesAs(String::toBooleanStrictOrNull)) {
                            // ZOUKAN <--
                            preferenceStore.getBoolean(key).set(value.value)
                            // ZOUKAN -->
                        } else {
                            problems += notRestored(key)
                            // ZOUKAN <--
                        }
                    }
                    is StringSetPreferenceValue -> {
                        if (prefs[key] is Set<*>?) {
                            val restored = restoreCategoriesPreference(
                                key,
                                value.value,
                                preferenceStore,
                                backupCategoriesById,
                                categoriesByName,
                            )
                            if (!restored) {
                                // KMK -->
                                when (key) {
                                    /* ZOUKAN --> */PINNED_SOURCES_PREF_KEY/* ZOUKAN <-- */ ->
                                        restorePinnedSourcesPreference(
                                            key,
                                            value.value,
                                            preferenceStore,
                                        )
                                    else ->
                                        // KMK <--
                                        preferenceStore.getStringSet(key).set(value.value)
                                }
                            }
                            // ZOUKAN -->
                        } else {
                            problems += notRestored(key)
                            // ZOUKAN <--
                        }
                    }
                }
                // ZOUKAN -->
            } catch (e: CancellationException) {
                throw e
                // ZOUKAN <--
            } catch (e: Exception) {
                // KMK -->
                xLogE("Failed to restore preference <$key>", e)
                // KMK <--
                // ZOUKAN -->
                problems += "Setting $key was not restored: ${e.message}"
                // ZOUKAN <--
            }
        }
        // ZOUKAN -->
        return problems
        // ZOUKAN <--
    }

    // ZOUKAN -->
    private fun notRestored(key: String) = "Setting $key was not restored: it is stored as another type"

    /** A value saved as plain text by an older version, which reads as [parse]'s type. */
    private fun Any?.parsesAs(parse: (String) -> Any?): Boolean = this is String && parse(this) != null
    // ZOUKAN <--

    private fun restoreCategoriesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        backupCategoriesById: Map<String, BackupCategory>,
        categoriesByName: Map<String, Category>,
    ): Boolean {
        val categoryPreferences = LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys
        if (key !in categoryPreferences) return false

        val ids = value.mapNotNull {
            backupCategoriesById[it]?.name?.let { name ->
                categoriesByName[name]?.id?.toString()
            }
        }

        if (ids.isNotEmpty()) {
            preferenceStore.getStringSet(key) += ids
        }
        return true
    }

    private fun restorePinnedSourcesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
    ) {
        if (value.isEmpty()) return

        val valueToSet = EXHMigrations.migrateSourceIds(value)
        preferenceStore.getStringSet(key).set(valueToSet)
    }
}

// ZOUKAN -->
/** SourcePreferences.PINNED_SOURCES_PREF_KEY in the Android app; BackupUpstreamConstantsTest (app) checks they match. */
const val PINNED_SOURCES_PREF_KEY = "pinned_catalogues"
// ZOUKAN <--
