package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.automatic_background
import tachiyomi.i18n.landscape
import tachiyomi.i18n.lock_always
import tachiyomi.i18n.lock_never

enum class TabletUiMode(val titleRes: StringResource) {
    AUTOMATIC(MR.strings.automatic_background),
    ALWAYS(MR.strings.lock_always),
    LANDSCAPE(MR.strings.landscape),
    NEVER(MR.strings.lock_never),
}
