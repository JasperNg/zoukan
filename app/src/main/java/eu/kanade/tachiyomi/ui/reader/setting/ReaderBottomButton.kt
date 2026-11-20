package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_open_in_browser
import tachiyomi.i18n.action_open_in_web_view
import tachiyomi.i18n.action_share
import tachiyomi.i18n.action_view_chapters
import tachiyomi.i18n.rotation_type
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.page_layout
import tachiyomi.i18n.sy.pref_crop_borders_continuous_vertical
import tachiyomi.i18n.sy.pref_crop_borders_pager
import tachiyomi.i18n.sy.pref_crop_borders_webtoon
import tachiyomi.i18n.viewer

enum class ReaderBottomButton(val value: String, val stringRes: StringResource) {
    ViewChapters("vc", MR.strings.action_view_chapters),
    WebView("wb", MR.strings.action_open_in_web_view),
    Browser("br", MR.strings.action_open_in_browser),
    Share("sh", MR.strings.action_share),
    ReadingMode("rm", MR.strings.viewer),
    Rotation("rot", MR.strings.rotation_type),
    CropBordersPager("cbp", SYMR.strings.pref_crop_borders_pager),
    CropBordersContinuesVertical("cbc", SYMR.strings.pref_crop_borders_continuous_vertical),
    CropBordersWebtoon("cbw", SYMR.strings.pref_crop_borders_webtoon),
    PageLayout("pl", SYMR.strings.page_layout),
    ;

    fun isIn(buttons: Collection<String>) = value in buttons

    companion object {
        val BUTTONS_DEFAULTS = setOf(
            ViewChapters,
            WebView,
            CropBordersPager,
            CropBordersContinuesVertical,
            PageLayout,
        ).map { it.value }.toSet()
    }
}
