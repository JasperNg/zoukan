// ZOUKAN -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.SharedPreferences
import tachiyomi.core.common.preference.PreferenceStore

/** A [PreferenceStore] over a source's own SharedPreferences, where its extension reads them. */
internal expect fun sourcePreferenceStore(sharedPreferences: SharedPreferences): PreferenceStore
// ZOUKAN <--
