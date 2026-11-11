// ZOUKAN -->
// androidx.core's SharedPreferences.edit { }, for the shared app code
@file:JvmName("SharedPreferencesCompat")

package androidx.core.content

import android.content.SharedPreferences

inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}
// ZOUKAN <--
