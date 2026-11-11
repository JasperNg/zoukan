package zoukan.desktop.ui.reader

/**
 * What keys and menu buttons ask of the active viewer. The paged and webtoon viewers each set
 * these when they show.
 */
class ReaderActions {
    var next: () -> Unit = {}
    var previous: () -> Unit = {}

    /** Down arrow: pan or scroll down, else the next page */
    var down: () -> Unit = {}

    /** Up arrow: pan or scroll up, else the previous page */
    var up: () -> Unit = {}
    var first: () -> Unit = {}
    var last: () -> Unit = {}
    var goTo: (Int) -> Unit = {}
    var zoomIn: () -> Unit = {}
    var zoomOut: () -> Unit = {}
    var zoomReset: () -> Unit = {}

    /** Smooth auto scroll: scrolls a window's height over the given milliseconds; null where pages turn instead */
    var smoothScroll: ((Long) -> Unit)? = null
}
