// ZOUKAN: whole file -->
@file:JvmName("ByteArrayImageInput")

package xyz.nulldev.androidcompat.graphics

import javax.imageio.stream.ImageInputStream

/**
 * An ImageIO input over [bytes] itself: no copy of the image and no cache file (TwelveMonkeys'
 * `ByteArrayImageInputStream`). ImageIO's own `MemoryCacheImageInputStream` keeps a second full
 * copy of what it reads; `createImageInputStream` may spill the image to a temporary file.
 */
fun byteArrayImageInputStream(bytes: ByteArray): ImageInputStream =
    com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream(bytes)
// ZOUKAN: whole file <--
