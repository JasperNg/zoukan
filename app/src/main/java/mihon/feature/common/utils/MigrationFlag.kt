package mihon.feature.common.utils

import dev.icerock.moko.resources.StringResource
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_notes
import tachiyomi.i18n.categories
import tachiyomi.i18n.chapters
import tachiyomi.i18n.custom_cover
import tachiyomi.i18n.delete_downloaded
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.log_extra
import tachiyomi.i18n.track

fun MigrationFlag.getLabel(): StringResource {
    return when (this) {
        MigrationFlag.CHAPTER -> MR.strings.chapters
        MigrationFlag.CATEGORY -> MR.strings.categories
        // KMK -->
        MigrationFlag.TRACK -> MR.strings.track
        // KMK <--
        MigrationFlag.CUSTOM_COVER -> MR.strings.custom_cover
        MigrationFlag.NOTES -> MR.strings.action_notes
        MigrationFlag.REMOVE_DOWNLOAD -> MR.strings.delete_downloaded
        // KMK -->
        MigrationFlag.EXTRA -> SYMR.strings.log_extra
        // KMK <--
    }
}
