package zoukan.desktop.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Corners
import zoukan.i18n.desktop.DMR
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The pages a right-click or long-press acted on, as Android's page actions dialog: [page] alone,
 * or with [extra], the second page of a double page. [leftFirst] says whether [page] is drawn on
 * the left, which is the order a combined image keeps.
 */
data class PageActionsTarget(val page: Int, val extra: Int? = null, val leftFirst: Boolean = true)

/**
 * The page actions sheet: set as cover, copy to the clipboard and save, for one page or, on a
 * double page, for the first, the second or both combined. Android also shares; a desktop has no
 * share sheet, so copying covers it.
 */
@Composable
fun PageActionsDialog(
    target: PageActionsTarget,
    onDismiss: () -> Unit,
    onSetAsCover: (index: Int) -> Unit,
    onCopy: (index: Int) -> Unit,
    onSave: (index: Int) -> Unit,
    onCopyCombined: () -> Unit,
    onSaveCombined: () -> Unit,
) {
    var coverOf by remember { mutableStateOf<Int?>(null) }
    val double = target.extra != null
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("page-actions"),
        title = { Text(if (double) localized(DMR.strings.pages_and, target.page + 1, target.extra!! + 1) else localized(DMR.strings.page, target.page + 1)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (double) localized(SYMR.strings.action_set_first_page_cover) else localized(MR.strings.set_as_cover), Icons.Outlined.Photo) { coverOf = target.page }
                    Action(if (double) localized(KMR.strings.action_copy_to_clipboard_first_page) else localized(MR.strings.action_copy_to_clipboard), Icons.Outlined.ContentCopy) {
                        onCopy(target.page)
                        onDismiss()
                    }
                    Action(if (double) localized(SYMR.strings.action_save_first_page) else localized(MR.strings.action_save), Icons.Outlined.Save) {
                        onSave(target.page)
                        onDismiss()
                    }
                }
                if (target.extra != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action(localized(SYMR.strings.action_set_second_page_cover), Icons.Outlined.Photo) { coverOf = target.extra }
                        Action(localized(KMR.strings.action_copy_to_clipboard_second_page), Icons.Outlined.ContentCopy) {
                            onCopy(target.extra)
                            onDismiss()
                        }
                        Action(localized(SYMR.strings.action_save_second_page), Icons.Outlined.Save) {
                            onSave(target.extra)
                            onDismiss()
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.widthIn(min = ACTION_WIDTH))
                        Action(localized(KMR.strings.action_copy_to_clipboard_combined_page), Icons.Outlined.ContentCopy) {
                            onCopyCombined()
                            onDismiss()
                        }
                        Action(localized(SYMR.strings.action_save_combined_page), Icons.Outlined.Save) {
                            onSaveCombined()
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) } },
    )

    coverOf?.let { index ->
        AlertDialog(
            onDismissRequest = { coverOf = null },
            text = { Text(localized(MR.strings.confirm_set_image_as_cover)) },
            confirmButton = {
                TextButton(onClick = {
                    onSetAsCover(index)
                    coverOf = null
                    onDismiss()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { coverOf = null }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}

private val ACTION_WIDTH = 132.dp

@Composable
private fun Action(title: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = Corners.Large,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier.widthIn(min = ACTION_WIDTH, max = ACTION_WIDTH).height(96.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Icon(icon, null)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}

/** Saving, copying and combining page images, as Android's ImageSaver and ImageUtil.mergeBitmaps. */
object PageImages {

    /**
     * Where saved pages go: the Pictures folder's Zoukan folder, as Android's Pictures/Komikku.
     * `-Dzoukan.picturesDir=<path>` overrides it, which the tests use.
     */
    fun picturesDir(): File = System.getProperty("zoukan.picturesDir")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(File(System.getProperty("user.home"), "Pictures"), "Zoukan")

    /** Writes [bytes] as [name] plus the image's extension into [dir], and returns the file. */
    fun save(bytes: ByteArray, dir: File, name: String): File {
        val ext = extension(bytes)
        val data = if (ext == null) toPng(bytes) else bytes
        dir.mkdirs()
        val file = File(dir, "${validFilename(name, MAX_NAME - 5)}.${ext ?: "png"}")
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error(localized(DMR.strings.could_not_write, file.name))
            }
        }
        return file
    }

    /** Puts the image on the system clipboard. */
    fun copy(bytes: ByteArray) {
        val image = toAwt(bytes)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
    }

    /**
     * The two pages side by side, centered on the taller one over [background] (ARGB), as a JPEG,
     * as Android's combined page. [leftFirst] puts [first] on the left.
     */
    fun combine(first: ByteArray, second: ByteArray, leftFirst: Boolean, background: Int): ByteArray {
        val a = decode(first)
        // The first image is closed when the second one does not decode
        val b = try {
            decode(second)
        } catch (e: Throwable) {
            a.close()
            throw e
        }
        try {
            val height = maxOf(a.height, b.height)
            val surface = Surface.makeRasterN32Premul(a.width + b.width, height)
            try {
                val canvas = surface.canvas
                canvas.clear(background)
                val (left, right) = if (leftFirst) a to b else b to a
                canvas.drawImage(left, 0f, ((height - left.height) / 2).toFloat())
                canvas.drawImage(right, left.width.toFloat(), ((height - right.height) / 2).toFloat())
                surface.makeImageSnapshot().use { snapshot ->
                    return snapshot.encodeToData(EncodedImageFormat.JPEG, 100)?.bytes ?: error(localized(DMR.strings.could_not_encode_the_combined_page))
                }
            } finally {
                surface.close()
            }
        } finally {
            a.close()
            b.close()
        }
    }

    /** The extension the image's bytes say it is, or null when it is not a known image format. */
    fun extension(bytes: ByteArray): String? {
        fun at(i: Int, text: String) = bytes.size >= i + text.length &&
            text.indices.all { bytes[i + it] == text[it].code.toByte() }
        fun byte(i: Int, value: Int) = bytes.size > i && bytes[i] == value.toByte()
        return when {
            byte(0, 0xFF) && byte(1, 0xD8) && byte(2, 0xFF) -> "jpg"
            byte(0, 0x89) && at(1, "PNG") -> "png"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(0, "GIF8") -> "gif"
            at(4, "ftypavif") || at(4, "ftypavis") -> "avif"
            at(4, "ftypheic") || at(4, "ftypheix") || at(4, "ftypmif1") -> "heif"
            byte(0, 0xFF) && byte(1, 0x0A) -> "jxl"
            at(0, "BM") -> "bmp"
            else -> null
        }
    }

    /** A file name with the characters Windows and macOS refuse replaced, no longer than [maxBytes] in UTF-8. */
    fun validFilename(name: String, maxBytes: Int = MAX_NAME): String {
        val cleaned = name.map { c -> if (c < ' ' || c in "\\/:*?\"<>|") '_' else c }.joinToString("")
            .trim().trimEnd('.').ifBlank { "image" }
        val out = StringBuilder()
        var size = 0
        for (c in cleaned) {
            val bytes = c.toString().toByteArray(Charsets.UTF_8).size
            if (size + bytes > maxBytes) break
            out.append(c)
            size += bytes
        }
        return out.toString().trim().trimEnd('.').ifBlank { "image" }
    }

    private fun decode(bytes: ByteArray): Image = try {
        Image.makeFromEncoded(bytes)
    } catch (e: Exception) {
        // Skia reads JPEG, PNG, WebP, GIF and BMP; ImageIO with TwelveMonkeys covers a few more
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException(localized(DMR.strings.not_an_image), e)
        Image.makeFromEncoded(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
    }

    private fun toPng(bytes: ByteArray): ByteArray = decode(bytes).use { image ->
        image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error(localized(DMR.strings.could_not_encode_the_page))
    }

    /** An opaque AWT image: Windows pastes transparent parts of a clipboard image as black. */
    private fun toAwt(bytes: ByteArray): BufferedImage {
        val decoded = ImageIO.read(ByteArrayInputStream(toPng(bytes))) ?: error(localized(DMR.strings.not_an_image))
        val image = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.drawImage(decoded, 0, 0, null)
        g.dispose()
        return image
    }

    private class ImageTransferable(private val image: BufferedImage) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }

    /** Android's file name limit, in bytes */
    private const val MAX_NAME = 240
}
