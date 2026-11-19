package zoukan.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReaderBottomButtons
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.WebtoonScaleTypes
import zoukan.desktop.ui.common.collectAsState

/** Grouped viewer preferences for paged reading modes. */
@Immutable
data class PagedReaderConfig(
    val pageLayout: Int = 2,
    val invertDoublePages: Boolean = false,
    val dualPageSplit: Boolean = false,
    val dualPageInvert: Boolean = false,
    val dualPageRotateToFit: Boolean = false,
    val dualPageRotateToFitInvert: Boolean = false,
    val centerMarginType: Int = 0,
    val navigationMode: Int = 0,
    val navInverted: String = "NONE",
    val smallerTapZone: Boolean = false,
    val navigateToPan: Boolean = true,
    val readWithLongTap: Boolean = true,
    val pageTransitions: Boolean = true,
    val doubleTapZoomEnabled: Boolean = true,
    val disableZoomIn: Boolean = false,
    val zoomStart: Int = 1,
    val alwaysShowChapterTransition: Boolean = true,
) {
    companion object {
        @Composable
        fun collect(prefs: DesktopPreferences): PagedReaderConfig {
            val smallerTapZone by prefs.smallerTapZone().collectAsState()
            val readWithLongTap by prefs.readWithLongTap().collectAsState()
            val alwaysShowChapterTransition by prefs.alwaysShowChapterTransition().collectAsState()
            return collect(prefs, smallerTapZone, readWithLongTap, alwaysShowChapterTransition)
        }

        @Composable
        internal fun collect(
            prefs: DesktopPreferences,
            smallerTapZone: Boolean,
            readWithLongTap: Boolean,
            alwaysShowChapterTransition: Boolean,
        ): PagedReaderConfig {
            val pageLayout by prefs.pageLayout().collectAsState()
            val invertDoublePages by prefs.invertDoublePages().collectAsState()
            val dualPageSplit by prefs.dualPageSplitPaged().collectAsState()
            val dualPageInvert by prefs.dualPageInvertPaged().collectAsState()
            val dualPageRotateToFit by prefs.dualPageRotateToFit().collectAsState()
            val dualPageRotateToFitInvert by prefs.dualPageRotateToFitInvert().collectAsState()
            val centerMarginType by prefs.centerMarginType().collectAsState()
            val navigationMode by prefs.navigationModePager().collectAsState()
            val navInverted by prefs.pagerNavInverted().collectAsState()
            val navigateToPan by prefs.navigateToPan().collectAsState()
            val pageTransitions by prefs.pageTransitionsPager().collectAsState()
            val doubleTapZoomEnabled by prefs.pagedDoubleTapZoomEnabled().collectAsState()
            val disableZoomIn by prefs.pagedDisableZoomIn().collectAsState()
            val zoomStart by prefs.zoomStart().collectAsState()
            return PagedReaderConfig(
                pageLayout = pageLayout,
                invertDoublePages = invertDoublePages,
                dualPageSplit = dualPageSplit,
                dualPageInvert = dualPageInvert,
                dualPageRotateToFit = dualPageRotateToFit,
                dualPageRotateToFitInvert = dualPageRotateToFitInvert,
                centerMarginType = centerMarginType,
                navigationMode = navigationMode,
                navInverted = navInverted,
                smallerTapZone = smallerTapZone,
                navigateToPan = navigateToPan,
                readWithLongTap = readWithLongTap,
                pageTransitions = pageTransitions,
                doubleTapZoomEnabled = doubleTapZoomEnabled,
                disableZoomIn = disableZoomIn,
                zoomStart = zoomStart,
                alwaysShowChapterTransition = alwaysShowChapterTransition,
            )
        }
    }
}

/** Grouped viewer preferences for webtoon and continuous vertical reading modes. */
@Immutable
data class WebtoonReaderConfig(
    val navigationMode: Int = 0,
    val navInverted: String = "NONE",
    val smallerTapZone: Boolean = false,
    val pageTransitions: Boolean = true,
    val doubleTapZoomEnabled: Boolean = true,
    val pinchToZoomEnabled: Boolean = true,
    val disableZoomOut: Boolean = false,
    val readWithLongTap: Boolean = true,
    val dualPageSplit: Boolean = false,
    val dualPageInvert: Boolean = false,
    val dualPageRotateToFit: Boolean = false,
    val dualPageRotateToFitInvert: Boolean = false,
    val scaleType: String = WebtoonScaleTypes.FIT,
    val longStripGapSmartScale: Boolean = false,
    val alwaysShowChapterTransition: Boolean = true,
) {
    companion object {
        @Composable
        fun collect(prefs: DesktopPreferences): WebtoonReaderConfig {
            val smallerTapZone by prefs.smallerTapZone().collectAsState()
            val readWithLongTap by prefs.readWithLongTap().collectAsState()
            val alwaysShowChapterTransition by prefs.alwaysShowChapterTransition().collectAsState()
            return collect(prefs, smallerTapZone, readWithLongTap, alwaysShowChapterTransition)
        }

        @Composable
        internal fun collect(
            prefs: DesktopPreferences,
            smallerTapZone: Boolean,
            readWithLongTap: Boolean,
            alwaysShowChapterTransition: Boolean,
        ): WebtoonReaderConfig {
            val navigationMode by prefs.navigationModeWebtoon().collectAsState()
            val navInverted by prefs.webtoonNavInverted().collectAsState()
            val pageTransitions by prefs.pageTransitionsWebtoon().collectAsState()
            val doubleTapZoomEnabled by prefs.webtoonDoubleTapZoomEnabled().collectAsState()
            val pinchToZoomEnabled by prefs.webtoonPinchToZoomEnabled().collectAsState()
            val disableZoomOut by prefs.webtoonDisableZoomOut().collectAsState()
            val dualPageSplit by prefs.dualPageSplitWebtoon().collectAsState()
            val dualPageInvert by prefs.dualPageInvertWebtoon().collectAsState()
            val dualPageRotateToFit by prefs.dualPageRotateToFitWebtoon().collectAsState()
            val dualPageRotateToFitInvert by prefs.dualPageRotateToFitInvertWebtoon().collectAsState()
            val scaleType by prefs.webtoonScaleType().collectAsState()
            val longStripGapSmartScale by prefs.longStripGapSmartScale().collectAsState()
            return WebtoonReaderConfig(
                navigationMode = navigationMode,
                navInverted = navInverted,
                smallerTapZone = smallerTapZone,
                pageTransitions = pageTransitions,
                doubleTapZoomEnabled = doubleTapZoomEnabled,
                pinchToZoomEnabled = pinchToZoomEnabled,
                disableZoomOut = disableZoomOut,
                readWithLongTap = readWithLongTap,
                dualPageSplit = dualPageSplit,
                dualPageInvert = dualPageInvert,
                dualPageRotateToFit = dualPageRotateToFit,
                dualPageRotateToFitInvert = dualPageRotateToFitInvert,
                scaleType = scaleType,
                longStripGapSmartScale = longStripGapSmartScale,
                alwaysShowChapterTransition = alwaysShowChapterTransition,
            )
        }
    }
}

/**
 * Snapshot of all reader preferences observed by [ReaderScreen] and its viewers, collected once
 * instead of scattered preference subscriptions across composables.
 */
@Immutable
data class ReaderConfig(
    val theme: Int = 1,
    val fitMode: Int = FitModes.FIT_SCREEN,
    val webtoonSidePadding: Int = 0,
    val showPageNumber: Boolean = true,
    val autoScrollInterval: Float = 3f,
    val smoothAutoScroll: Boolean = true,
    val filter: ReaderFilter = ReaderFilter(),
    val cropPaged: Boolean = false,
    val cropWebtoon: Boolean = false,
    val cropContinuousVertical: Boolean = false,
    val bottomButtons: Set<String> = ReaderBottomButtons.DEFAULTS,
    val forceHorizontalSeekbar: Boolean = false,
    val landscapeVerticalSeekbar: Boolean = false,
    val leftVerticalSeekbar: Boolean = false,
    val paged: PagedReaderConfig = PagedReaderConfig(),
    val webtoon: WebtoonReaderConfig = WebtoonReaderConfig(),
) {
    val cropKey: Triple<Boolean, Boolean, Boolean>
        get() = Triple(cropPaged, cropWebtoon, cropContinuousVertical)

    fun cropEnabled(readingMode: Int): Boolean = when (readingMode) {
        ReadingModes.WEBTOON -> cropWebtoon
        ReadingModes.CONTINUOUS_VERTICAL -> cropContinuousVertical
        else -> cropPaged
    }

    fun navInvert(webtoonMode: Boolean): String =
        if (webtoonMode) webtoon.navInverted else paged.navInverted

    companion object {
        @Composable
        fun collect(prefs: DesktopPreferences): ReaderConfig {
            val theme by prefs.readerTheme().collectAsState()
            val fitMode by prefs.imageScaleType().collectAsState()
            val webtoonSidePadding by prefs.webtoonSidePadding().collectAsState()
            val showPageNumber by prefs.showPageNumber().collectAsState()
            val autoScrollInterval by prefs.autoscrollInterval().collectAsState()
            val smoothAutoScroll by prefs.smoothAutoScroll().collectAsState()
            val filter = ReaderFilter.collect(prefs)
            val cropPaged by prefs.cropBorders().collectAsState()
            val cropWebtoon by prefs.cropBordersWebtoon().collectAsState()
            val cropContinuousVertical by prefs.cropBordersContinuousVertical().collectAsState()
            val bottomButtons by prefs.readerBottomButtons().collectAsState()
            val forceHorizontalSeekbar by prefs.forceHorizontalSeekbar().collectAsState()
            val landscapeVerticalSeekbar by prefs.landscapeVerticalSeekbar().collectAsState()
            val leftVerticalSeekbar by prefs.leftVerticalSeekbar().collectAsState()
            val smallerTapZone by prefs.smallerTapZone().collectAsState()
            val readWithLongTap by prefs.readWithLongTap().collectAsState()
            val alwaysShowChapterTransition by prefs.alwaysShowChapterTransition().collectAsState()
            val paged = PagedReaderConfig.collect(prefs, smallerTapZone, readWithLongTap, alwaysShowChapterTransition)
            val webtoon = WebtoonReaderConfig.collect(prefs, smallerTapZone, readWithLongTap, alwaysShowChapterTransition)
            return ReaderConfig(
                theme = theme,
                fitMode = fitMode,
                webtoonSidePadding = webtoonSidePadding,
                showPageNumber = showPageNumber,
                autoScrollInterval = autoScrollInterval,
                smoothAutoScroll = smoothAutoScroll,
                filter = filter,
                cropPaged = cropPaged,
                cropWebtoon = cropWebtoon,
                cropContinuousVertical = cropContinuousVertical,
                bottomButtons = bottomButtons,
                forceHorizontalSeekbar = forceHorizontalSeekbar,
                landscapeVerticalSeekbar = landscapeVerticalSeekbar,
                leftVerticalSeekbar = leftVerticalSeekbar,
                paged = paged,
                webtoon = webtoon,
            )
        }
    }
}
