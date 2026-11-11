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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.sourcePreferences
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts

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
        val preferences = remember(source) {
            source?.let {
                runCatching {
                    val screen = PreferenceScreen(Injekt.get<Application>())
                    screen.sharedPreferences = it.sourcePreferences()
                    it.setupPreferenceScreen(screen)
                    screen.preferences.toList()
                }.onFailure { e -> Toasts.error("The source's settings failed to load", e) }.getOrNull()
            }
        }
        // Preference values are not Compose state; this redraws the list after a change
        var version by remember { mutableIntStateOf(0) }
        var dialog by remember { mutableStateOf<Preference?>(null) }

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(source?.let { "${it.name} settings" } ?: "Source settings")
            when {
                source == null -> EmptyScreen("This source is not installed or has no settings.")
                preferences.isNullOrEmpty() -> EmptyScreen("This source has no settings.")
                else -> LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp)) {
                    itemsIndexed(preferences.filter { it.visible }, key = { i, p -> p.key ?: "pref-$i" }) { _, pref ->
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

private fun save(pref: Preference, value: Any) {
    if (pref.callChangeListener(value)) pref.saveNewValue(value)
}

private fun summaryOf(pref: Preference): String? {
    val summary = pref.summary?.toString()
    if (pref is ListPreference) {
        val index = pref.findIndexOfValue(pref.currentValue as? String)
        val entry = pref.entries?.getOrNull(index)?.toString()
        return when {
            summary == null -> entry
            summary.contains("%s") -> summary.replace("%s", entry.orEmpty())
            else -> summary
        }
    }
    return summary
}

@Composable
private fun PreferenceItem(pref: Preference, @Suppress("UNUSED_PARAMETER") version: Int, onChanged: () -> Unit, onOpen: () -> Unit) {
    val title = pref.title?.toString().orEmpty()
    when (pref) {
        is TwoStatePreference -> SwitchSetting(
            title = title,
            subtitle = summaryOf(pref),
            checked = pref.currentValue as? Boolean ?: false,
            enabled = pref.isEnabled,
        ) {
            save(pref, it)
            onChanged()
        }
        is ListPreference, is MultiSelectListPreference, is EditTextPreference -> SettingRow(
            title = title,
            subtitle = when (pref) {
                is EditTextPreference -> summaryOf(pref) ?: (pref.currentValue as? String)
                is MultiSelectListPreference -> summaryOf(pref) ?: (pref.currentValue as? Set<*>)?.let { values ->
                    values.mapNotNull { v -> pref.entryValues?.indexOfFirst { it.toString() == v }?.let { pref.entries?.getOrNull(it) } }
                        .joinToString()
                }
                else -> summaryOf(pref)
            },
            enabled = pref.isEnabled,
            onClick = onOpen,
        )
        else -> SettingRow(title, summaryOf(pref), enabled = pref.isEnabled)
    }
}

@Composable
private fun PreferenceDialog(pref: Preference, onDismiss: () -> Unit, onSave: (Any) -> Unit) {
    val title = (pref as? DialogPreference)?.dialogTitle?.toString() ?: pref.title?.toString().orEmpty()
    when (pref) {
        is ListPreference -> {
            val entries = pref.entries.orEmpty()
            val values = pref.entryValues.orEmpty()
            val current = pref.currentValue as? String
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
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
                confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        is MultiSelectListPreference -> {
            val entries = pref.entries.orEmpty()
            val values = pref.entryValues.orEmpty()

            @Suppress("UNCHECKED_CAST")
            var chosen by remember { mutableStateOf((pref.currentValue as? Set<String>).orEmpty()) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
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
                confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text("Save") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        is EditTextPreference -> {
            var text by remember { mutableStateOf(pref.currentValue as? String ?: "") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column {
                        pref.dialogMessage?.let { Text(it.toString(), Modifier.padding(bottom = 8.dp)) }
                        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        else -> onDismiss()
    }
}
