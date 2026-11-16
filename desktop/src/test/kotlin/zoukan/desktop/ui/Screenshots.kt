@file:OptIn(ExperimentalTestApi::class)

package zoukan.desktop.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * What the user sees: the window's root with every other root (dialogs, menus, toasts and other
 * popups) drawn over it at its place in the window. Each of those is a root of its own; today the
 * desktop test scene also draws them into the window root's capture, and drawing every root keeps
 * them in the image if that changes.
 *
 * The image is always [BufferedImage.TYPE_INT_ARGB]. A root that goes away between listing the
 * roots and capturing it (a popup that closed) is left out rather than failing the screenshot.
 */
fun ComposeUiTest.captureAllRoots(): BufferedImage {
    waitForIdle()
    val roots = onAllNodes(isRoot())
    val count = roots.fetchSemanticsNodes().size
    val image = roots[0].captureToImage().toAwtImage().toArgb()
    val graphics = image.createGraphics()
    try {
        (1 until count).forEach { index ->
            runCatching {
                val node = roots[index]
                val bounds = node.fetchSemanticsNode().boundsInWindow
                node.captureToImage().toAwtImage() to bounds
            }.onSuccess { (layer, bounds) ->
                graphics.drawImage(layer, bounds.left.toInt(), bounds.top.toInt(), null)
            }
        }
    } finally {
        graphics.dispose()
    }
    return image
}

/** Writes [captureAllRoots] to `<dir>/<name>.png` and returns that file. */
fun ComposeUiTest.saveScreenshot(dir: File, name: String): File {
    val file = File(dir.apply { mkdirs() }, "$name.png")
    ImageIO.write(captureAllRoots(), "png", file)
    return file
}

/** This image as [BufferedImage.TYPE_INT_ARGB]: itself when it already is, else a copy. */
private fun BufferedImage.toArgb(): BufferedImage {
    if (type == BufferedImage.TYPE_INT_ARGB) return this
    val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = copy.createGraphics()
    try {
        graphics.drawImage(this, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return copy
}
