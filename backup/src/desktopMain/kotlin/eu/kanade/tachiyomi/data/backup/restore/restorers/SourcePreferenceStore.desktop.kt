// ZOUKAN -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.SharedPreferences
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore

internal actual fun sourcePreferenceStore(sharedPreferences: SharedPreferences): PreferenceStore =
    DesktopPreferenceStore(sharedPreferences)
// ZOUKAN <--
