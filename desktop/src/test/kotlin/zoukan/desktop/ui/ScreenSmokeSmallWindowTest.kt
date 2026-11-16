package zoukan.desktop.ui

import zoukan.desktop.ui.common.WindowBounds

/**
 * The smoke checks at the smallest window the app allows (WindowBounds.MIN_WIDTH x MIN_HEIGHT), in
 * its own JVM. It finds what only a small window shows: clipped labels, a layout that throws.
 *
 *   ./gradlew :desktop:wiringTest --tests "*ScreenSmokeSmallWindowTest" -Pzoukan.smoke.only="settings:"
 */
class ScreenSmokeSmallWindowTest : ScreenSmokeTest() {
    override val width = WindowBounds.MIN_WIDTH
    override val height = WindowBounds.MIN_HEIGHT
    override val small = true
}
