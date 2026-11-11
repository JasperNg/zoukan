// ZOUKAN -->
// androidx.core.util's AtomicFile and SparseArray.forEach, for the shared E-Hentai code
@file:JvmName("AndroidxUtilCompat")

package androidx.core.util

import android.util.SparseArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes to a new file and moves it over the old one when done, like androidx's AtomicFile. */
class AtomicFile(private val baseFile: File) {
    private val newFile = File(baseFile.path + ".new")

    fun openRead(): FileInputStream = FileInputStream(baseFile)

    fun startWrite(): FileOutputStream {
        baseFile.parentFile?.mkdirs()
        return FileOutputStream(newFile)
    }

    fun finishWrite(stream: FileOutputStream?) {
        stream ?: return
        stream.fd.sync()
        stream.close()
        Files.move(newFile.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun failWrite(stream: FileOutputStream?) {
        stream ?: return
        runCatching { stream.close() }
        newFile.delete()
    }
}

inline fun <T> SparseArray<T>.forEach(action: (key: Int, value: T) -> Unit) {
    for (index in 0 until size()) action(keyAt(index), valueAt(index))
}
// ZOUKAN <--
