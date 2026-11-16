// ZOUKAN -->
// androidx.core.util's AtomicFile, for the shared E-Hentai code
@file:JvmName("AndroidxUtilCompat")

package androidx.core.util

import zoukan.desktop.Platform
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes to a new file and moves it over the old one when done, like androidx's AtomicFile.
 *
 * The base file only ever changes by that one move, so it is never half-written. A rename that is
 * refused (on Windows, an antivirus or indexer holding the file) is tried again a few times; if it
 * is still refused, [finishWrite] throws, the caller's [failWrite] removes the new file and the
 * base file keeps its old content. [openRead] deletes a `.new` file left by a crash between the
 * write and the move. The desktop never writes a `.bak` file.
 */
class AtomicFile internal constructor(
    private val baseFile: File,
    private val move: (Path, Path) -> Unit,
) {
    constructor(baseFile: File) : this(
        baseFile,
        { from, to -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) },
    )

    private val newFile = File(baseFile.path + ".new")

    fun openRead(): FileInputStream {
        newFile.delete()
        return FileInputStream(baseFile)
    }

    fun startWrite(): FileOutputStream {
        baseFile.parentFile?.mkdirs()
        return FileOutputStream(newFile)
    }

    fun finishWrite(stream: FileOutputStream?) {
        stream ?: return
        stream.fd.sync()
        stream.close()
        var attempt = 1
        while (true) {
            try {
                move(newFile.toPath(), baseFile.toPath())
                break
            } catch (e: AccessDeniedException) {
                if (attempt >= MOVE_ATTEMPTS) throw e
                Thread.sleep(50L * attempt)
                attempt++
            }
        }
        if (Platform.current != Platform.WINDOWS) {
            runCatching {
                FileChannel.open(baseFile.absoluteFile.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }
        }
    }

    fun failWrite(stream: FileOutputStream?) {
        stream ?: return
        runCatching { stream.close() }
        newFile.delete()
    }

    private companion object {
        const val MOVE_ATTEMPTS = 5
    }
}
// ZOUKAN <--
