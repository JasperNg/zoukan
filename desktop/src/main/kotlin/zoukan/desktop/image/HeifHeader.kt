package zoukan.desktop.image

/**
 * Reads the size of an AVIF or HEIF image from its header, without decoding it: the `ispe`
 * (image spatial extents) property of the primary item, as libavif and libheif do for their size.
 */
internal object HeifHeader {
    private class Box(val type: String, val start: Int, val end: Int)

    /**
     * The primary image's width and height, or null when the header does not tell them plainly:
     * no `ispe`, or a `clap` crop that FFmpeg applies when it decodes (the decoded size differs).
     */
    fun primaryImageSize(bytes: ByteArray): Pair<Int, Int>? =
        runCatching { read(bytes) }.getOrNull()

    private fun read(bytes: ByteArray): Pair<Int, Int>? {
        val meta = boxes(bytes, 0, bytes.size).firstOrNull { it.type == "meta" } ?: return null
        // meta is a full box: version and flags before its children
        val children = boxes(bytes, meta.start + 4, meta.end)
        val pitm = children.firstOrNull { it.type == "pitm" } ?: return null
        val primary = if (bytes[pitm.start].toInt() == 0) u16(bytes, pitm.start + 4) else u32(bytes, pitm.start + 4)
        val iprp = children.firstOrNull { it.type == "iprp" } ?: return null
        val iprpChildren = boxes(bytes, iprp.start, iprp.end)
        val ipco = iprpChildren.firstOrNull { it.type == "ipco" } ?: return null
        val properties = boxes(bytes, ipco.start, ipco.end)
        val indexes = iprpChildren.filter { it.type == "ipma" }.firstNotNullOfOrNull { associations(bytes, it, primary) } ?: return null
        // Property indexes count from 1; 0 means no property
        val associated = indexes.mapNotNull { properties.getOrNull(it - 1) }
        if (associated.any { it.type == "clap" }) return null
        val ispe = associated.firstOrNull { it.type == "ispe" } ?: return null
        val width = u32(bytes, ispe.start + 4)
        val height = u32(bytes, ispe.start + 8)
        if (width <= 0 || height <= 0 || width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return width.toInt() to height.toInt()
    }

    /** The property indexes [ipma] gives [item], or null when it lists no such item. */
    private fun associations(bytes: ByteArray, ipma: Box, item: Long): List<Int>? {
        val version = bytes[ipma.start].toInt()
        val wideIndexes = (bytes[ipma.start + 3].toInt() and 1) != 0
        var p = ipma.start + 4
        val count = u32(bytes, p)
        p += 4
        for (i in 0 until count) {
            val id: Long
            if (version < 1) {
                id = u16(bytes, p)
                p += 2
            } else {
                id = u32(bytes, p)
                p += 4
            }
            val n = bytes[p].toInt() and 0xFF
            p++
            val indexes = (0 until n).map {
                if (wideIndexes) {
                    (u16(bytes, p).toInt() and 0x7FFF).also { p += 2 }
                } else {
                    (bytes[p].toInt() and 0x7F).also { p++ }
                }
            }
            if (p > ipma.end) return null
            if (id == item) return indexes
        }
        return null
    }

    /** The boxes from [from] to [to]; each box's start is past its header. */
    private fun boxes(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val list = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(bytes, p)
            val type = String(CharArray(4) { (bytes[p + 4 + it].toInt() and 0xFF).toChar() })
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = (u32(bytes, p + 8) shl 32) or u32(bytes, p + 12)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) break
            list += Box(type, p + header, (p + size).toInt())
            p = (p + size).toInt()
        }
        return list
    }

    private fun u16(bytes: ByteArray, p: Int): Long = ((bytes[p].toLong() and 0xFF) shl 8) or (bytes[p + 1].toLong() and 0xFF)

    private fun u32(bytes: ByteArray, p: Int): Long = (u16(bytes, p) shl 16) or u16(bytes, p + 2)
}
