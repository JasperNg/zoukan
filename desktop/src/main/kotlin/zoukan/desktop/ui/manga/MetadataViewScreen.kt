package zoukan.desktop.ui.manga

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.getMainSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.onRightClick

/**
 * The metadata viewer (Manga > More info): every field the source's metadata has, as the Android
 * app's exh.ui.metadata.MetadataViewScreen. Right-click copies a value.
 */
data class MetadataViewScreen(val mangaId: Long, val sourceId: Long) : Screen {
    override val key: ScreenKey = "metadata-$mangaId"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { MetadataViewScreenModel(mangaId, sourceId) }
        val state by model.state.collectAsState()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(state.title.orEmpty())
            when (val view = state.view) {
                MetadataViewScreenModel.View.Loading -> LoadingScreen()
                MetadataViewScreenModel.View.MetadataNotFound -> EmptyScreen(localized(MR.strings.no_results_found))
                MetadataViewScreenModel.View.SourceNotFound -> EmptyScreen(localized(MR.strings.source_empty_screen))
                is MetadataViewScreenModel.View.Success -> MetadataList(view.items)
            }
        }
    }
}

@Composable
private fun MetadataList(items: List<Pair<String, String>>) {
    LazyColumn(Modifier.fillMaxSize().widthIn(max = 1000.dp).testTag("metadata-list")) {
        items(items) { (title, text) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { }
                    .onRightClick { copyToClipboard(text) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.width(180.dp).padding(start = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

class MetadataViewScreenModel(
    mangaId: Long,
    sourceId: Long,
    getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    sourceManager: SourceManager = Injekt.get(),
    getManga: GetManga = Injekt.get(),
) : StateScreenModel<MetadataViewScreenModel.State>(State()) {

    data class State(val title: String? = null, val view: View = View.Loading)

    sealed interface View {
        data object Loading : View
        data class Success(val meta: RaisedSearchMetadata, val items: List<Pair<String, String>>) : View
        data object MetadataNotFound : View
        data object SourceNotFound : View
    }

    init {
        screenModelScope.launch(Dispatchers.IO) {
            val title = getManga.await(mangaId)?.title
            mutableState.update { it.copy(title = title) }
        }
        screenModelScope.launch(Dispatchers.IO) {
            val metadataSource = sourceManager.get(sourceId)?.getMainSource<MetadataSource<*, *>>()
            val view = if (metadataSource == null) {
                View.SourceNotFound
            } else {
                when (val flat = getFlatMetadataById.await(mangaId)) {
                    null -> View.MetadataNotFound
                    else -> {
                        val meta = flat.raise(metadataSource.metaClass)
                        View.Success(meta, meta.getExtraInfoPairs(Injekt.get<Application>()))
                    }
                }
            }
            mutableState.update { it.copy(view = view) }
        }
    }
}
