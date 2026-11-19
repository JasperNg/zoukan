package zoukan.desktop.ui.reader

/**
 * Bounds the pixels decoding at once, so several large pages decoding together cannot take more
 * native memory than one page as large as allowed: each decode takes its declared pixels from
 * [capacity] and gives them back when done. Ordinary pages decode side by side; a page as large as
 * the gate (or of unknown size) decodes alone. Waiting decodes go in the order they came, so a
 * large page is not held back by a stream of small ones. Blocking: for the decode threads.
 */
internal class DecodeGate(private val capacity: Long) {
    private val lock = Object()

    /** Pixels taken by the decodes running now */
    var inUse = 0L
        get() = synchronized(lock) { field }
        private set

    private var nextTicket = 0L
    private var serving = 0L

    /** What a decode of [pixels] takes: at least 1, at most the whole gate; an unknown size takes all of it. */
    fun weightOf(pixels: Long?): Long = pixels?.coerceIn(1, capacity) ?: capacity

    /** Takes [weight] if it fits now and nobody is waiting before it. */
    fun tryAcquire(weight: Long): Boolean = synchronized(lock) {
        if (serving != nextTicket || inUse + weight > capacity) return false
        inUse += weight
        true
    }

    /** Takes [weight], waiting for the decodes before it and for room. */
    fun acquire(weight: Long) = synchronized(lock) {
        val ticket = nextTicket++
        try {
            while (ticket != serving || inUse + weight > capacity) lock.wait()
        } catch (e: InterruptedException) {
            // Give the turn up so the decodes behind this one are not stuck
            if (ticket == serving) serving++ else abandoned += ticket
            skipAbandoned()
            lock.notifyAll()
            throw e
        }
        inUse += weight
        serving++
        skipAbandoned()
        lock.notifyAll()
    }

    fun release(weight: Long) = synchronized(lock) {
        inUse -= weight
        lock.notifyAll()
    }

    /**
     * Runs [decode] with the share for [pixels] taken, and gives it back after. A thread
     * interrupted while it waits, or by the time it has its share (a page load cancelled by
     * fast paging, through runInterruptible), gives the share back without decoding and throws
     * [InterruptedException], so a page nobody waits for no longer delays the others.
     */
    fun <T> withPermit(pixels: Long?, decode: () -> T): T {
        val weight = weightOf(pixels)
        acquire(weight)
        try {
            if (Thread.interrupted()) throw InterruptedException("The decode was cancelled")
            return decode()
        } finally {
            release(weight)
        }
    }

    private val abandoned = mutableSetOf<Long>()

    private fun skipAbandoned() {
        while (abandoned.remove(serving)) serving++
    }
}
