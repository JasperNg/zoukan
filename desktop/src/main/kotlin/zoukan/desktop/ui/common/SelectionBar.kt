package zoukan.desktop.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/** The bar shown while items are selected: clear, the count, select all, invert, then the screen's own actions. */
@Composable
fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            TooltipIconButton(Icons.Outlined.Close, localized(DMR.strings.clear_selection), onClick = onClear)
            Text(localized(DMR.strings.selected, count), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TooltipIconButton(Icons.Outlined.SelectAll, localized(MR.strings.action_select_all), onClick = onSelectAll)
            TooltipIconButton(Icons.Outlined.FlipToBack, localized(DMR.strings.invert_selection), onClick = onInvert)
            actions()
        }
    }
}
