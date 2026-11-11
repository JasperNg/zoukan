package zoukan.desktop.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.extension.DesktopExtensionManager
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `zoukan library update`: a full library update, then optionally a download run, from the command
 * line. It is the Phase 5 gate on a real library: it must finish with no errors.
 */
object LibraryCommand {
    data class Options(
        val ignoreRestrictions: Boolean = false,
        /** Newest chapters to download per entry; 0 for none */
        val download: Int = 0,
        val report: File? = null,
    )

    /** @return the exit code: 0 when everything updated and downloaded */
    fun run(options: Options): Int = runBlocking {
        val extensions = Injekt.get<DesktopExtensionManager>()
        extensions.init()
        Injekt.get<SourceManager>().isInitialized.first { it }
        val updater = Injekt.get<LibraryUpdater>()
        val downloads = Injekt.get<DownloadManager>()

        val started = System.nanoTime()
        println("Updating the library...")
        val progress = CoroutineScope(Dispatchers.IO).launch {
            var last = -1
            updater.progress.collect { p ->
                if (p != null && p.total > 0 && p.done != last) {
                    last = p.done
                    println("[${p.done}/${p.total}] ${p.current.joinToString()}")
                }
            }
        }
        val result = updater.run(ignoreRestrictions = options.ignoreRestrictions)
        progress.cancel()
        val updateTime = Duration.ofNanos(System.nanoTime() - started)
        println("Updated ${result.updated} entries in ${updateTime.toSeconds()} s: ${result.newChapters} new chapters, ${result.failures.size} failed, ${result.skipped.size} skipped")
        result.failures.forEach { println("  FAILED ${it.manga.title}: ${it.message}") }

        val downloadErrors = mutableListOf<Download>()
        var downloaded = 0
        if (options.download > 0) {
            val getChapters = Injekt.get<GetChaptersByMangaId>()
            val failedIds = result.failures.mapTo(HashSet()) { it.manga.id }
            result.entries.filterNot { it.id in failedIds }.forEach { manga ->
                val newest = getChapters.await(manga.id).sortedBy { it.sourceOrder }.take(options.download)
                downloads.enqueue(manga, newest, start = false)
            }
            val queued = downloads.queue.value.toList()
            println("Downloading ${queued.size} chapters...")
            downloads.start()
            downloads.awaitIdle()
            queued.forEach { d ->
                when (d.state.value) {
                    Download.State.DOWNLOADED -> downloaded++
                    else -> downloadErrors += d
                }
            }
            println("Downloaded $downloaded chapters, ${downloadErrors.size} failed")
            downloadErrors.forEach { println("  FAILED ${it.manga.title} / ${it.chapter.name}: ${it.error ?: it.state.value}") }
        }
        val total = Duration.ofNanos(System.nanoTime() - started)

        options.report?.let { file ->
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(markdown(result, downloaded, downloadErrors, options, updateTime, total))
            println("Report saved to $file")
        }
        if (result.failures.isEmpty() && downloadErrors.isEmpty()) 0 else 1
    }

    private fun markdown(
        result: LibraryUpdater.Result,
        downloaded: Int,
        downloadErrors: List<Download>,
        options: Options,
        updateTime: Duration,
        total: Duration,
    ): String = buildString {
        val sources = Injekt.get<SourceManager>()
        appendLine("# Library update and download run, ${Platform.id}")
        appendLine()
        appendLine("${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}, data folder ${Injekt.get<ZoukanDirs>().root}")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Entries updated | ${result.updated} |")
        appendLine("| Skipped by the update settings | ${result.skipped.size}${if (options.ignoreRestrictions) " (skip rules off)" else ""} |")
        appendLine("| New chapters | ${result.newChapters} |")
        appendLine("| Update failures | ${result.failures.size} |")
        appendLine("| Update time | ${updateTime.toSeconds()} s |")
        if (options.download > 0) {
            appendLine("| Chapters downloaded (newest ${options.download} per entry) | $downloaded |")
            appendLine("| Download failures | ${downloadErrors.size} |")
        }
        appendLine("| Total time | ${total.toSeconds()} s |")
        appendLine()
        appendLine(if (result.failures.isEmpty() && downloadErrors.isEmpty()) "**Result: passed**" else "**Result: failed**")
        if (result.failures.isNotEmpty()) {
            appendLine()
            appendLine("## Update failures")
            appendLine()
            result.failures.forEach { appendLine("- ${it.manga.title} (${sources.getOrStub(it.manga.source)}): ${it.message}") }
        }
        if (downloadErrors.isNotEmpty()) {
            appendLine()
            appendLine("## Download failures")
            appendLine()
            downloadErrors.forEach { appendLine("- ${it.manga.title} / ${it.chapter.name}: ${it.error ?: it.state.value}") }
        }
        if (result.skipped.isNotEmpty()) {
            appendLine()
            appendLine("## Skipped")
            appendLine()
            result.skipped.groupBy({ it.second }, { it.first.title }).forEach { (reason, titles) ->
                appendLine("- $reason: ${titles.size}")
            }
        }
    }
}
