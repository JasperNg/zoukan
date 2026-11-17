package zoukan.desktop.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What is in each entry's download folder, read once and checked again cheaply.
 *
 * Names are looked up by a key: the name in Unicode NFC (macOS may hand back NFD names), lowercased
 * on a disk that ignores case (checked once per downloads folder). So "is it downloaded", "where is
 * it", the counts and the deletes all see the same files, whatever the case or normal form on disk.
 *
 * A folder read more than [recheckMillis] ago is checked again on its next use: one `stat` of the
 * folder, and a new listing only when its modified time changed. Files copied in from outside (a
 * synced folder from the Android app) show up within seconds, without re-reading every folder.
 */
internal class DownloadIndex(
    private val recheckMillis: Long = RECHECK_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Listing(
        /** Key to the names on disk with that key (two only on a case-sensitive disk with NFC and NFD copies) */
        val byKey: Map<String, List<String>>,
        val modified: Long,
        val readAt: Long,
        /** Names found to be a real chapter (a folder with files, a CBZ with bytes); only positives are kept */
        val valid: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    ) {
        @Volatile
        var checkedAt: Long = readAt
    }

    private val listings = ConcurrentHashMap<File, Listing>()
    private val caseInsensitive = ConcurrentHashMap<File, Boolean>()

    /** How many folders were listed, for tests and the benchmark. */
    @Volatile
    var listingsRead = 0
        private set

    fun clear() {
        listings.clear()
        caseInsensitive.clear()
    }

    fun invalidate(dir: File) {
        listings.remove(dir)
    }

    fun key(root: File, name: String): String {
        val nfc = Normalizer.normalize(name, Normalizer.Form.NFC)
        return if (isCaseInsensitive(root)) nfc.lowercase(Locale.ROOT) else nfc
    }

    /** Every name in [dir], as on disk. */
    fun names(root: File, dir: File): List<String> = listing(root, dir).byKey.values.flatten()

    /** The names on disk in [dir] that [candidates] name, whether or not they are complete chapters. */
    fun matching(root: File, dir: File, candidates: List<String>): List<String> {
        val listing = listing(root, dir)
        if (listing.byKey.isEmpty()) return emptyList()
        return candidates.asSequence().map { key(root, it) }.distinct()
            .flatMap { listing.byKey[it].orEmpty() }.distinct().toList()
    }

    /** The first of [candidates] in [dir] that is a complete chapter, by its name on disk. */
    fun findValid(root: File, dir: File, candidates: List<String>): String? {
        val listing = listing(root, dir)
        if (listing.byKey.isEmpty()) return null
        for (candidate in candidates) {
            val names = listing.byKey[key(root, candidate)] ?: continue
            names.firstOrNull { isValid(listing, dir, it) }?.let { return it }
        }
        return null
    }

    /** The complete chapters in [dir] by key without `.cbz`, so a folder and a CBZ of one chapter count once. */
    fun chapterKeys(root: File, dir: File): Set<String> {
        val listing = listing(root, dir)
        val keys = HashSet<String>()
        listing.byKey.forEach { (key, names) ->
            names.forEach { name ->
                if (isChapterName(name) && isValid(listing, dir, name)) keys += key.removeSuffix(".cbz").removeSuffix(".CBZ")
            }
        }
        return keys
    }

    private fun isValid(listing: Listing, dir: File, name: String): Boolean {
        if (name in listing.valid) return true
        val valid = isCompleteChapter(File(dir, name))
        if (valid) listing.valid += name
        return valid
    }

    private fun listing(root: File, dir: File): Listing {
        val now = clock()
        val cached = listings[dir]
        if (cached != null) {
            if (now - cached.checkedAt < recheckMillis) return cached
            val modified = modifiedTime(dir)
            // A change within the disk's time resolution of the read may not move the time: read again
            val trusted = cached.modified < cached.readAt - TIME_RESOLUTION_MILLIS
            if (modified == cached.modified && trusted) {
                cached.checkedAt = now
                return cached
            }
        }
        val modified = modifiedTime(dir)
        val names = dir.list().orEmpty()
        listingsRead++
        val listing = Listing(names.groupBy { key(root, it) }, modified, now)
        listings[dir] = listing
        return listing
    }

    private fun modifiedTime(dir: File): Long = try {
        Files.getLastModifiedTime(dir.toPath()).toMillis()
    } catch (e: IOException) {
        MISSING
    }

    /**
     * Whether names on this disk ignore case, checked once per downloads folder: the nearest folder
     * that exists is looked up with its name's case changed. Without such a folder, the system's
     * default (macOS and Windows ignore case).
     */
    private fun isCaseInsensitive(root: File): Boolean = caseInsensitive.getOrPut(root) {
        generateSequence(root.absoluteFile) { it.parentFile }
            .firstOrNull { it.exists() && it.parentFile != null && it.name.lowercase() != it.name.uppercase() }
            ?.let { existing ->
                val swapped = existing.name.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
                File(existing.parentFile, swapped).exists()
            }
            ?: System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "win" in it }
    }

    companion object {
        const val RECHECK_MILLIS = 3_000L
        private const val TIME_RESOLUTION_MILLIS = 2_000L
        private const val MISSING = -1L

        /** A folder or CBZ that may be a chapter: not an unfinished download, a hidden file or a cover. */
        fun isChapterName(name: String): Boolean =
            !name.endsWith(DownloadPaths.TMP_SUFFIX) && !name.startsWith(".") && NOT_CHAPTER.none { name.endsWith(it, ignoreCase = true) }

        /** A folder with at least one file in it, or a CBZ with bytes in it. An empty one is no download. */
        fun isCompleteChapter(file: File): Boolean = try {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            when {
                attrs.isDirectory -> Files.newDirectoryStream(file.toPath()).use { it.iterator().hasNext() }
                attrs.isRegularFile -> file.name.endsWith(".cbz", ignoreCase = true) && attrs.size() > 0
                else -> false
            }
        } catch (e: IOException) {
            false
        }

        internal val NOT_CHAPTER = listOf(".xml", ".json", ".jpg", ".jpeg", ".png", ".webp", ".txt")
    }
}
