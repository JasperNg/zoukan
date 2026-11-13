package zoukan.desktop.ui.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.library.TriStateRow
import zoukan.i18n.desktop.DMR

/** The Updates filters, from Android's UpdatesPreferences, so they carry over in backups. */
data class UpdatesFilters(
    val downloaded: TriState = TriState.DISABLED,
    val unread: TriState = TriState.DISABLED,
    val started: TriState = TriState.DISABLED,
    val bookmarked: TriState = TriState.DISABLED,
    val hideExcludedScanlators: Boolean = false,
) {
    /** As Android's hasActiveFilters: the tri-state filters, not the scanlator switch. */
    val active: Boolean
        get() = listOf(downloaded, unread, started, bookmarked).any { it != TriState.DISABLED }
}

fun updatesFilters(preferences: UpdatesPreferences): Flow<UpdatesFilters> = combine(
    preferences.filterDownloaded().changes(),
    preferences.filterUnread().changes(),
    preferences.filterStarted().changes(),
    preferences.filterBookmarked().changes(),
    preferences.filterExcludedScanlators().changes(),
) { downloaded, unread, started, bookmarked, excluded ->
    UpdatesFilters(downloaded, unread, started, bookmarked, excluded)
}

/** What a tri-state filter asks the updates query for: only, only not, or anything. */
internal fun TriState.toBooleanOrNull(): Boolean? = when (this) {
    TriState.DISABLED -> null
    TriState.ENABLED_IS -> true
    TriState.ENABLED_NOT -> false
}

/** The Updates toolbar's filter button, as Android's, with a dot while a filter is on. */
@Composable
fun UpdatesFilterButton(model: UpdatesScreenModel, filters: UpdatesFilters) {
    var open by remember { mutableStateOf(false) }
    Box {
        BadgedBox(badge = { if (filters.active) Badge() }) {
            TooltipIconButton(
                Icons.Outlined.FilterList,
                if (filters.active) localized(DMR.strings.filter_filtered) else localized(MR.strings.action_filter),
                onClick = { open = true },
            )
        }
    }
    if (open) UpdatesFilterDialog(model.preferences, onDismiss = { open = false })
}

@Composable
private fun UpdatesFilterDialog(preferences: UpdatesPreferences, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(MR.strings.action_filter)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) } },
        text = {
            Column(Modifier.width(320.dp)) {
                TriStateRow(localized(MR.strings.label_downloaded), preferences.filterDownloaded())
                TriStateRow(localized(MR.strings.action_filter_unread), preferences.filterUnread())
                TriStateRow(localized(MR.strings.label_started), preferences.filterStarted())
                TriStateRow(localized(MR.strings.action_filter_bookmarked), preferences.filterBookmarked())
                val excluded = preferences.filterExcludedScanlators()
                val hide by excluded.collectAsState()
                Row(
                    Modifier.fillMaxWidth().clickable { excluded.set(!hide) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = hide, onCheckedChange = { excluded.set(it) })
                    Text(localized(DMR.strings.hide_excluded_scanlators))
                }
            }
        },
    )
}
