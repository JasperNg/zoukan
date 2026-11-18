package zoukan.desktop

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.File

/**
 * The native side of [SpotlightExclusion]: Spotlight's per-folder exclusion list on macOS (System
 * Settings > Spotlight > Search Privacy), which needs no administrator rights for a folder the
 * user can read.
 *
 * Neither a `.metadata_never_index` file in the folder nor a hidden flag keeps current macOS from
 * indexing it, and the storage folder's folders cannot take a `.noindex` name (they keep the
 * Android app's layout), so this makes the call that settings pane makes (CoreServices'
 * `_MDSetExclusion` and `_MDCopyExclusionList`). They are not public API: a changed signature in
 * a later macOS could crash the process that calls them. So only [SpotlightExclusionHelper], a
 * short-lived process of its own, calls them; Zoukan never does in its own process.
 */
internal object SpotlightNative {
    /** kCFStringEncodingUTF8 */
    private const val UTF8 = 0x08000100

    /**
     * Excludes [dir] from Spotlight ([exclude] true) or lets Spotlight index it again (false).
     * Returns whether [dir] is in the wanted state now; false for a folder that is not there (when
     * excluding) or when the call failed.
     */
    fun set(dir: File, exclude: Boolean, note: (String) -> Unit): Boolean {
        return try {
            // Spotlight takes only a folder that is there; an entry can be removed in any case
            if (exclude && !dir.isDirectory) return false
            val path = dir.canonicalPath
            if ((path in excludedPaths()) == exclude) return true
            val string = cf.createString(path) ?: return false
            try {
                // A C Boolean (one byte); the call returns nothing useful, so the list is read back
                setExclusion.invokeVoid(arrayOf<Any?>(string, (if (exclude) 1 else 0).toByte()))
            } finally {
                cf.release(string)
            }
            (path in excludedPaths()) == exclude
        } catch (e: Exception) {
            note("Could not change ${dir.path} in Spotlight's Search Privacy list: $e")
            false
        } catch (e: LinkageError) {
            // JNA failed to load, or the call is gone from this macOS
            note("Could not change ${dir.path} in Spotlight's Search Privacy list: $e")
            false
        }
    }

    /** Whether [dir] is in Spotlight's Search Privacy list; false on other systems or when it cannot be read. */
    fun isExcluded(dir: File): Boolean {
        return try {
            dir.canonicalPath in excludedPaths()
        } catch (e: Exception) {
            false
        } catch (e: LinkageError) {
            false
        }
    }

    /** The paths in Spotlight's Search Privacy list. */
    private fun excludedPaths(): Set<String> {
        val array = copyExclusionList.invokePointer(emptyArray()) ?: return emptySet()
        return try {
            (0 until cf.arrayCount(array)).mapNotNull { cf.arrayItem(array, it)?.let(cf::toKotlinString) }.toSet()
        } finally {
            cf.release(array)
        }
    }

    private val coreServices by lazy { NativeLibrary.getInstance("CoreServices") }
    private val setExclusion: Function by lazy { coreServices.getFunction("_MDSetExclusion") }
    private val copyExclusionList: Function by lazy { coreServices.getFunction("_MDCopyExclusionList") }

    /** The few CoreFoundation calls the list needs. */
    private val cf by lazy { CoreFoundation(NativeLibrary.getInstance("CoreFoundation")) }

    private class CoreFoundation(library: NativeLibrary) {
        private val createWithCString = library.getFunction("CFStringCreateWithCString")
        private val getLength = library.getFunction("CFStringGetLength")
        private val getMaximumSize = library.getFunction("CFStringGetMaximumSizeForEncoding")
        private val getCString = library.getFunction("CFStringGetCString")
        private val arrayGetCount = library.getFunction("CFArrayGetCount")
        private val arrayGetValue = library.getFunction("CFArrayGetValueAtIndex")
        private val cfRelease = library.getFunction("CFRelease")

        fun createString(value: String): Pointer? =
            createWithCString.invokePointer(arrayOf<Any?>(null, value.toByteArray(Charsets.UTF_8) + 0, UTF8))

        fun toKotlinString(string: Pointer): String? {
            val length = getLength.invokeLong(arrayOf<Any?>(string))
            val size = getMaximumSize.invokeLong(arrayOf<Any?>(length, UTF8)) + 1
            if (size <= 0 || size > Int.MAX_VALUE) return null
            val buffer = ByteArray(size.toInt())
            // Returns a C Boolean (one byte)
            if (getCString.invoke(java.lang.Byte.TYPE, arrayOf<Any?>(string, buffer, size, UTF8)) == 0.toByte()) return null
            val end = buffer.indexOf(0).takeIf { it >= 0 } ?: buffer.size
            return String(buffer, 0, end, Charsets.UTF_8)
        }

        fun arrayCount(array: Pointer): Int = arrayGetCount.invokeLong(arrayOf<Any?>(array)).toInt()

        fun arrayItem(array: Pointer, index: Int): Pointer? = arrayGetValue.invokePointer(arrayOf<Any?>(array, index.toLong()))

        fun release(value: Pointer) {
            cfRelease.invokeVoid(arrayOf<Any?>(value))
        }
    }
}
