package zoukan.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR
import kotlin.math.roundToInt

fun LazyListScope.settingsGroup(title: String) {
    item(key = "$GROUP_KEY_PREFIX$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    // Settings search matches the title and the summary; in its results only matching rows show
    LocalSearchTexts.current?.add(title, subtitle)
    LocalSearchQuery.current?.let { if (!SettingsSearch.matches(it, listOfNotNull(title, subtitle))) return }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
fun SwitchSetting(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, enabled, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SwitchPreference(title: String, preference: Preference<Boolean>, subtitle: String? = null) {
    val value by preference.changes().collectAsState(preference.get())
    SwitchSetting(title, subtitle, value) { preference.set(it) }
}

@Composable
fun <T> ChoiceSetting(
    title: String,
    value: T,
    choices: List<Pair<T, String>>,
    subtitle: String? = null,
    enabled: Boolean = true,
    onChoose: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, subtitle ?: choices.firstOrNull { it.first == value }?.second, enabled, onClick = { open = true })
    // A dialog of choices, as Android's ListPreference
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScrollWithBar()) {
                    choices.forEach { (choice, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                open = false
                                onChoose(choice)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == value, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}

@Composable
fun <T> ChoicePreference(title: String, preference: Preference<T>, choices: List<Pair<T, String>>) {
    val value by preference.changes().collectAsState(preference.get())
    ChoiceSetting(title, value, choices) { preference.set(it) }
}

@Composable
fun SliderPreference(title: String, preference: Preference<Int>, range: IntRange, label: (Int) -> String) {
    LocalSearchTexts.current?.add(title)
    LocalSearchQuery.current?.let { if (!SettingsSearch.matches(it, listOf(title))) return }
    val saved by preference.changes().collectAsState(preference.get())
    var value by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(value.roundToInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { preference.set(value.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

/** A switch for one value of a string set preference, such as one library update restriction. */
@Composable
fun SetMemberPreference(title: String, preference: Preference<Set<String>>, member: String, subtitle: String? = null) {
    val value by preference.changes().collectAsState(preference.get())
    SwitchSetting(title, subtitle, member in value) { on -> preference.set(if (on) value + member else value - member) }
}

/** A setting whose value is a set of choices, picked in a dialog of checkboxes, as Android's MultiSelectListPreference. */
@Composable
fun MultiSelectPreference(
    title: String,
    preference: Preference<Set<String>>,
    choices: List<Pair<String, String>>,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val value by preference.changes().collectAsState(preference.get())
    var open by remember { mutableStateOf(false) }
    val summary = subtitle ?: choices.filter { it.first in value }.joinToString { it.second }.ifEmpty { localized(MR.strings.none) }
    SettingRow(title, summary, enabled, onClick = { open = true })
    if (open) {
        var chosen by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScrollWithBar()) {
                    choices.forEach { (key, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (key in chosen) chosen - key else chosen + key }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = key in chosen, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preference.set(chosen)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}

/**
 * Categories to include and exclude, as Android's TriStateListDialog: a click cycles a category
 * through included, excluded and neither. The summary reads as Android's getCategoriesLabel.
 */
@Composable
fun CategoriesIncludeExcludePreference(
    title: String,
    message: String,
    categories: List<Category>,
    include: Preference<Set<String>>,
    exclude: Preference<Set<String>>,
    enabled: Boolean = true,
) {
    val included by include.changes().collectAsState(include.get())
    val excluded by exclude.changes().collectAsState(exclude.get())
    var open by remember { mutableStateOf(false) }
    SettingRow(title, categoriesLabel(categories, included, excluded), enabled, onClick = { open = true })
    if (open) {
        CategoriesIncludeExcludeDialog(
            title = title,
            message = message,
            categories = categories,
            included = included,
            excluded = excluded,
            onDismiss = { open = false },
            onConfirm = { inc, exc ->
                include.set(inc)
                exclude.set(exc)
                open = false
            },
        )
    }
}

/** The dialog of [CategoriesIncludeExcludePreference], also the library's category filter (KMK). */
@Composable
fun CategoriesIncludeExcludeDialog(
    title: String,
    message: String,
    categories: List<Category>,
    included: Set<String>,
    excluded: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (included: Set<String>, excluded: Set<String>) -> Unit,
) {
    var inc by remember { mutableStateOf(included) }
    var exc by remember { mutableStateOf(excluded) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScrollWithBar()) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                categories.forEach { category ->
                    val id = category.id.toString()
                    val state = when (id) {
                        in inc -> ToggleableState.On
                        in exc -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            when (state) {
                                ToggleableState.Off -> inc = inc + id
                                ToggleableState.On -> {
                                    inc = inc - id
                                    exc = exc + id
                                }
                                ToggleableState.Indeterminate -> exc = exc - id
                            }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = state, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(categoryName(category))
                        if (state == ToggleableState.Indeterminate) {
                            Text("  " + localized(DMR.strings.excluded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(inc, exc) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** A category's name, with the system category shown as "Default", as Android's visualName. */
fun categoryName(category: Category): String = if (category.isSystemCategory) localized(MR.strings.label_default) else category.name

/** "Include: All\nExclude: None" and so on, as Android's getCategoriesLabel. */
fun categoriesLabel(categories: List<Category>, included: Set<String>, excluded: Set<String>): String {
    val inc = included.mapNotNull { id -> categories.find { it.id.toString() == id } }.sortedBy { it.order }
    val exc = excluded.mapNotNull { id -> categories.find { it.id.toString() == id } }.sortedBy { it.order }
    val allExcluded = exc.size == categories.size
    val includedText = when {
        inc.isNotEmpty() && inc.size != categories.size -> inc.joinToString(transform = ::categoryName)
        inc.size == categories.size -> localized(MR.strings.all)
        allExcluded -> localized(MR.strings.none)
        else -> localized(MR.strings.all)
    }
    val excludedText = when {
        exc.isEmpty() -> localized(MR.strings.none)
        allExcluded -> localized(MR.strings.all)
        else -> exc.joinToString(transform = ::categoryName)
    }
    return localized(DMR.strings.include_exclude, includedText, excludedText)
}
