package tachiyomi.domain.library.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.categories
import tachiyomi.i18n.label_sources
import tachiyomi.i18n.status
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.tracking_status
import tachiyomi.i18n.sy.ungrouped

object LibraryGroup {

    const val BY_DEFAULT = 0
    const val BY_SOURCE = 1
    const val BY_STATUS = 2
    const val BY_TRACK_STATUS = 3
    const val UNGROUPED = 4

    fun groupTypeStringRes(type: Int): StringResource {
        return when (type) {
            BY_STATUS -> MR.strings.status
            BY_SOURCE -> MR.strings.label_sources
            BY_TRACK_STATUS -> SYMR.strings.tracking_status
            UNGROUPED -> SYMR.strings.ungrouped
            else -> MR.strings.categories
        }
    }
}
