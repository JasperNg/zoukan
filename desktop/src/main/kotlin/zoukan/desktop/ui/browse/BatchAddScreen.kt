package zoukan.desktop.ui.browse

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import exh.ui.batchadd.BatchAddScreenModel
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR

/**
 * The Android app's Batch add (More > Batch add): paste entry addresses, one per line (or E-H
 * Visited's export), and each is added to the library through the shared BatchAddScreenModel and
 * GalleryAdder.
 */
object BatchAddScreen : Screen {
    private fun readResolve(): Any = BatchAddScreen

    override val key: ScreenKey = "batch-add"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { BatchAddScreenModel() }
        val state by model.state.collectAsState()
        val context = Injekt.get<Application>()

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(SYMR.strings.batch_add))
            when (state.state) {
                BatchAddScreenModel.State.INPUT -> Column(
                    Modifier.fillMaxSize().verticalScrollWithBar().padding(24.dp).widthIn(max = 900.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(localized(SYMR.strings.eh_batch_add_title), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = state.galleries,
                        onValueChange = model::updateGalleries,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("batch-add-input"),
                        placeholder = {
                            Text(
                                MANGADEX_EXAMPLE + if (model.isHentaiEnabled) EH_EXAMPLE else "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { model.addGalleries(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(localized(DMR.strings.add_galleries))
                    }
                    Text(localized(KMR.strings.batch_add_description), style = MaterialTheme.typography.bodyMedium)
                }
                BatchAddScreenModel.State.PROGRESS -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(24.dp),
                ) {
                    item(key = "top") {
                        Column {
                            Text(localized(SYMR.strings.eh_batch_add_adding_galleries), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (state.progressTotal > 0) {
                                    LinearProgressIndicator(
                                        progress = { state.progress.toFloat() / state.progressTotal },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Text(
                                    "${state.progress}/${state.progressTotal}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    itemsIndexed(state.events, key = { index, text -> "event-$index-${text.hashCode()}" }) { _, text ->
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                    }
                    if (state.progress == state.progressTotal) {
                        item(key = "finish") {
                            Button(onClick = model::finish, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(localized(SYMR.strings.eh_batch_add_finish)) }
                        }
                    }
                }
            }
        }

        if (state.dialog == BatchAddScreenModel.Dialog.NoGalleriesSpecified) {
            AlertDialog(
                onDismissRequest = model::dismissDialog,
                confirmButton = { TextButton(onClick = model::dismissDialog) { Text("OK") } },
                title = { Text(localized(SYMR.strings.batch_add_no_valid_galleries)) },
                text = { Text(localized(SYMR.strings.batch_add_no_valid_galleries_message)) },
            )
        }
    }

    private val MANGADEX_EXAMPLE get() = localized(DMR.strings.example) + "\n\n" +
        "https://mangadex.org/title/32d76d19-8a05-4db0-9fc2-e0b0648fe9d0/solo-leveling\n" +
        "https://mangadex.org/title/77bee52c-d2d6-44ad-a33a-1734c1fe696a\n\n"

    private val EH_EXAMPLE get() = localized(DMR.strings.example) + "\n\n" +
        "http://e-hentai.org/g/12345/1a2b3c4e\n" +
        "http://g.e-hentai.org/g/67890/6f7g8h9i\n" +
        "http://exhentai.org/g/13579/1a3b5c7e\n" +
        "https://exhentai.org/g/24680/2f4g6h8i\n\n" +
        localized(DMR.strings.eh_visited_supported) + "\n"
}
