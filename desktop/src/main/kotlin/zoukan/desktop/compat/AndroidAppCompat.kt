// ZOUKAN -->
// What the Android app's own code (compiled into the desktop app, see app-shared.txt) takes from
// the Android app module or from Android itself: toasts, small string and date helpers.
@file:JvmName("AndroidAppCompat")

package eu.kanade.tachiyomi.util.system

import android.content.Context
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.i18n.stringResource
import zoukan.desktop.ui.common.Toasts

/** Android's toast: a message at the bottom of the main window. */
fun Context.toast(text: String?, duration: Int = 0) {
    text?.takeIf { it.isNotBlank() }?.let(Toasts::show)
}

fun Context.toast(resource: StringResource, duration: Int = 0) = toast(stringResource(resource), duration)
// ZOUKAN <--
