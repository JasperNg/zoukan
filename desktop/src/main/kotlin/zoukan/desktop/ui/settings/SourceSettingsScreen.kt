package zoukan.desktop.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.sourcePreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * A source's own settings: the androidx.preference screen the extension builds, drawn in Compose.
 * The values go to the source's SharedPreferences (source_<id>), as on Android.
 */
data class SourceSettingsScreen(val sourceId: Long) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: ScreenKey = "source-settings-$sourceId/"

    @Composable
    override fun Content() {
        val source = remember { Injekt.get<SourceManager>().get(sourceId) as? ConfigurableSource }
        val screen = remember(source) {
            source?.let {
                runCatching {
                    PreferenceScreen(Injekt.get<Application>()).also { screen ->
                        screen.sharedPreferences = it.sourcePreferences()
                        it.setupPreferenceScreen(screen)
                    }
                }.onFailure { e -> Toasts.error(localized(DMR.strings.the_sources_settings_failed_to_load), e) }.getOrNull()
            }
        }
        // Preference values are not Compose state; this redraws the list after a change
        var version by remember { mutableIntStateOf(0) }
        var dialog by remember { mutableStateOf<Preference?>(null) }
        // Again after each change: a change listener may show or hide preferences
        val rows = remember(screen, version) { screen?.let(::flattenPreferences) }

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(source?.let { localized(DMR.strings.settings, it.name) } ?: localized(MR.strings.source_settings))
            when {
                source == null -> EmptyScreen(localized(DMR.strings.this_source_is_not_installed_or))
                rows == null || rows.none { it !is PreferenceGroup } -> EmptyScreen(localized(DMR.strings.this_source_has_no_settings))
                else -> LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp)) {
                    // Index-qualified: an extension may give two preferences the same key, and a
                    // repeated LazyColumn key crashes the window
                    itemsIndexed(rows, key = { i, p -> "$i:${p.key}" }) { _, pref ->
                        PreferenceItem(pref, version, onChanged = { version++ }, onOpen = { dialog = pref })
                    }
                }
            }
        }

        dialog?.let { pref ->
            PreferenceDialog(pref, onDismiss = { dialog = null }, onSave = { value ->
                save(pref, value)
                version++
                dialog = null
            })
        }
    }
}

/**
 * The rows of [group], as Android lists a screen: a category (or a nested screen) is its title row,
 * followed by its own preferences. Hidden preferences, and everything in a hidden group, are left out.
 */
internal fun flattenPreferences(group: PreferenceGroup): List<Preference> =
    group.preferences.toList().flatMap { pref ->
        when {
            !readSafely(pref, false) { pref.isVisible } -> emptyList()
            pref is PreferenceGroup -> listOf(pref) + flattenPreferences(pref)
            else -> listOf(pref)
        }
    }

// The extension's code runs in the window: its change listener, a stored value of another type
// (ClassCastException) or a summary that throws must show an error, never end the app (an uncaught
// exception in the window is fatal)
private fun save(pref: Preference, value: Any) {
    try {
        if (pref.callChangeListener(value)) pref.saveNewValue(value)
    } catch (e: Exception) {
        reportFailure(pref, e)
    } catch (e: LinkageError) {
        reportFailure(pref, e)
    }
}

private fun reportFailure(pref: Preference, e: Throwable) {
    pref.logcat(LogPriority.ERROR, e) { "Source preference ${pref.key} failed" }
    // Not readSafely: that reports failures through here, and the title getter may be the one that throws
    Toasts.error(runCatching { pref.title?.toString() }.getOrNull().orEmpty().ifEmpty { pref.key.orEmpty() }, e)
}

private fun titleOf(pref: Preference): String = readSafely(pref, "") { pref.title?.toString().orEmpty() }

// Reads run at every redraw: a preference whose reads fail is reported once, not at each redraw
private val failedReads: MutableSet<Preference> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

/** [read] of an extension's preference, or [fallback] (logged and shown once) when it throws. */
private inline fun <T> readSafely(pref: Preference, fallback: T, read: () -> T): T =
    try {
        read()
    } catch (e: Exception) {
        readFailed(pref, e)
        fallback
    } catch (e: LinkageError) {
        readFailed(pref, e)
        fallback
    }

private fun readFailed(pref: Preference, e: Throwable) {
    if (failedReads.add(pref)) reportFailure(pref, e) else pref.logcat(LogPriority.DEBUG) { "Source preference ${pref.key} still fails: $e" }
}

private fun summaryOf(pref: Preference): String? = readSafely(pref, null) {
    val summary = pref.summary?.toString()
    if (pref is ListPreference) {
        val index = pref.findIndexOfValue(pref.currentValue as? String)
        val entry = pref.entries?.getOrNull(index)?.toString()
        return@readSafely when {
            summary == null -> entry
            summary.contains("%s") -> summary.replace("%s", entry.orEmpty())
            else -> summary
        }
    }
    summary
}

// Never skipped: the preference values are not Compose state, so a change (which bumps
// [version]) must re-read them. Compose ignores unused parameters when it decides to skip a
// call, so with an unused version every row kept showing its first values.
@NonSkippableComposable
@Composable
internal fun PreferenceItem(pref: Preference, @Suppress("UNUSED_PARAMETER") version: Int, onChanged: () -> Unit, onOpen: () -> Unit) {
    val title = titleOf(pref)
    // As Android: off while the switch it depends on (setDependency) is off, or its group is disabled
    val enabled = readSafely(pref, true) { pref.isEnabled }
    when (pref) {
        // A category's title, as a section header; one without a title draws nothing
        is PreferenceGroup -> if (title.isNotBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        is SeekBarPreference -> SeekBarSetting(pref, title, enabled) { value ->
            save(pref, value)
            onChanged()
        }
        is TwoStatePreference -> SwitchSetting(
            title = title,
            subtitle = summaryOf(pref),
            checked = readSafely(pref, false) { pref.currentValue as? Boolean ?: false },
            enabled = enabled,
        ) {
            save(pref, it)
            onChanged()
        }
        is ListPreference, is MultiSelectListPreference, is EditTextPreference -> SettingRow(
            title = title,
            subtitle = when (pref) {
                // A password, token or key is never the summary: the extension's own summary, or "Not set"
                is EditTextPreference -> summaryOf(pref) ?: readSafely(pref, null) { pref.currentValue as? String }.let { value ->
                    when {
                        !looksSecret(pref.key, title) -> value
                        value.isNullOrEmpty() -> localized(DMR.strings.not_set)
                        else -> null
                    }
                }
                is MultiSelectListPreference -> summaryOf(pref) ?: readSafely(pref, null) {
                    (pref.currentValue as? Set<*>)?.let { values ->
                        values.mapNotNull { v -> pref.entryValues?.indexOfFirst { it.toString() == v }?.let { pref.entries?.getOrNull(it) } }
                            .joinToString()
                    }
                }
                else -> summaryOf(pref)
            },
            enabled = enabled,
            onClick = onOpen,
        )
        // A plain preference with a click listener is a button, as on Android: the click runs the
        // listener, which may change the summary or other preferences, so the list redraws after it
        else -> SettingRow(
            title = title,
            subtitle = summaryOf(pref),
            enabled = enabled,
            onClick = pref.onPreferenceClickListener?.let {
                {
                    runCatching { pref.performClick() }.onFailure { e -> reportFailure(pref, e) }
                    onChanged()
                }
            },
        )
    }
}

/** Android's SeekBarPreference: a slider from min to max in steps of its increment, saved when released. */
@Composable
private fun SeekBarSetting(pref: SeekBarPreference, title: String, enabled: Boolean, onSave: (Int) -> Unit) {
    val min = pref.min
    val max = pref.max.coerceAtLeast(min)
    val saved = readSafely(pref, min) { pref.currentValue as? Int ?: min }.coerceIn(min, max)
    var value by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    val increment = pref.seekBarIncrement.takeIf { it > 0 } ?: 1
    val summary = summaryOf(pref)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!summary.isNullOrBlank()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (pref.showSeekBarValue) {
                Text(
                    snapSeekBar(value, min, max, increment).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (max > min) {
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = {
                    val snapped = snapSeekBar(value, min, max, increment)
                    if (snapped != saved) onSave(snapped)
                },
                enabled = enabled && pref.isAdjustable,
                valueRange = min.toFloat()..max.toFloat(),
                steps = ((max - min) / increment - 1).coerceIn(0, 1000),
            )
        }
    }
}

/** [value] rounded to the nearest step of [increment] from [min], within [min]..[max]. */
internal fun snapSeekBar(value: Float, min: Int, max: Int, increment: Int): Int {
    val steps = ((value - min) / increment).roundToInt()
    return (min + steps * increment).coerceIn(min, max)
}

private val secretWords = listOf("pass", "secret", "token", "apikey", "api_key", "api key", "cookie", "hash", "auth")

/** Whether a text setting called [key] or [title] probably holds a password, token or key. */
internal fun looksSecret(key: String?, title: String?): Boolean =
    listOfNotNull(key, title).any { text -> secretWords.any { it in text.lowercase() } }

/** A single-line text field that shows bullets until the eye button is pressed. */
@Composable
internal fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value,
        onValueChange,
        modifier = modifier,
        singleLine = true,
        label = label,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = localized(if (revealed) DMR.strings.hide_value else DMR.strings.show_value),
                )
            }
        },
    )
}

@Composable
internal fun PreferenceDialog(pref: Preference, onDismiss: () -> Unit, onSave: (Any) -> Unit) {
    val title = readSafely(pref, null) { (pref as? DialogPreference)?.dialogTitle?.toString() } ?: titleOf(pref)
    when (pref) {
        is ListPreference -> {
            val entries = pref.entries.orEmpty()
            val values = pref.entryValues.orEmpty()
            val current = readSafely(pref, null) { pref.currentValue as? String }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(Modifier.verticalScrollWithBar()) {
                        entries.forEachIndexed { i, entry ->
                            val value = values.getOrNull(i)?.toString() ?: return@forEachIndexed
                            Row(
                                Modifier.fillMaxWidth().toggleable(value == current, onValueChange = { onSave(value) }).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.RadioButton(selected = value == current, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(entry.toString())
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
            )
        }
        is MultiSelectListPreference -> {
            val entries = pref.entries.orEmpty()
            val values = pref.entryValues.orEmpty()

            @Suppress("UNCHECKED_CAST")
            var chosen by remember { mutableStateOf(readSafely(pref, null) { pref.currentValue as? Set<String> }.orEmpty()) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(Modifier.verticalScrollWithBar()) {
                        entries.forEachIndexed { i, entry ->
                            val value = values.getOrNull(i)?.toString() ?: return@forEachIndexed
                            Row(
                                Modifier.fillMaxWidth().toggleable(value in chosen, onValueChange = { on ->
                                    chosen = if (on) chosen + value else chosen - value
                                }).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = value in chosen, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(entry.toString())
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text(localized(MR.strings.action_save)) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
            )
        }
        is EditTextPreference -> {
            var text by remember { mutableStateOf(readSafely(pref, null) { pref.currentValue as? String } ?: "") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column {
                        pref.dialogMessage?.let { Text(it.toString(), Modifier.padding(bottom = 8.dp)) }
                        if (looksSecret(pref.key, titleOf(pref))) {
                            SecretTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { onSave(text) }) { Text(localized(MR.strings.action_save)) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
            )
        }
        else -> LaunchedEffect(pref) { onDismiss() }
    }
}
