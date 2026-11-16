package zoukan.desktop.ui.common

import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * What a screen reader says about a chapter row besides its text: "Read", "Bookmarked", both
 * joined by a comma, or null when neither applies. A faded row or an icon alone says nothing.
 */
internal fun chapterStateDescription(read: Boolean, bookmark: Boolean): String? = listOfNotNull(
    localized(DMR.strings.chapter_state_read).takeIf { read },
    localized(DMR.strings.chapter_state_bookmarked).takeIf { bookmark },
).takeIf { it.isNotEmpty() }?.joinToString(", ")
