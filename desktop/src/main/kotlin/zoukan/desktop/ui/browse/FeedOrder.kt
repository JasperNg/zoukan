package zoukan.desktop.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import zoukan.desktop.ui.common.TooltipIconButton

/**
 * Komikku's feed order screen (FeedOrderScreen and SourceFeedOrderScreen): drag the saved feeds,
 * or move them with the arrows; the database follows each move, as Edit categories does.
 */
@Composable
fun FeedOrderDialog(model: FeedScreenModel, rows: List<FeedScreenModel.Row>, onDismiss: () -> Unit) {
    val items = remember { rows.toMutableStateList() }
    val listState = rememberLazyListState()
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        val row = items.removeAt(from.index)
        items.add(to.index, row)
        model.move(row, to.index)
    }
    LaunchedEffect(rows) {
        if (!reorderable.isAnyItemDragging) {
            items.clear()
            items.addAll(rows)
        }
    }
    fun moveBy(row: FeedScreenModel.Row, delta: Int) {
        val from = items.indexOf(row)
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (from < 0 || from == to) return
        items.add(to, items.removeAt(from))
        model.move(row, to)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Feed order") },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 480.dp).widthIn(min = 360.dp).testTag("feed-order"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.feed.id }) { row ->
                    ReorderableItem(reorderable, row.feed.id) {
                        ElevatedCard {
                            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.DragHandle, "Reorder", Modifier.padding(12.dp).draggableHandle())
                                Text(row.title, modifier = Modifier.weight(1f))
                                TooltipIconButton(Icons.Default.KeyboardArrowUp, "Move up", onClick = { moveBy(row, -1) }, enabled = items.firstOrNull() != row)
                                TooltipIconButton(Icons.Default.KeyboardArrowDown, "Move down", onClick = { moveBy(row, 1) }, enabled = items.lastOrNull() != row)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
