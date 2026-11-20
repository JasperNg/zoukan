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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_cancel
import tachiyomi.i18n.action_ok
import tachiyomi.i18n.all
import tachiyomi.i18n.label_default
import tachiyomi.i18n.none
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.RowCheckbox
import zoukan.desktop.ui.common.RowRadioButton
import zoukan.desktop.ui.common.RowSwitch
import zoukan.desktop.ui.common.RowTriStateCheckbox
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.checkRow
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.radioRow
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.excluded
import zoukan.i18n.desktop.include_exclude
import kotlin.math.roundToInt

/** A group header. Its list key is [id]; give a group an id of its own when another on the page has its title. */
fun LazyListScope.settingsGroup(title: String, id: String = title) {
    item(key = "$GROUP_KEY_PREFIX$id", contentType = SettingsGroupHeader(title)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Spacing.SettingsRowInset, end = Spacing.SettingsRowInset, top = Spacing.SettingsGroupGap, bottom = 8.dp),
        )
    }
}

/** The colour of a setting's title: [ColorScheme.onSurface], or Material's disabled colour when the row is not [enabled]. */
internal fun settingTitleColor(enabled: Boolean, scheme: ColorScheme): Color =
    if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = DISABLED_ALPHA)

/** The colour of a setting's summary: [ColorScheme.onSurfaceVariant], at the disabled alpha when the row is not [enabled]. */
internal fun settingSubtitleColor(enabled: Boolean, scheme: ColorScheme): Color =
    if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)

/** Material's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    /** The state of a switch in [trailing]: the row then is the switch (one node with the title), and the switch has no handler of its own. */
    switched: Boolean? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            // clickable(enabled = false) also reports the row as disabled to accessibility tools
            .then(
                when {
                    onClick != null && switched != null ->
                        Modifier.toggleable(value = switched, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
                    onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
                    !enabled -> Modifier.semantics(mergeDescendants = true) { disabled() }
                    else -> Modifier
                },
            )
            .padding(horizontal = Spacing.SettingsRowInset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = settingTitleColor(enabled, MaterialTheme.colorScheme),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = settingSubtitleColor(enabled, MaterialTheme.colorScheme))
            }
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
fun SwitchSetting(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, enabled, onClick = { onChange(!checked) }, switched = checked) {
        RowSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun SwitchPreference(title: String, preference: Preference<Boolean>, subtitle: String? = null, enabled: Boolean = true) {
    val value by preference.collectAsState()
    SwitchSetting(title, subtitle, value, enabled) { preference.set(it) }
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
    SettingRow(title, subtitle ?: choiceSummary(value, choices), enabled, onClick = { open = true })
    // A dialog of choices, as Android's ListPreference
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScrollWithBar()) {
                    choices.forEach { (choice, label) ->
                        Row(
                            Modifier.fillMaxWidth().radioRow(choice == value) {
                                open = false
                                onChoose(choice)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RowRadioButton(selected = choice == value, onClick = null)
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
    val value by preference.collectAsState()
    ChoiceSetting(title, value, choices) { preference.set(it) }
}

@Composable
fun SliderPreference(title: String, preference: Preference<Int>, range: IntRange, label: (Int) -> String) {
    val saved by preference.collectAsState()
    var value by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.SettingsRowInset, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(value.roundToInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { preference.set(value.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = sliderSteps(range),
            // The title above names the slider for screen readers (and the button crawl)
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/** A switch for one value of a string set preference, such as one library update restriction. */
@Composable
fun SetMemberPreference(title: String, preference: Preference<Set<String>>, member: String, subtitle: String? = null) {
    val value by preference.collectAsState()
    SwitchSetting(title, subtitle, member in value) { on -> preference.set(withMember(value, member, on)) }
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
    val value by preference.collectAsState()
    var open by remember { mutableStateOf(false) }
    val summary = subtitle ?: multiSelectSummary(value, choices)
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
                            Modifier.fillMaxWidth().checkRow(key in chosen) { chosen = if (key in chosen) chosen - key else chosen + key }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RowCheckbox(checked = key in chosen, onCheckedChange = null)
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
                }) { Text(localized(MR.strings.action_ok)) }
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
    val included by include.collectAsState()
    val excluded by exclude.collectAsState()
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
                    val state = categoryState(id, inc, exc)
                    Row(
                        Modifier.fillMaxWidth().triStateToggleable(state = state, role = Role.Checkbox) {
                            val (nextInc, nextExc) = cycleCategory(state, id, inc, exc)
                            inc = nextInc
                            exc = nextExc
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RowTriStateCheckbox(state = state, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(categoryName(category))
                        if (state == ToggleableState.Indeterminate) {
                            Text("  " + localized(DMR.strings.excluded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(inc, exc) }) { Text(localized(MR.strings.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** The summary of a choice setting: the label of [value], or null when it is none of [choices]. */
internal fun <T> choiceSummary(value: T, choices: List<Pair<T, String>>): String? = choices.firstOrNull { it.first == value }?.second

/** The summary of a multi-select setting: the labels of the chosen [choices] in their order, or "None". */
internal fun multiSelectSummary(value: Set<String>, choices: List<Pair<String, String>>): String =
    choices.filter { it.first in value }.joinToString { it.second }.ifEmpty { localized(MR.strings.none) }

/** [value] with [member] added ([on]) or removed; other members stay. */
internal fun withMember(value: Set<String>, member: String, on: Boolean): Set<String> = if (on) value + member else value - member

/** The slider's steps between its ends: one stop per whole number of [range]. */
internal fun sliderSteps(range: IntRange): Int = (range.last - range.first - 1).coerceAtLeast(0)

/** A category's box in the include/exclude dialog: included is checked, excluded is indeterminate. */
internal fun categoryState(id: String, included: Set<String>, excluded: Set<String>): ToggleableState = when (id) {
    in included -> ToggleableState.On
    in excluded -> ToggleableState.Indeterminate
    else -> ToggleableState.Off
}

/**
 * A click on category [id], whose box shows [state]: neither, then included, then excluded, then
 * neither again. Returns the new (included, excluded).
 */
internal fun cycleCategory(state: ToggleableState, id: String, included: Set<String>, excluded: Set<String>): Pair<Set<String>, Set<String>> =
    when (state) {
        ToggleableState.Off -> (included + id) to excluded
        ToggleableState.On -> (included - id) to (excluded + id)
        ToggleableState.Indeterminate -> included to (excluded - id)
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
