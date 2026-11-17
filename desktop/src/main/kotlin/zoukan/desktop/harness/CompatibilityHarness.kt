package zoukan.desktop.harness

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ui.reader.PageImage
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.LoadResult
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The extension compatibility harness: installs extensions from a store with the desktop
 * pipeline, loads them, and calls popular, latest, search, details and chapters, page list and
 * the first image of one source per extension. Every step is recorded per extension.
 *
 * It is an end-to-end test against live sites, so it runs only locally (Windows and the Mac).
 */
class CompatibilityHarness(
    private val dirs: ZoukanDirs,
    private val storeService: ExtensionStoreService,
    private val installer: DesktopExtensionInstaller,
    private val loader: DesktopExtensionLoader,
    private val options: Options,
    /** Overrides the time one step may take (a test uses one second). */
    internal val stepTimeout: Duration? = null,
) {
    data class Options(
        val storeUrl: String = DEFAULT_STORE,
        /** Names the store in the report and trace files: a [SampleStore.id], or [storeId] of the URL. */
        val storeId: String = KEIYOUSHI.id,
        /** Package names, with or without the eu.kanade.tachiyomi.extension. prefix. Empty means the defaults. */
        val packages: List<String> = emptyList(),
        /**
         * Leaves out [packages] the store does not list instead of failing them: a run over several
         * stores passes each store the same list.
         */
        val skipMissing: Boolean = false,
        /** Fails the run when the store lists fewer extensions (a moved or emptied store). */
        val minExtensions: Int = 0,
        /** Test every extension in the store. */
        val all: Boolean = false,
        val lang: String = "en",
        /** Install and load only; no calls to manga sites. */
        val offline: Boolean = false,
        /** Install from the APK with dex2jar even when the store has a jar. */
        val forceApk: Boolean = false,
        val skipNsfw: Boolean = false,
        /** Cloudflare challenges the hidden browser cannot solve open in a browser window. */
        val interactive: Boolean = false,
    )

    /** One row of the report. A blank cell means the step was not reached. */
    data class Row(
        val pkg: String,
        var version: String = "",
        var install: String = "",
        var source: String = "",
        var popular: String = "",
        var latest: String = "",
        var search: String = "",
        var details: String = "",
        var chapters: String = "",
        var pages: String = "",
        var image: String = "",
        var error: String = "",
    ) {
        /** The extension ran past its deadline and was cancelled; a late failure of its worker does not change the row. */
        @Volatile
        var timedOut = false

        fun fail(message: String) {
            if (!timedOut) error = message
        }

        private val required get() = listOf(popular, details, chapters, pages)

        val passed: Boolean
            get() = error.isEmpty() && install.startsWith("ok") && (popular.isEmpty() || required.all { it.startsWith("ok") })

        fun allErrors(): String = (required + error).joinToString(" ")
    }

    /** The class loaders of the extensions being run, by package: a timed-out one is closed from outside its worker. */
    private val loaders = java.util.concurrent.ConcurrentHashMap<String, zoukan.extension.ExtensionClassLoader>()

    // Leaves time for a Cloudflare challenge in the browser window (30 s hidden, then 3 min)
    private val timeout = stepTimeout ?: if (options.interactive) INTERACTIVE_TIMEOUT else TIMEOUT

    private val traces = File(
        dirs.reports,
        "extensions-traces-${options.storeId}${if (options.forceApk) "-apk" else ""}-${Platform.id}.txt",
    )

    fun run(): Pair<Report, List<Row>> = runBlocking {
        traces.parentFile.mkdirs()
        traces.writeText("")
        val report = Report("Zoukan extension compatibility")
        val store = storeService.fetch(options.storeUrl).getOrThrow()
        val available = storeService.getExtensions(store).getOrThrow()
        checkStoreSize(options.storeUrl, available.size, options.minExtensions)
        report.line("Store: ${store.name} (${options.storeUrl}), ${available.size} extensions, signing key ${store.signingKey.take(16)}…")
        report.line(
            "Mode: " + when {
                options.offline -> "offline (install and load only, no calls to manga sites)"
                else -> "online"
            } + if (options.forceApk) ", APKs converted with dex2jar" else ", store jars where available",
        )
        report.line()

        val wanted = when {
            options.all -> available.filterNot { options.skipNsfw && it.isNsfw }.map { it.pkgName }
            options.packages.isNotEmpty() -> options.packages.map { it.withPrefix() }
            else -> SAMPLE_STORES.firstOrNull { it.url == options.storeUrl }?.packages ?: DEFAULT_PACKAGES
        }.filter { pkg -> !options.skipMissing || available.any { it.pkgName == pkg } }
        // Each extension runs on a worker of its own: withTimeout fires only where a coroutine
        // suspends, so a source blocked in a network read (an Rx source in OkHttp's execute(), a
        // Cloudflare wait) would hold the one thread of this run for good. The worker is left behind
        // at the deadline instead.
        val workers = Executors.newCachedThreadPool { r -> Thread(r, "harness").apply { isDaemon = true } }
        // popular, latest, search, details, pages and the image each have `timeout`; the pages step
        // tries up to three chapters, and an install and a load come on top
        val deadline = timeout * 6
        val rows = try {
            wanted.map { pkg ->
                val row = Row(pkg.removePrefix(PREFIX))
                val extension = available.firstOrNull { it.pkgName == pkg }
                if (extension == null) {
                    row.error = "not in store"
                } else {
                    row.version = extension.versionName
                    val work = workers.submit { runBlocking { runOne(extension, store, row) } }
                    try {
                        runInterruptible { work.get(deadline.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
                    } catch (_: TimeoutException) {
                        // Before the cancel: the interrupt makes the worker's step fail, and that
                        // failure must not replace the verdict
                        if (row.error.isEmpty()) row.error = "timed out after $deadline"
                        row.timedOut = true
                        work.cancel(true)
                        // The worker may stay blocked and never reach its own finally
                        loaders.remove(row.pkg)?.close()
                    } catch (e: ExecutionException) {
                        val cause = e.cause ?: e
                        if (row.error.isEmpty()) row.error = describe(cause)
                        trace(row.pkg, row.error.substringBefore(':'), cause)
                    }
                }
                println("  ${row.pkg}: ${if (row.passed) "PASS" else "FAIL ${row.error}"}")
                row
            }
        } finally {
            workers.shutdownNow()
        }

        report.table(
            listOf(
                "Extension", "Version", "Install", "Source tested", "Popular", "Latest", "Search", "Details",
                "Chapters", "Pages", "First image", "Error",
            ),
            rows.map {
                listOf(
                    it.pkg, it.version, it.install, it.source, it.popular, it.latest, it.search, it.details,
                    it.chapters, it.pages, it.image, it.error,
                )
            },
        )
        val installed = rows.count { it.install.startsWith("ok") }
        report.line("Installed and loaded: $installed of ${rows.size}")
        if (!options.offline) {
            report.line("Passed every required step (popular, details, chapters, pages): ${rows.count { it.passed }} of ${rows.size}")
            val undecodable = rows.filter { it.image.startsWith("undecodable") }
            if (undecodable.isNotEmpty()) {
                report.line("Images the reader cannot decode: ${undecodable.joinToString { "${it.pkg} (${it.image})" }}")
            }
            // A Cloudflare check the hidden browser cannot pass needs the user in the browser
            // window (--interactive); those sources are a known limitation, not a failure
            val cloudflare = rows.filter { !it.passed && CLOUDFLARE_MANUAL in it.allErrors() }
            if (cloudflare.isNotEmpty()) {
                val others = rows.size - cloudflare.size
                report.line(
                    "Without the ${cloudflare.size} that need a manual Cloudflare check " +
                        "(${cloudflare.joinToString { it.pkg }}): ${rows.count { it.passed }} of $others",
                )
            }
        }
        report.line()
        report.line("Latest and search are recorded but not required: some sources have no latest list or no text search.")
        report to rows
    }

    private suspend fun runOne(extension: Extension.Available, store: ExtensionStore, row: Row) {
        val installed = stage(row, "install") { installer.install(extension, options.forceApk) }
        val success = when (val result = loader.load(installed.files, listOf(store))) {
            is LoadResult.Success -> result
            is LoadResult.Untrusted -> {
                row.install = "fail"
                row.error = "signature: signer ${result.extension.signatureHash.take(16)} is not the store key"
                return
            }
            is LoadResult.Error -> {
                row.install = "fail"
                row.error = "${result.error.stage.name.lowercase()}: ${describe(result.error)}"
                trace(row.pkg, "load", result.error)
                return
            }
        }
        // Closed when the extension is done, or by the coordinator if this worker never gets there
        success.classLoader?.let { loaders[row.pkg] = it }
        try {
            val sources = success.extension.sources
            row.install = "ok (${installed.method.name.lowercase()})"
            val source = pickSource(sources)
            row.source = "${source.name} (${source.lang}), ${sources.size} total"
            if (options.offline) return
            val catalogue = source as? CatalogueSource ?: run {
                row.error = "not a CatalogueSource"
                return
            }
            callSource(catalogue, row)
        } finally {
            // Whoever removes it closes it: not again after the coordinator did
            loaders.remove(row.pkg)?.close()
        }
    }

    private suspend fun callSource(source: CatalogueSource, row: Row) {
        val popular = step(row, "popular") { source.getPopularManga(1) }
        row.popular = "ok ${popular.mangas.size}"
        val manga = popular.mangas.firstOrNull() ?: run {
            row.error = "popular: empty"
            return
        }

        if (source.supportsLatest) {
            row.latest = optional(row, "latest") { "ok ${source.getLatestUpdates(1).mangas.size}" }
        } else {
            row.latest = "n/a"
        }
        val query = manga.title.split(' ').firstOrNull { it.length >= 3 } ?: manga.title
        row.search = optional(row, "search") { "ok ${source.getSearchManga(1, query, source.getFilterList()).mangas.size}" }

        // extensions-lib 1.6 fetches details and chapters in one call
        val update = step(row, "details") {
            source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        }
        row.details = "ok"
        val chapters = update.chapters
        row.chapters = "ok ${chapters.size}"
        if (chapters.isEmpty()) {
            row.error = "chapters: empty"
            return
        }

        // Newest chapters are often paywalled or locked: try the oldest, the newest, then one in the middle
        val candidates = listOf(chapters.last(), chapters.first(), chapters[chapters.size / 2]).distinctBy { it.url }
        var pages: List<Page> = emptyList()
        var lastError: Throwable? = null
        for (chapter in candidates) {
            pages = runCatching { withTimeout(timeout) { source.getPageList(chapter) } }
                .onFailure { lastError = it }
                .getOrDefault(emptyList())
            if (pages.isNotEmpty()) break
        }
        if (pages.isEmpty()) {
            lastError?.let { trace(row.pkg, "pages", it) }
            row.pages = "fail"
            row.fail("pages: " + (lastError?.let(::describe) ?: "empty"))
            return
        }
        row.pages = "ok ${pages.size}"

        // The first image, through the source's own client and headers, as the reader would load it
        val http = source as? HttpSource ?: return
        runCatching {
            withTimeout(timeout) {
                val page = pages.first()
                page.imageUrl = page.imageUrl ?: http.getImageUrl(page)
                http.getImage(page).use { response ->
                    val type = response.body.contentType()?.toString() ?: "?"
                    val bytes = response.body.bytes()
                    row.image = when {
                        !response.isSuccessful -> "HTTP ${response.code}"
                        // As the reader decodes it: a format the app cannot show (AVIF, JPEG XL) fails here
                        else -> runCatching { PageImage.decode(bytes) }.fold(
                            onSuccess = { "ok $type ${bytes.size / 1024} KB" },
                            onFailure = { "undecodable $type: ${describe(it)}" },
                        )
                    }
                }
            }
        }.onFailure {
            row.image = "fail: ${describe(it)}"
            trace(row.pkg, "image", it)
        }
    }

    private fun pickSource(sources: List<Source>): Source =
        sources.firstOrNull { it.lang == options.lang } ?: sources.firstOrNull { it.lang == "all" } ?: sources.first()

    private inline fun <T> stage(row: Row, name: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        row.install = "fail"
        row.fail("$name: ${describe(e)}")
        throw e
    }

    private suspend fun <T> step(row: Row, name: String, block: suspend () -> T): T = try {
        withTimeout(timeout) { block() }
    } catch (e: Throwable) {
        when (name) {
            "popular" -> row.popular = "fail"
            "details" -> row.details = "fail"
        }
        row.fail("$name: ${describe(e)}")
        throw e
    }

    private suspend fun optional(row: Row, name: String, block: suspend () -> String): String = try {
        withTimeout(timeout) { block() }
    } catch (e: Throwable) {
        trace(row.pkg, name, e)
        "fail: ${describe(e)}"
    }

    private fun trace(pkg: String, step: String, e: Throwable) {
        traces.appendText("== $pkg ($step)\n" + e.stackTraceToString().lines().take(40).joinToString("\n") + "\n\n")
    }

    private fun String.withPrefix() = if (startsWith(PREFIX)) this else PREFIX + this

    /**
     * A store the harness and the wiring check test, with its sample of extensions for the live
     * run and the fewest extensions it should list (fewer means the store moved or was emptied).
     */
    data class SampleStore(val id: String, val url: String, val packages: List<String>, val minExtensions: Int)

    companion object {
        const val DEFAULT_STORE = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json"
        private const val PREFIX = "eu.kanade.tachiyomi.extension."
        // Sources that load their chapter list in a WebView take over a minute for one step (en.comix:
        // about 85 s); the app itself puts no limit on details and chapters
        private val TIMEOUT = 2.minutes
        private const val CLOUDFLARE_MANUAL = "Failed to bypass Cloudflare"
        private val INTERACTIVE_TIMEOUT = 5.minutes

        /** The Phase 0 sample: big multi-language sources plus common site engines. */
        val DEFAULT_PACKAGES = listOf(
            "all.mangadex", "all.mangaplus", "all.webtoons", "all.mangafire", "all.kagane", "all.mangaball",
            "en.weebcentral", "en.asurascans", "en.flamecomics", "en.mangapill", "en.mangakatana", "en.mangabuddy",
            "en.mangakakalot", "en.mangahere", "en.mangatown", "en.mangago", "en.toonily", "en.comix",
            "en.tapastic", "en.templescan",
        ).map { PREFIX + it }

        val KEIYOUSHI = SampleStore(
            id = "keiyoushi",
            url = DEFAULT_STORE,
            packages = DEFAULT_PACKAGES + listOf(
                "all.cubari", "all.namicomi", "all.globalcomix", "all.mangatoon", "all.mangataro", "en.mangafox",
                "en.manhuaplus", "en.rizzcomic", "en.thunderscans", "en.drakescans",
            ).map { PREFIX + it },
            minExtensions = 1000,
        )

        /**
         * yuzono's "Cursed" store: six NSFW extensions with their own signing key. Only the harness
         * and the tests use it, so its extensions go through the same checks; the app does not
         * add or trust it (desktop/THREAT-MODEL.md), a user adds it as any other store.
         */
        val YUZONO = SampleStore(
            id = "yuzono",
            url = "https://raw.githubusercontent.com/yuzono/cursed-manga-repo/repo/repo.json",
            packages = listOf(
                "all.ehentai",
                "all.hitomi",
                "all.nhentai",
                "all.pururin",
                "en.hentai20",
                "en.hentai2read",
            ).map { PREFIX + it },
            minExtensions = 1,
        )

        /** The stores the harness and the wiring check run over by default, each on its own. */
        val SAMPLE_STORES = listOf(KEIYOUSHI, YUZONO)

        /** A [SampleStore.id], or a name for another store URL: its GitHub owner, else its host. */
        fun storeId(url: String): String = SAMPLE_STORES.firstOrNull { it.url == url }?.id
            ?: url.substringAfter("://").split('/').let { parts ->
                if (parts.first() == "raw.githubusercontent.com" && parts.size > 1) parts[1] else parts.first()
            }.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "store" }

        /** Fails when a store lists fewer than [min] extensions: it moved, or its index is a stub. */
        fun checkStoreSize(url: String, size: Int, min: Int) = check(size >= min) {
            "The store $url lists $size extensions, expected at least $min: has it moved, or is its index a stub?"
        }

        fun describe(e: Throwable): String {
            val root = generateSequence(e) { it.cause }.last()
            val msg = (root.message ?: "").lines().first().take(160)
            return root.javaClass.simpleName + if (msg.isNotEmpty()) ": $msg" else ""
        }
    }
}
