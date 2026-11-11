package zoukan.desktop.ui.migration

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Entries picked in a manual search for a bulk migration, as the Android app's
 * MigrationListScreen.addMatchOverride: the search screen sends the pick, and the list (still on
 * the back stack) takes it as the entry's target.
 */
object MigrationPicks {
    data class Pick(val current: Long, val target: Long)

    private val mutablePicks = MutableSharedFlow<Pick>(extraBufferCapacity = 16)
    val picks: SharedFlow<Pick> = mutablePicks.asSharedFlow()

    fun pick(current: Long, target: Long) {
        mutablePicks.tryEmit(Pick(current, target))
    }
}
