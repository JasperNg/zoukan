package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourcePreferences.DataSaver
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.LazyColumn

/**
 * Settings > Data saver, as the data saver group of the Android app's Advanced settings: pages
 * load through Bandwidth Hero or wsrv.nl, recompressed. Android excludes a source from its long
 * press menu in Browse; here the excluded sources are picked in this section.
 */
fun LazyListScope.dataSaverSettings() {
    settingsGroup(localized(SYMR.strings.data_saver))
    item(key = "data-saver-settings") { DataSaverSettings() }
}

@Composable
private fun DataSaverSettings() {
    val preferences = remember { Injekt.get<SourcePreferences>() }
    val dataSaver by preferences.dataSaver().changes().collectAsState(preferences.dataSaver().get())
    val server by preferences.dataSaverServer().changes().collectAsState(preferences.dataSaverServer().get())
    val excluded by preferences.dataSaverExcludedSources().changes().collectAsState(preferences.dataSaverExcludedSources().get())
    var serverDialog by remember { mutableStateOf(false) }
    var sourcesDialog by remember { mutableStateOf(false) }
    val on = dataSaver != DataSaver.NONE

    Column {
        ChoiceSetting(
            localized(SYMR.strings.data_saver),
            dataSaver,
            listOf(
                DataSaver.NONE to localized(MR.strings.disabled),
                DataSaver.BANDWIDTH_HERO to localized(SYMR.strings.bandwidth_hero),
                DataSaver.WSRV_NL to localized(SYMR.strings.wsrv),
            ),
            subtitle = localized(SYMR.strings.data_saver_summary),
        ) { preferences.dataSaver().set(it) }
        SettingRow(
            localized(SYMR.strings.bandwidth_data_saver_server),
            server.ifBlank { localized(SYMR.strings.data_saver_server_summary) },
            enabled = dataSaver == DataSaver.BANDWIDTH_HERO,
            onClick = { serverDialog = true },
        )
        SwitchSetting(
            localized(SYMR.strings.data_saver_downloader),
            checked = preferences.dataSaverDownloader().collect(),
            enabled = on,
        ) { preferences.dataSaverDownloader().set(it) }
        SwitchSetting(
            localized(SYMR.strings.data_saver_ignore_jpeg),
            checked = preferences.dataSaverIgnoreJpeg().collect(),
            enabled = on,
        ) { preferences.dataSaverIgnoreJpeg().set(it) }
        SwitchSetting(
            localized(SYMR.strings.data_saver_ignore_gif),
            checked = preferences.dataSaverIgnoreGif().collect(),
            enabled = on,
        ) { preferences.dataSaverIgnoreGif().set(it) }
        val quality by preferences.dataSaverImageQuality().changes().collectAsState(preferences.dataSaverImageQuality().get())
        ChoiceSetting(
            localized(SYMR.strings.data_saver_image_quality),
            quality,
            listOf(10, 20, 40, 50, 70, 80, 90, 95).map { it to "$it%" },
            subtitle = localized(SYMR.strings.data_saver_image_quality_summary),
            enabled = on,
        ) { preferences.dataSaverImageQuality().set(it) }
        val jpeg = preferences.dataSaverImageFormatJpeg().collect()
        SwitchSetting(
            localized(SYMR.strings.data_saver_image_format),
            localized(if (jpeg) SYMR.strings.data_saver_image_format_summary_on else SYMR.strings.data_saver_image_format_summary_off),
            checked = jpeg,
            enabled = on,
        ) { preferences.dataSaverImageFormatJpeg().set(it) }
        SwitchSetting(
            localized(SYMR.strings.data_saver_color_bw),
            checked = preferences.dataSaverColorBW().collect(),
            enabled = dataSaver == DataSaver.BANDWIDTH_HERO,
        ) { preferences.dataSaverColorBW().set(it) }
        SettingRow(
            "Excluded sources",
            if (excluded.isEmpty()) "None" else "${excluded.size} excluded",
            enabled = on,
            onClick = { sourcesDialog = true },
        )
    }

    if (serverDialog) {
        var text by remember { mutableStateOf(server) }
        AlertDialog(
            onDismissRequest = { serverDialog = false },
            title = { Text(localized(SYMR.strings.bandwidth_data_saver_server)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("https://…") }) },
            confirmButton = {
                TextButton(onClick = {
                    preferences.dataSaverServer().set(text.trim())
                    serverDialog = false
                }) { Text(localized(MR.strings.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { serverDialog = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }

    if (sourcesDialog) {
        val sources = remember {
            Injekt.get<SourceManager>().getOnlineSources().sortedBy { it.toString().lowercase() }
        }
        AlertDialog(
            onDismissRequest = { sourcesDialog = false },
            title = { Text("Exclude from the data saver") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(sources, key = { it.id }) { source ->
                        val id = source.id.toString()
                        SwitchSetting(source.toString(), checked = id in excluded) { exclude ->
                            preferences.dataSaverExcludedSources().set(if (exclude) excluded + id else excluded - id)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sourcesDialog = false }) { Text(localized(MR.strings.action_ok)) } },
        )
    }
}

@Composable
private fun tachiyomi.core.common.preference.Preference<Boolean>.collect(): Boolean {
    val value by changes().collectAsState(get())
    return value
}
