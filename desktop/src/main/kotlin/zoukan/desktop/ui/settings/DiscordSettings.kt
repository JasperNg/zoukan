package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.discord.DiscordPresence
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR

/** Settings > Discord: Rich Presence, with the Android app's options. */
fun LazyListScope.discordSettings() {
    settingsGroup("Discord")
    item(key = "discord") { DiscordSettingsSection() }
}

@Composable
private fun DiscordSettingsSection() {
    val presence = remember { Injekt.get<DiscordPresence>() }
    val enabled by presence.enabled().collectAsState()
    val connected by produceState(presence.connected) {
        while (true) {
            value = presence.connected
            delay(2_000)
        }
    }
    val message by presence.customMessage().collectAsState()
    var messageDialog by remember { mutableStateOf(false) }
    var categoriesDialog by remember { mutableStateOf(false) }
    val buttons by presence.showButtons().collectAsState()

    Column {
        SwitchPreference(
            localized(KMR.strings.pref_discord_rpc),
            presence.enabled(),
            when {
                !enabled -> localized(DMR.strings.show_what_you_read_on_your)
                connected -> localized(DMR.strings.connected_to_discord)
                else -> localized(DMR.strings.waiting_for_the_discord_app)
            },
        )
        if (enabled) {
            SwitchPreference(localized(DMR.strings.hide_what_i_read), presence.hideReading(), localized(DMR.strings.show_only_that_zoukan_is_in))
            SettingRow(localized(DMR.strings.hide_these_categories), localized(DMR.strings.entries_in_them_are_not_shown), onClick = { categoriesDialog = true })
            SwitchPreference(localized(DMR.strings.show_progress), presence.showProgress(), localized(DMR.strings.the_chapter_being_read))
            SwitchPreference(localized(DMR.strings.use_chapter_titles), presence.useChapterTitles(), localized(DMR.strings.in_place_of_the_chapter_number))
            SwitchPreference(localized(DMR.strings.show_elapsed_time), presence.showTimestamp())
            SettingRow(localized(DMR.strings.custom_message), message.ifBlank { localized(MR.strings.none) }, onClick = { messageDialog = true })
            SwitchPreference(localized(DMR.strings.show_buttons), presence.showButtons())
            if (buttons) {
                SwitchPreference(localized(DMR.strings.download_button), presence.showDownloadButton())
                SwitchPreference(localized(DMR.strings.discord_server_button), presence.showDiscordButton())
            }
        }
    }

    if (messageDialog) {
        var text by remember { mutableStateOf(message) }
        AlertDialog(
            onDismissRequest = { messageDialog = false },
            title = { Text(localized(DMR.strings.custom_message)) },
            text = { OutlinedTextField(text, { text = it.take(128) }, singleLine = true, placeholder = { Text(localized(DMR.strings.shown_in_place_of_the_title)) }) },
            confirmButton = {
                TextButton(onClick = {
                    presence.customMessage().set(text.trim())
                    messageDialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { messageDialog = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
    if (categoriesDialog) HiddenCategoriesDialog(presence) { categoriesDialog = false }
}

@Composable
private fun HiddenCategoriesDialog(presence: DiscordPresence, onDismiss: () -> Unit) {
    val categories by produceState(emptyList<Category>()) { value = Injekt.get<GetCategories>().await() }
    var hidden by remember { mutableStateOf(presence.hiddenCategories().get()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.hide_these_categories)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                categories.forEach { category ->
                    val id = category.id.toString()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(id in hidden, onCheckedChange = { hidden = if (it) hidden + id else hidden - id })
                        Text(if (category.isSystemCategory) localized(MR.strings.label_default) else category.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                presence.hiddenCategories().set(hidden)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}
