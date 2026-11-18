package zoukan.desktop.browser.blocking

import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The block lists the user added by address, kept in [folder] (one file per address). A list that
 * cannot be downloaded keeps its last good copy; a list that is removed loses its file.
 *
 * [download] fetches one address and returns its text; it throws on any failure and is expected to
 * refuse bodies larger than [MAX_LIST_BYTES].
 */
class UserBlockLists(
    private val folder: File,
    private val download: (String) -> String,
) {
    /** The hosts and exception hosts of every list in [urls] that has a saved copy. Never throws. */
    fun load(urls: List<String>): BlockRules {
        val hosts = ArrayList<String>()
        val exceptions = ArrayList<String>()
        urls.forEach { url ->
            val text = runCatching { fileFor(url).takeIf { it.isFile }?.readText() }
                .onFailure { logcat(LogPriority.WARN, it) { "A saved block list could not be read" } }
                .getOrNull() ?: return@forEach
            val room = (MAX_TOTAL_HOSTS - hosts.size - exceptions.size).coerceAtLeast(0)
            val rules = BlockListParser.parseRules(text, room)
            hosts += rules.hosts
            exceptions += rules.exceptions
        }
        return BlockRules(HostSet.of(hosts), HostSet.of(exceptions))
    }

    /**
     * Downloads every list in [urls] and saves the ones that hold at least one host or exception,
     * then deletes the copies of lists no longer in [urls]. Returns how many lists were updated.
     * Never throws.
     */
    fun refresh(urls: List<String>): Int {
        var updated = 0
        urls.forEach { url ->
            try {
                val text = download(url)
                if (BlockListParser.parseRules(text, limit = 1).isEmpty()) {
                    logcat(LogPriority.WARN) { "A block list held no hosts; the previous copy is kept" }
                } else {
                    save(fileFor(url), text)
                    updated++
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "A block list could not be downloaded; the previous copy is kept" }
            }
        }
        prune(urls)
        return updated
    }

    private fun save(target: File, text: String) {
        folder.mkdirs()
        val temp = File(folder, "${target.name}.tmp")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    /** Deletes the saved lists (and leftover temporary files) that [urls] no longer names. */
    private fun prune(urls: List<String>) {
        val keep = urls.mapTo(HashSet()) { fileFor(it).name }
        runCatching {
            folder.listFiles()?.forEach { file ->
                val ours = file.isFile && (file.name.endsWith(LIST_SUFFIX) || file.name.endsWith("$LIST_SUFFIX.tmp"))
                if (ours && file.name !in keep) file.delete()
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Removing old block lists failed" } }
    }

    internal fun fileFor(url: String): File = File(folder, sha256(url).take(32) + LIST_SUFFIX)

    companion object {
        /** The most addresses the user can add. */
        const val MAX_LISTS = 10

        /** The largest list downloaded. */
        const val MAX_LIST_BYTES = 8L * 1024 * 1024

        /** The most hosts and exceptions all the user's lists together contribute. */
        const val MAX_TOTAL_HOSTS = 400_000

        /** How often the lists are downloaded again (sooner after a failed refresh, see [RefreshBackoff]). */
        const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        private const val LIST_SUFFIX = ".list"

        /**
         * The list addresses in the setting's [text], one per line: https addresses only, without
         * repeats, at most [MAX_LISTS]. Anything else is ignored.
         */
        fun parseUrls(text: String): List<String> =
            text.lineSequence()
                .map { it.trim() }
                .filter { url -> url.toHttpUrlOrNull()?.isHttps == true }
                .distinct()
                .take(MAX_LISTS)
                .toList()

        /** Whether the lists are due to be downloaded again, [lastCheckMs] being the last time. */
        fun isDue(lastCheckMs: Long, nowMs: Long): Boolean =
            lastCheckMs <= 0 || nowMs - lastCheckMs >= REFRESH_INTERVAL_MS || nowMs < lastCheckMs

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
