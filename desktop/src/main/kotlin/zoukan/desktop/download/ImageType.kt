package zoukan.desktop.download

import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageFormats

/** Image types by their first bytes, for page file extensions (Android's ImageUtil.findImageType). */
enum class ImageType(val extension: String, val mime: String) {
    AVIF("avif", "image/avif"),
    GIF("gif", "image/gif"),
    HEIF("heif", "image/heif"),
    JPEG("jpg", "image/jpeg"),
    JXL("jxl", "image/jxl"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    ;

    companion object {
        private val extensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heif", "heic", "jxl", "bmp")

        /** The type of the image in [bytes] by the app's one sniffer (AVIF with the mif1 major brand is AVIF). */
        fun fromBytes(bytes: ByteArray): ImageType? =
            when (ImageFormats.sniff(bytes)) {
                Format.Avif -> AVIF
                Format.Gif -> GIF
                Format.Heif -> HEIF
                Format.Jpeg -> JPEG
                Format.Jxl -> JXL
                Format.Png -> PNG
                Format.Webp -> WEBP
                null -> null
            }

        /**
         * Whether [bytes], which are no known image, look like text: an HTML error or login page, or
         * JSON, that a server sent with status 200. Image formats have control bytes in their first
         * bytes; text has none.
         */
        fun looksLikeText(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val bom = if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB &&
                (bytes[2].toInt() and 0xFF) == 0xBF
            ) {
                3
            } else {
                0
            }
            val end = minOf(bytes.size, bom + 512)
            if (end == bom) return false
            for (i in bom until end) {
                val b = bytes[i].toInt() and 0xFF
                if (b < 0x09 || b in 0x0E..0x1F || b == 0x7F) return false
            }
            return true
        }

        /** Whether [name] looks like an image file by its extension. */
        fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions
    }
}
