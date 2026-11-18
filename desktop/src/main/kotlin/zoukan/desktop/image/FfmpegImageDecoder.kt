package zoukan.desktop.image

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avformat.AVStreamGroupTileGrid
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
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
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FORCE
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_DEFAULT
import org.bytedeco.ffmpeg.global.avformat.AV_STREAM_GROUP_PARAMS_TILE_GRID
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_get_pix_fmt_name
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.av_malloc
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
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageFormats
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes the still images Skia and ImageIO cannot read, AVIF and HEIF, with FFmpeg: the first
 * frame of the primary image, or its tiles put together when the image is a grid.
 *
 * On Android Komikku reads these with its image decoder (libavif, libheif); FFmpeg does the same
 * job here. Transparency is kept: from a frame with an alpha plane (the yuva pixel formats), or
 * from the AVIF or HEIF alpha auxiliary image, which FFmpeg does not merge but gives as one more
 * stream (or tile grid): a gray image the primary image's size, put in as the alpha channel.
 *
 * JPEG XL is not decoded: the bundled FFmpeg has no libjxl decoder.
 */
object FfmpegImageDecoder {
    // Loads FFmpeg's natives on the first decode, so recognizing other images never touches them
    private val natives: Unit by lazy { av_log_set_level(AV_LOG_QUIET) }

    /** Whether [bytes] start like an image this decoder is for ([ImageFormats.HEADER] bytes are enough). */
    fun handles(bytes: ByteArray): Boolean = ImageFormats.sniff(bytes).let { it == Format.Avif || it == Format.Heif }

    /**
     * Decodes [bytes], which FFmpeg reads from memory (ZOUKAN: never from a temporary file, which
     * would put a decrypted page on the disk).
     */
    fun decode(bytes: ByteArray): BufferedImage {
        loadNatives()
        val io = try {
            MemoryInput.open(bytes)
        } catch (e: LinkageError) {
            throw IOException("FFmpeg could not load", e)
        }
        try {
            val format = avformat_alloc_context() ?: throw IOException("Out of memory")
            format.pb(io.context)
            // On failure FFmpeg frees the context itself (but not the reader, which is ours)
            if (avformat_open_input(format, null as BytePointer?, null as AVInputFormat?, null as AVDictionary?) < 0) {
                throw IOException("Not an image FFmpeg can open")
            }
            return decodeOpened(format)
        } finally {
            io.close()
        }
    }

    fun decode(file: File): BufferedImage {
        loadNatives()
        val format = AVFormatContext(null)
        val path = BytePointer(file.absolutePath, Charsets.UTF_8)
        try {
            if (avformat_open_input(format, path, null, null as AVDictionary?) < 0) throw IOException("Not an image FFmpeg can open")
        } finally {
            path.close()
        }
        return decodeOpened(format)
    }

    private fun loadNatives() {
        try {
            natives
        } catch (e: LinkageError) {
            // Natives missing for this OS or CPU: these images fail, the others still show
            throw IOException("FFmpeg could not load", e)
        }
    }

    /** The image of an opened [format], which is closed afterwards. */
    private fun decodeOpened(format: AVFormatContext): BufferedImage {
        try {
            if (avformat_find_stream_info(format, null as PointerPointer<*>?) < 0) throw IOException("Could not read the image's streams")
            val grids = tileGrids(format)
            val grid = grids.firstOrNull { it.isDefault } ?: grids.firstOrNull()
            if (grid != null) {
                val alpha = grids.firstOrNull { it !== grid && isAlphaGrid(format, it, grid) }
                val alphaStreams = alpha?.streams.orEmpty()
                val frames = decodeFirstFrames(format, (grid.streams + alphaStreams).distinct(), optional = alphaStreams.toSet())
                val color = composeGrid(grid, frames) ?: throw IOException("The image could not be decoded")
                return withAlpha(color, alpha?.let { composeGrid(it, frames) })
            }
            val index = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, null as AVCodec?, 0)
            if (index < 0) throw IOException("The file has no image")
            val alpha = alphaStream(format, index)
            val frames = decodeFirstFrames(format, listOfNotNull(index, alpha), optional = setOfNotNull(alpha))
            return withAlpha(frames.getValue(index), alpha?.let { frames[it] })
        } finally {
            avformat_close_input(format)
        }
    }

    /**
     * FFmpeg's reader over bytes in memory: an AVIOContext whose read and seek callbacks find the
     * bytes by the context's opaque pointer. The callbacks are made once and kept, as JavaCPP has
     * few native slots for them; each open reader has a key of its own.
     */
    private class MemoryInput private constructor(val context: AVIOContext, private val key: Pointer) : AutoCloseable {
        private class Source(val bytes: ByteArray) {
            var position = 0L
        }

        override fun close() {
            sources.remove(key.address())
            // FFmpeg may have replaced the buffer it was given: the one it holds now is freed
            av_free(context.buffer())
            avio_context_free(context)
            av_free(key)
        }

        private object Read : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, buffer: BytePointer?, size: Int): Int = try {
                val source = opaque?.let { sources[it.address()] }
                val left = source?.let { it.bytes.size - it.position } ?: 0L
                val n = minOf(left, size.toLong()).toInt()
                if (source == null || buffer == null || n <= 0) {
                    AVERROR_EOF
                } else {
                    buffer.position(0L).put(source.bytes, source.position.toInt(), n)
                    source.position += n
                    n
                }
            } catch (_: Throwable) {
                AVERROR_EOF
            }
        }

        private object Seek : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, whence: Int): Long = try {
                val source = opaque?.let { sources[it.address()] }
                val size = source?.bytes?.size?.toLong() ?: 0L
                val mode = whence and AVSEEK_FORCE.inv()
                val target = when (mode) {
                    SEEK_SET -> offset
                    SEEK_CUR -> (source?.position ?: 0L) + offset
                    SEEK_END -> size + offset
                    else -> -1L
                }
                when {
                    source == null -> -1L
                    // The size, without moving
                    mode == AVSEEK_SIZE -> size
                    target < 0 || target > size -> -1L
                    else -> {
                        source.position = target
                        target
                    }
                }
            } catch (_: Throwable) {
                -1L
            }
        }

        companion object {
            private const val BUFFER_BYTES = 64 * 1024
            private const val SEEK_SET = 0
            private const val SEEK_CUR = 1
            private const val SEEK_END = 2

            private val sources = ConcurrentHashMap<Long, Source>()

            fun open(bytes: ByteArray): MemoryInput {
                // A byte of FFmpeg's memory, only for its address: the key of this reader's bytes
                val key = av_malloc(1L) ?: throw IOException("Out of memory")
                val buffer = av_malloc(BUFFER_BYTES.toLong())
                if (buffer == null) {
                    av_free(key)
                    throw IOException("Out of memory")
                }
                sources[key.address()] = Source(bytes)
                val context = avio_alloc_context(BytePointer(buffer), BUFFER_BYTES, 0, key, Read, null, Seek)
                if (context == null) {
                    sources.remove(key.address())
                    av_free(buffer)
                    av_free(key)
                    throw IOException("Out of memory")
                }
                return MemoryInput(context, key)
            }
        }
    }

    /** A tile grid of the file, with the indexes of its tiles' streams. */
    private class Grid(val grid: AVStreamGroupTileGrid, val streams: List<Int>, val isDefault: Boolean)

    /** The file's tile grids: the primary image's is the default one; an alpha or other auxiliary grid is not. */
    private fun tileGrids(format: AVFormatContext): List<Grid> =
        (0 until format.nb_stream_groups()).map { format.stream_groups(it) }
            .filter { it.type() == AV_STREAM_GROUP_PARAMS_TILE_GRID }
            .map { group ->
                val grid = group.params_tile_grid()
                val streams = (0 until grid.nb_tiles()).map { group.streams(grid.offsets_idx(it)).index() }
                Grid(grid, streams, (group.disposition() and AV_DISPOSITION_DEFAULT) != 0)
            }

    /** Whether [candidate] is the alpha image of [primary]: a grid of gray tiles the same size. */
    private fun isAlphaGrid(format: AVFormatContext, candidate: Grid, primary: Grid): Boolean =
        !candidate.isDefault &&
            candidate.grid.coded_width() == primary.grid.coded_width() &&
            candidate.grid.coded_height() == primary.grid.coded_height() &&
            candidate.streams.isNotEmpty() &&
            candidate.streams.none { it in primary.streams } &&
            candidate.streams.all { isGray(format, it) }

    /**
     * The stream of the alpha auxiliary image of the image in stream [primary], or null: another
     * stream, not the default one, of gray pixels and the same size. libavif names the item "Alpha".
     */
    private fun alphaStream(format: AVFormatContext, primary: Int): Int? {
        val main = format.streams(primary).codecpar()
        val candidates = (0 until format.nb_streams()).filter { i ->
            val stream = format.streams(i)
            val parameters = stream.codecpar()
            i != primary &&
                parameters.codec_type() == AVMEDIA_TYPE_VIDEO &&
                (stream.disposition() and AV_DISPOSITION_DEFAULT) == 0 &&
                parameters.width() == main.width() &&
                parameters.height() == main.height() &&
                isGray(format, i)
        }
        return candidates.firstOrNull { title(format, it)?.contains("alpha", ignoreCase = true) == true } ?: candidates.firstOrNull()
    }

    private fun isGray(format: AVFormatContext, stream: Int): Boolean =
        av_get_pix_fmt_name(format.streams(stream).codecpar().format())?.string?.startsWith("gray") == true

    private fun title(format: AVFormatContext, stream: Int): String? =
        av_dict_get(format.streams(stream).metadata(), "title", null, 0)?.value()?.string

    /** [color] with the gray levels of [alpha] as its alpha channel, when they are the same size. */
    internal fun withAlpha(color: BufferedImage, alpha: BufferedImage?): BufferedImage {
        if (alpha == null || alpha.width != color.width || alpha.height != color.height) return color
        val width = color.width
        val colors = IntArray(width)
        val levels = IntArray(width)
        for (y in 0 until color.height) {
            color.getRGB(0, y, width, 1, colors, 0, width)
            alpha.getRGB(0, y, width, 1, levels, 0, width)
            // A gray frame converts to equal red, green and blue: red is the level
            for (x in 0 until width) colors[x] = (colors[x] and 0xFFFFFF) or (((levels[x] shr 16) and 0xFF) shl 24)
            color.setRGB(0, y, width, 1, colors, 0, width)
        }
        return color
    }

    /** The grid's tiles from [tiles] put together, or null when a tile is missing. */
    private fun composeGrid(grid: Grid, tiles: Map<Int, BufferedImage>): BufferedImage? {
        if (grid.streams.any { it !in tiles }) return null
        val params = grid.grid
        val canvas = BufferedImage(params.coded_width(), params.coded_height(), BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            // Tiles replace what is under them, alpha included
            g.composite = AlphaComposite.Src
            grid.streams.forEachIndexed { i, stream ->
                g.drawImage(tiles.getValue(stream), params.offsets_horizontal(i), params.offsets_vertical(i), null)
            }
        } finally {
            g.dispose()
        }
        val width = params.width().takeIf { it > 0 } ?: canvas.width
        val height = params.height().takeIf { it > 0 } ?: canvas.height
        val x = params.horizontal_offset().coerceIn(0, canvas.width - 1)
        val y = params.vertical_offset().coerceIn(0, canvas.height - 1)
        if (x == 0 && y == 0 && width == canvas.width && height == canvas.height) return canvas
        return canvas.getSubimage(x, y, width.coerceAtMost(canvas.width - x), height.coerceAtMost(canvas.height - y))
    }

    /**
     * The first decoded frame of each of [streams], as images. A stream in [optional] (an alpha
     * image) that cannot be decoded is left out instead of failing the image.
     */
    private fun decodeFirstFrames(
        format: AVFormatContext,
        streams: List<Int>,
        optional: Set<Int> = emptySet(),
    ): Map<Int, BufferedImage> {
        val decoders = HashMap<Int, AVCodecContext>()
        val images = HashMap<Int, BufferedImage>()
        val packet = av_packet_alloc()
        val frame = av_frame_alloc()
        try {
            streams.forEach { stream ->
                if (stream in optional) {
                    runCatching { openDecoder(format, stream) }.getOrNull()?.let { decoders[stream] = it }
                } else {
                    decoders[stream] = openDecoder(format, stream)
                }
            }
            val wanted = streams.filter { it in decoders }

            fun receive(stream: Int) {
                if (stream in images) return
                if (avcodec_receive_frame(decoders.getValue(stream), frame) == 0) images[stream] = toImage(frame)
            }

            val failed = HashSet<Int>()
            while (images.size + failed.size < wanted.size && av_read_frame(format, packet) >= 0) {
                val stream = packet.stream_index()
                val decoder = decoders[stream]
                if (decoder != null && stream !in images && stream !in failed) {
                    val sent = avcodec_send_packet(decoder, packet)
                    if (sent < 0 && sent != AVERROR_EAGAIN()) {
                        if (stream !in optional) throw IOException("Could not decode the image ($sent)")
                        failed += stream
                    } else {
                        receive(stream)
                    }
                }
                av_packet_unref(packet)
            }
            // Decoders that hold a frame back until the end of the stream
            wanted.filter { it !in images && it !in failed }.forEach { stream ->
                avcodec_send_packet(decoders.getValue(stream), null)
                receive(stream)
            }
            streams.firstOrNull { it !in images && it !in optional }?.let { throw IOException("The image could not be decoded") }
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
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            // BGRA in memory is ARGB as a little-endian int; copied straight into the image's pixels
            pixels.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get((image.raster.dataBuffer as DataBufferInt).data)
            return image
        } finally {
            pixels.close()
            sws_freeContext(sws)
        }
    }

    // The matrices sws_getCoefficients knows; AVCOL_SPC_* and SWS_CS_* share these numbers
    private val SWS_MATRICES = setOf(1, 4, 5, 6, 7, 9, 10)

    private val DECODER_NAMES by lazy {
        mapOf(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1 to listOf("libdav1d", "libaom-av1"))
    }
}
