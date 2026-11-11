package zoukan.desktop.download

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The Android app's "split tall images" download option (ImageUtil.splitTallImage): a page more
 * than three times as tall as it is wide is saved as several JPEGs, `001__001.jpg`, `001__002.jpg`…,
 * each about twice the screen's height, so readers need not decode one huge image.
 */
object TallImageSplitter {

    /** Twice the screen's longer side, as Android uses twice the display height. */
    val optimalHeight: Int by lazy {
        val screen = if (GraphicsEnvironment.isHeadless()) {
            null
        } else {
            runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                    .maxOfOrNull { maxOf(it.displayMode.width, it.displayMode.height) }
            }.getOrNull()
        }
        (screen ?: 1440) * 2
    }

    fun isSplitName(name: String, base: String) = name.startsWith("${base}__")

    /**
     * The files holding page [base] in [dir] after an interrupted download, made consistent: when
     * the original page is still there, parts beside it are from a split that did not finish, so
     * they are dropped and the page is split again (when [split] is on).
     *
     * @return the page's files, or empty when the page still has to be downloaded
     */
    fun settle(dir: File, base: String, split: Boolean, partHeight: Int = optimalHeight): List<File> {
        val files = dir.listFiles { f -> !f.name.endsWith(".tmp") && f.length() > 0 }.orEmpty()
        val original = files.firstOrNull { it.name.startsWith("$base.") && ImageType.isImageName(it.name) }
        val parts = files.filter { isSplitName(it.name, base) }.sortedBy { it.name }
        if (original == null) return parts
        parts.forEach { it.delete() }
        return if (split) splitIfTall(original, base, partHeight) else listOf(original)
    }

    /**
     * Splits [image] (named `<base>.<ext>`) in its folder when it is tall. Animated images and
     * formats ImageIO cannot read are kept as they are.
     *
     * @return the files that now hold the page
     */
    fun splitIfTall(image: File, base: String, partHeight: Int = optimalHeight): List<File> {
        if (image.extension.lowercase() == "gif" || isAnimatedWebp(image)) return listOf(image)
        val dir = image.parentFile
        val written = mutableListOf<File>()
        try {
            ImageIO.createImageInputStream(image).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return listOf(image)
                try {
                    reader.input = input
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height / width <= 3) return listOf(image)

                    // -1 so an image exactly partHeight tall stays whole
                    val parts = (height - 1) / partHeight + 1
                    if (parts < 2) return listOf(image)
                    val step = height / parts
                    for (index in 0 until parts) {
                        val top = index * step
                        val bottom = if (index == parts - 1) height else top + step
                        val param = reader.defaultReadParam.apply { sourceRegion = Rectangle(0, top, width, bottom - top) }
                        val part = toRgb(reader.read(0, param))
                        // Written as .tmp first, so a stop halfway leaves no parts that look finished
                        val file = File(dir, "${base}__%03d.jpg.tmp".format(index + 1))
                        writeJpeg(part, file)
                        written += file
                    }
                } finally {
                    reader.dispose()
                }
            }
            val parts = written.map { tmp ->
                File(dir, tmp.name.removeSuffix(".tmp")).also { Files.move(tmp.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
            // Parts next to the original mean a stop before this delete; see [settle]
            image.delete()
            logcat { "Split ${image.name} into ${parts.size} parts" }
            return parts
        } catch (e: Exception) {
            // Keep the original page when the parts could not all be saved
            written.forEach { it.delete() }
            written.forEach { File(dir, it.name.removeSuffix(".tmp")).delete() }
            logcat(LogPriority.ERROR, e) { "Failed to split ${image.name}" }
            return listOf(image)
        }
    }

    private fun toRgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_RGB) return image
        return BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { rgb ->
            val g = rgb.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.drawImage(image, 0, 0, null)
            g.dispose()
        }
    }

    private fun writeJpeg(image: BufferedImage, file: File) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(file).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 1f
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
    }

    private fun isAnimatedWebp(file: File): Boolean {
        if (file.extension.lowercase() != "webp") return false
        val header = file.inputStream().use { it.readNBytes(64) }
        // An extended WebP (VP8X) has the animation flag in its feature byte
        return header.size > 20 && String(header, 12, 4, Charsets.US_ASCII) == "VP8X" && (header[20].toInt() and 0x02) != 0
    }
}
