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
import androidx.compose.runtime.collectAsState
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.discord.DiscordPresence
import zoukan.desktop.ui.common.verticalScrollWithBar

/** Settings > Discord: Rich Presence, with the Android app's options. */
fun LazyListScope.discordSettings() {
    settingsGroup("Discord")
    item(key = "discord") { DiscordSettingsSection() }
}

@Composable
private fun DiscordSettingsSection() {
    val presence = remember { Injekt.get<DiscordPresence>() }
    val enabled by presence.enabled().changes().collectAsState(presence.enabled().get())
    val connected by produceState(presence.connected) {
        while (true) {
            value = presence.connected
            delay(2_000)
        }
    }
    val message by presence.customMessage().changes().collectAsState(presence.customMessage().get())
    var messageDialog by remember { mutableStateOf(false) }
    var categoriesDialog by remember { mutableStateOf(false) }
    val buttons by presence.showButtons().changes().collectAsState(presence.showButtons().get())

    Column {
        SwitchPreference(
            "Discord Rich Presence",
            presence.enabled(),
            when {
                !enabled -> "Show what you read on your Discord profile. Needs the Discord app running on this computer."
                connected -> "Connected to Discord"
                else -> "Waiting for the Discord app"
            },
        )
        if (enabled) {
            SwitchPreference("Hide what I read", presence.hideReading(), "Show only that Zoukan is in use")
            SettingRow("Hide these categories", "Entries in them are not shown", onClick = { categoriesDialog = true })
            SwitchPreference("Show progress", presence.showProgress(), "The chapter being read")
            SwitchPreference("Use chapter titles", presence.useChapterTitles(), "In place of the chapter number")
            SwitchPreference("Show elapsed time", presence.showTimestamp())
            SettingRow("Custom message", message.ifBlank { "None" }, onClick = { messageDialog = true })
            SwitchPreference("Show buttons", presence.showButtons())
            if (buttons) {
                SwitchPreference("Download button", presence.showDownloadButton())
                SwitchPreference("Discord server button", presence.showDiscordButton())
            }
        }
    }

    if (messageDialog) {
        var text by remember { mutableStateOf(message) }
        AlertDialog(
            onDismissRequest = { messageDialog = false },
            title = { Text("Custom message") },
            text = { OutlinedTextField(text, { text = it.take(128) }, singleLine = true, placeholder = { Text("Shown in place of the title") }) },
            confirmButton = {
                TextButton(onClick = {
                    presence.customMessage().set(text.trim())
                    messageDialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { messageDialog = false }) { Text("Cancel") } },
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
        title = { Text("Hide these categories") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                categories.forEach { category ->
                    val id = category.id.toString()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(id in hidden, onCheckedChange = { hidden = if (it) hidden + id else hidden - id })
                        Text(if (category.isSystemCategory) "Default" else category.name)
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
