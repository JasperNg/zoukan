package tachiyomi.core.common.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

// ZOUKAN -->
/** The Android PreferenceStore: the shared store on the app's default SharedPreferences. */
class AndroidPreferenceStore(
    context: Context,
    sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context),
) : SharedPreferencesStore(sharedPreferences)
// ZOUKAN <--
