package zoukan.desktop.ui

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/** The outcome of [ScreenshotDiff.compare]. [changedShare] is changed pixels over all pixels, outside the masks. */
data class DiffResult(
    val passed: Boolean,
    val changedPixels: Int,
    val changedShare: Double,
    val message: String,
    /** Changed pixels in red over a dimmed actual image; null when the sizes differ or nothing changed. */
    val diff: BufferedImage?,
)

object ScreenshotDiff {
    /**
     * Compares [actual] with [expected]. A pixel is changed when any of its channels (alpha
     * included) differs by more than [channelTolerance], outside [masks]. The images pass when the
     * changed share of all pixels is at most [maxChangedShare]. Images of different sizes fail.
     */
    fun compare(
        expected: BufferedImage,
        actual: BufferedImage,
        channelTolerance: Int = 8,
        maxChangedShare: Double = 0.001,
        masks: List<Rectangle> = emptyList(),
    ): DiffResult {
        if (expected.width != actual.width || expected.height != actual.height) {
            return DiffResult(
                passed = false,
                changedPixels = 0,
                changedShare = 1.0,
                message = "size differs: expected ${expected.width}x${expected.height}, actual ${actual.width}x${actual.height}",
                diff = null,
            )
        }
        val width = actual.width
        val height = actual.height
        val a = expected.argbPixels()
        val b = actual.argbPixels()
        val masked = BooleanArray(width * height)
        for (mask in masks) {
            val clipped = mask.intersection(Rectangle(0, 0, width, height))
            if (clipped.isEmpty) continue
            for (y in clipped.y until clipped.y + clipped.height) {
                for (x in clipped.x until clipped.x + clipped.width) masked[y * width + x] = true
            }
        }
        val diff = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var changed = 0
        for (i in b.indices) {
            val p = b[i]
            val differs = !masked[i] && exceeds(a[i], p, channelTolerance)
            if (differs) changed++
            diff.setRGB(i % width, i / width, if (differs) RED else dim(p))
        }
        val share = changed.toDouble() / (width.toLong() * height).coerceAtLeast(1)
        val passed = share <= maxChangedShare
        val message = "$changed of ${width * height} pixels changed (${"%.4f".format(share * 100)}%), at most ${maxChangedShare * 100}% allowed"
        return DiffResult(passed, changed, share, message, if (changed > 0) diff else null)
    }

    /** Writes `expected.png`, `actual.png` and `diff.png` to [dir]. A size mismatch has no diff image. */
    fun writeFailure(dir: File, expected: BufferedImage, actual: BufferedImage, result: DiffResult) {
        dir.mkdirs()
        ImageIO.write(expected, "png", File(dir, "expected.png"))
        ImageIO.write(actual, "png", File(dir, "actual.png"))
        result.diff?.let { ImageIO.write(it, "png", File(dir, "diff.png")) }
    }

    private const val RED = 0xFFFF0000.toInt()

    private fun exceeds(x: Int, y: Int, tolerance: Int): Boolean {
        if (x == y) return false
        for (shift in intArrayOf(0, 8, 16, 24)) {
            if (abs(((x ushr shift) and 0xFF) - ((y ushr shift) and 0xFF)) > tolerance) return true
        }
        return false
    }

    /** The pixel at a third of its brightness and fully opaque, so the red stands out. */
    private fun dim(argb: Int): Int {
        val r = ((argb shr 16) and 0xFF) / 3
        val g = ((argb shr 8) and 0xFF) / 3
        val b = (argb and 0xFF) / 3
        return Color(r, g, b).rgb or (0xFF shl 24)
    }

    private fun BufferedImage.argbPixels(): IntArray = getRGB(0, 0, width, height, null, 0, width)
}
