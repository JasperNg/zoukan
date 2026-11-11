// ZOUKAN -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.app.Application
import android.content.SharedPreferences
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal actual fun sourcePreferenceStore(sharedPreferences: SharedPreferences): PreferenceStore =
    AndroidPreferenceStore(Injekt.get<Application>(), sharedPreferences)
// ZOUKAN <--
