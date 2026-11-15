package zoukan.desktop.image

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStreamGroupTileGrid
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AV_STREAM_GROUP_PARAMS_TILE_GRID
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.swscale.SWS_ACCURATE_RND
import org.bytedeco.ffmpeg.global.swscale.SWS_BICUBIC
import org.bytedeco.ffmpeg.global.swscale.SWS_CS_DEFAULT
import org.bytedeco.ffmpeg.global.swscale.SWS_FULL_CHR_H_INT
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCoefficients
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.global.swscale.sws_setColorspaceDetails
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes the still images Skia and ImageIO cannot read, AVIF and HEIF, with FFmpeg: the first
 * frame of the primary image, or its tiles put together when the image is a grid.
 *
 * On Android Komikku reads these with its image decoder (libavif, libheif); FFmpeg does the same
 * job here. Alpha planes, which FFmpeg reads as a separate image, are left out: pages and covers
 * are opaque.
 */
object FfmpegImageDecoder {
    init {
        av_log_set_level(AV_LOG_QUIET)
    }

    /** Whether [bytes] start like an image this decoder is for. */
    fun handles(bytes: ByteArray): Boolean = isAvifOrHeif(bytes)

    fun decode(bytes: ByteArray): BufferedImage {
        // FFmpeg reads from a file here, which keeps the native code simple and safe
        val file = File.createTempFile("zoukan-image-", ".bin")
        try {
            file.writeBytes(bytes)
            return decode(file)
        } finally {
            file.delete()
        }
    }

    fun decode(file: File): BufferedImage {
        val format = AVFormatContext(null)
        val path = BytePointer(file.absolutePath, Charsets.UTF_8)
        try {
            if (avformat_open_input(format, path, null, null as AVDictionary?) < 0) throw IOException("Not an image FFmpeg can open")
        } finally {
            path.close()
        }
        try {
            if (avformat_find_stream_info(format, null as PointerPointer<*>?) < 0) throw IOException("Could not read the image's streams")
            val grid = tileGrid(format)
            return if (grid != null) {
                decodeGrid(format, grid.first, grid.second)
            } else {
                val index = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, null as AVCodec?, 0)
                if (index < 0) throw IOException("The file has no image")
                decodeFirstFrames(format, listOf(index)).getValue(index)
            }
        } finally {
            avformat_close_input(format)
        }
    }

    /** The stream group of a grid image, with the indexes of its tiles' streams in the file. */
    private fun tileGrid(format: AVFormatContext): Pair<AVStreamGroupTileGrid, List<Int>>? {
        for (g in 0 until format.nb_stream_groups()) {
            val group = format.stream_groups(g)
            if (group.type() != AV_STREAM_GROUP_PARAMS_TILE_GRID) continue
            val grid = group.params_tile_grid()
            val streams = (0 until grid.nb_tiles()).map { group.streams(grid.offsets_idx(it)).index() }
            return grid to streams
        }
        return null
    }

    private fun decodeGrid(format: AVFormatContext, grid: AVStreamGroupTileGrid, streams: List<Int>): BufferedImage {
        val tiles = decodeFirstFrames(format, streams.distinct())
        val canvas = BufferedImage(grid.coded_width(), grid.coded_height(), BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            streams.forEachIndexed { i, stream ->
                g.drawImage(tiles.getValue(stream), grid.offsets_horizontal(i), grid.offsets_vertical(i), null)
            }
        } finally {
            g.dispose()
        }
        val width = grid.width().takeIf { it > 0 } ?: canvas.width
        val height = grid.height().takeIf { it > 0 } ?: canvas.height
        val x = grid.horizontal_offset().coerceIn(0, canvas.width - 1)
        val y = grid.vertical_offset().coerceIn(0, canvas.height - 1)
        if (x == 0 && y == 0 && width == canvas.width && height == canvas.height) return canvas
        return canvas.getSubimage(x, y, width.coerceAtMost(canvas.width - x), height.coerceAtMost(canvas.height - y))
    }

    /** The first decoded frame of each of [streams], as images. */
    private fun decodeFirstFrames(format: AVFormatContext, streams: List<Int>): Map<Int, BufferedImage> {
        val decoders = HashMap<Int, AVCodecContext>()
        val images = HashMap<Int, BufferedImage>()
        val packet = av_packet_alloc()
        val frame = av_frame_alloc()
        try {
            streams.forEach { decoders[it] = openDecoder(format, it) }

            fun receive(stream: Int) {
                if (stream in images) return
                if (avcodec_receive_frame(decoders.getValue(stream), frame) == 0) images[stream] = toImage(frame)
            }

            while (images.size < streams.size && av_read_frame(format, packet) >= 0) {
                val stream = packet.stream_index()
                val decoder = decoders[stream]
                if (decoder != null && stream !in images) {
                    val sent = avcodec_send_packet(decoder, packet)
                    if (sent < 0 && sent != AVERROR_EAGAIN()) throw IOException("Could not decode the image ($sent)")
                    receive(stream)
                }
                av_packet_unref(packet)
            }
            // Decoders that hold a frame back until the end of the stream
            streams.filter { it !in images }.forEach { stream ->
                avcodec_send_packet(decoders.getValue(stream), null)
                receive(stream)
            }
            streams.firstOrNull { it !in images }?.let { throw IOException("The image could not be decoded") }
            return images
        } finally {
            av_frame_free(frame)
            av_packet_free(packet)
            decoders.values.forEach { avcodec_free_context(it) }
        }
    }

    private fun openDecoder(format: AVFormatContext, stream: Int): AVCodecContext {
        val parameters = format.streams(stream).codecpar()
        // FFmpeg's own AV1 decoder needs hardware decoding; libdav1d and libaom decode in software
        val codec = DECODER_NAMES[parameters.codec_id()].orEmpty().firstNotNullOfOrNull { avcodec_find_decoder_by_name(it) }
            ?: avcodec_find_decoder(parameters.codec_id())
            ?: throw IOException("No decoder for the image's codec")
        val context = avcodec_alloc_context3(codec) ?: throw IOException("Out of memory")
        if (avcodec_parameters_to_context(context, parameters) < 0 || avcodec_open2(context, codec, null as AVDictionary?) < 0) {
            avcodec_free_context(context)
            throw IOException("Could not open the decoder ${codec.name().string}")
        }
        return context
    }

    /** The frame as an ARGB image, its YUV converted with the frame's own matrix and range. */
    private fun toImage(frame: AVFrame): BufferedImage {
        val width = frame.width()
        val height = frame.height()
        val sws = sws_getContext(
            width, height, frame.format(), width, height, AV_PIX_FMT_BGRA,
            SWS_BICUBIC or SWS_ACCURATE_RND or SWS_FULL_CHR_H_INT, null, null, null as DoublePointer?,
        ) ?: throw IOException("Unsupported pixel format ${frame.format()}")
        val stride = width * 4
        val pixels = BytePointer(stride.toLong() * height)
        try {
            val matrix = frame.colorspace().takeIf { it in SWS_MATRICES } ?: SWS_CS_DEFAULT
            val fullRange = if (frame.color_range() == AVCOL_RANGE_JPEG) 1 else 0
            sws_setColorspaceDetails(sws, sws_getCoefficients(matrix), fullRange, sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 shl 16, 1 shl 16)
            val dst = PointerPointer<BytePointer>(1L).put(pixels)
            val dstStride = IntPointer(1L).put(stride)
            try {
                sws_scale(sws, frame.data(), frame.linesize(), 0, height, dst, dstStride)
            } finally {
                dst.close()
                dstStride.close()
            }
            val ints = IntArray(width * height)
            // BGRA in memory is ARGB as a little-endian int
            pixels.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
            return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, ints, 0, width) }
        } finally {
            pixels.close()
            sws_freeContext(sws)
        }
    }

    internal fun isAvifOrHeif(bytes: ByteArray): Boolean {
        fun ascii(offset: Int, text: String) =
            bytes.size >= offset + text.length && text.indices.all { bytes[offset + it].toInt().toChar() == text[it] }
        return ascii(4, "ftyp") && BRANDS.any { ascii(8, it) }
    }

    // The ISO-BMFF major brands of AVIF and HEIF still images
    private val BRANDS = listOf("avif", "avis", "heic", "heix", "heim", "heis", "mif1", "msf1")

    // The matrices sws_getCoefficients knows; AVCOL_SPC_* and SWS_CS_* share these numbers
    private val SWS_MATRICES = setOf(1, 4, 5, 6, 7, 9, 10)

    private val DECODER_NAMES = mapOf(
        org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1 to listOf("libdav1d", "libaom-av1"),
    )
}
