package zoukan.desktop.ui.reader

/**
 * Exclusive overlay or modal dialog shown on top of the reader so bars and dialogs never stack
 * awkwardly.
 */
sealed interface ReaderOverlay {
    data object Hidden : ReaderOverlay
    data object Menu : ReaderOverlay
    data class PageActions(val target: PageActionsTarget) : ReaderOverlay
    data object Settings : ReaderOverlay
    data object Chapters : ReaderOverlay
    data object Help : ReaderOverlay

    fun toggleMenu(): ReaderOverlay = if (this is Menu) Hidden else Menu

    fun setMenu(visible: Boolean): ReaderOverlay = when {
        visible -> Menu
        this is Menu -> Hidden
        else -> this
    }

    fun toggleSettings(): ReaderOverlay = if (this is Settings) Hidden else Settings

    fun toggleChapters(): ReaderOverlay = if (this is Chapters) Hidden else Chapters

    fun toggleHelp(): ReaderOverlay = if (this is Help) Hidden else Help

    fun clearPageActions(): ReaderOverlay = if (this is PageActions) Hidden else this

    fun dismiss(): ReaderOverlay = Hidden
}
