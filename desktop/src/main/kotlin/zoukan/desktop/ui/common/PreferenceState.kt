package zoukan.desktop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import tachiyomi.core.common.preference.Preference

/**
 * The preference's value as Compose state, as presentation-core's Preference.collectAsState on
 * Android. `changes()` builds a new flow on every call, so calling it during composition restarts
 * the collector on every recomposition; this remembers the flow instead.
 *
 * The flow is remembered by the preference's key and default, not by the object: the preference
 * stores build a new object on every call (`preferences.themeMode()`), and those have no equals.
 * A composable shows one preference store's preference in one place, so the key names it.
 */
@Composable
fun <T> Preference<T>.collectAsState(): State<T> {
    val flow = remember(key(), defaultValue()) { changes() }
    return flow.collectAsState(initial = get())
}
