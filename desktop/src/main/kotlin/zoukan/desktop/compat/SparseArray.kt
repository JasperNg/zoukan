package zoukan.desktop.compat

import java.util.TreeMap

/**
 * Android's SparseArray, which the desktop's android.jar only has as a stub. Build-time rewrites
 * point the shared app code that needs it here (see build.gradle.kts).
 */
class SparseArray<T>(@Suppress("UNUSED_PARAMETER") initialCapacity: Int = 10) {
    private val map = TreeMap<Int, T>()

    fun put(key: Int, value: T) {
        map[key] = value
    }

    fun get(key: Int): T? = map[key]

    fun size(): Int = map.size

    fun keyAt(index: Int): Int = map.keys.elementAt(index)

    fun valueAt(index: Int): T = map.values.elementAt(index)

    /** In key order; a member, so it wins over androidx's index-based extension. */
    fun forEach(action: (key: Int, value: T) -> Unit) = map.forEach(action)
}
