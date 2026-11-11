package zoukan.desktop.download

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

        fun fromBytes(bytes: ByteArray): ImageType? {
            fun at(offset: Int, vararg values: Int) =
                bytes.size >= offset + values.size && values.indices.all { (bytes[offset + it].toInt() and 0xFF) == values[it] }
            fun ascii(offset: Int, text: String) =
                bytes.size >= offset + text.length && text.indices.all { bytes[offset + it].toInt().toChar() == text[it] }
            return when {
                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                at(0, 0x89, 0x50, 0x4E, 0x47) -> PNG
                ascii(0, "GIF8") -> GIF
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                at(0, 0xFF, 0x0A) || at(0, 0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20) -> JXL
                ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> AVIF
                ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1")) -> HEIF
                else -> null
            }
        }

        /** Whether [name] looks like an image file by its extension. */
        fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions
    }
}
