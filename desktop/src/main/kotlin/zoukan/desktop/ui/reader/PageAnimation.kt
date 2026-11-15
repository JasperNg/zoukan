package zoukan.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.AnimationDisposalMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo

/**
 * An animated page (GIF or WebP), played as Android's reader plays them: frame by frame, each for
 * its own duration, looping as the file says. Only the frame shown is kept, decoded from the file
 * when its turn comes, so a long animation takes no more memory than one still page.
 *
 * [frame] is snapshot state: what draws it is redrawn when it changes. Frames advance only while
 * [play] runs, which the page's composable does while it is on screen.
 */
class PageAnimation private constructor(
    private val codec: Codec,
    private val timeline: FrameTimeline,
    private val requiredFrames: IntArray,
    private val restoresPrevious: BooleanArray,
) {
    val width: Int = codec.width
    val height: Int = codec.height
    val frameCount: Int get() = timeline.frameCount

    // Frames are composited here, off the UI thread, then copied to the shown bitmap on it
    private val work = newBitmap()
    private val shown = newBitmap()

    // Two wrappers of the shown bitmap, so each new frame is a new value for the state
    private val wrappers = arrayOf(shown.asComposeImageBitmap(), shown.asComposeImageBitmap())
    private var wrapper = 0

    /** The frame the work bitmap holds, and the frame shown with the loop it is in. */
    private var workFrame = -1
    var frameIndex: Int = 0
        private set
    private var loop = 0

    private val mutex = Mutex()

    var frame: ImageBitmap by mutableStateOf(wrappers[0])
        private set

    init {
        decode(0)
        publish(0, 0)
    }

    /**
     * Plays the animation until it ends or the caller is cancelled (the page left the screen).
     * Callers take turns: a page drawn twice (both halves of a split page) plays once.
     */
    suspend fun play() {
        mutex.withLock {
            var shownAt = System.nanoTime()
            while (true) {
                val (next, nextLoop) = timeline.next(frameIndex, loop) ?: return
                // Decoding runs on a worker thread; the shown bitmap changes only on the caller's (UI) thread
                if (workFrame != next) {
                    try {
                        withContext(Dispatchers.Default) { decode(next) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A broken frame stops the animation on the last good one, as a still page
                        workFrame = -1
                        return
                    }
                }
                val wait = timeline.delayMillis(frameIndex) - (System.nanoTime() - shownAt) / 1_000_000
                if (wait > 0) delay(wait)
                publish(next, nextLoop)
                shownAt = System.nanoTime()
            }
        }
    }

    private fun newBitmap(): Bitmap {
        val info = ImageInfo.makeN32Premul(width, height)
        return Bitmap().also { it.allocPixels(info) }
    }

    /** Moves to the next frame at once; false when the animation is over. For tests. */
    internal fun advance(): Boolean {
        val (next, nextLoop) = timeline.next(frameIndex, loop) ?: return false
        if (workFrame != next) decode(next)
        publish(next, nextLoop)
        return true
    }

    /** Composites frame [index] in the work bitmap, from the frame it holds when Skia can build on it. */
    private fun decode(index: Int) {
        val prior = FrameTimeline.priorFrame(index, workFrame, requiredFrames, restoresPrevious)
        if (prior == -1) work.erase(0)
        codec.readPixels(work, index, prior)
        workFrame = index
    }

    private fun publish(index: Int, loop: Int) {
        work.peekPixels()?.let { from -> shown.peekPixels()?.let { to -> from.readPixels(to) } }
        shown.notifyPixelsChanged()
        frameIndex = index
        this.loop = loop
        wrapper = wrapper xor 1
        frame = wrappers[wrapper]
    }

    companion object {
        /**
         * The animation in [bytes], or null when they hold one frame (a still GIF or WebP), are no
         * animation Skia reads, or are taller or wider than a page slice may be (shown still).
         */
        fun of(bytes: ByteArray): PageAnimation? {
            val codec = runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)) }.getOrNull() ?: return null
            val animation = runCatching {
                val tooLarge = codec.width > PageImage.MAX_SLICE || codec.height > PageImage.MAX_SLICE
                if (codec.frameCount <= 1 || tooLarge) return@runCatching null
                val frames = codec.framesInfo
                PageAnimation(
                    codec,
                    FrameTimeline(IntArray(frames.size) { frames[it].duration }, codec.repetitionCount),
                    IntArray(frames.size) { frames[it].requiredFrame },
                    BooleanArray(frames.size) { frames[it].disposalMethod == AnimationDisposalMode.RESTORE_PREVIOUS },
                )
            }.getOrNull()
            if (animation == null) codec.close()
            return animation
        }
    }
}

/**
 * Plays [image]'s animation while this composable is on screen: it stops when the page scrolls or
 * turns away (the composable leaves) and goes on from the same frame when it comes back.
 */
@Composable
fun PlayAnimation(image: PageImage) {
    val animation = image.animation ?: return
    LaunchedEffect(animation) { animation.play() }
}

/**
 * When the frames of an animation show: [durations] in milliseconds, and [repetitions] as Skia
 * gives them (-1 forever, 0 once, n for n more times).
 */
internal class FrameTimeline(private val durations: IntArray, private val repetitions: Int) {
    val frameCount: Int get() = durations.size

    /** How long frame [index] shows; very short durations are slowed down as browsers and Android do. */
    fun delayMillis(index: Int): Long = durations[index].let { if (it <= MIN_DURATION) DEFAULT_DURATION else it.toLong() }

    /** The frame after [index] and the loop it is in, or null when the animation is over on [loop]. */
    fun next(index: Int, loop: Int): Pair<Int, Int>? = when {
        index + 1 < frameCount -> index + 1 to loop
        repetitions < 0 || loop < repetitions -> 0 to loop + 1
        else -> null
    }

    companion object {
        private const val MIN_DURATION = 10
        private const val DEFAULT_DURATION = 100L

        /**
         * The frame Skia may draw frame [index] over when the bitmap holds frame [held]: one from the
         * frame [index] requires up to the one before it, and not one that restores the previous
         * frame. -1 (decode from scratch) otherwise, and for frames that need no earlier frame.
         */
        fun priorFrame(index: Int, held: Int, required: IntArray, restoresPrevious: BooleanArray): Int {
            val need = required[index]
            if (need < 0 || held < need || held >= index) return -1
            return if (restoresPrevious[held]) -1 else held
        }
    }
}
