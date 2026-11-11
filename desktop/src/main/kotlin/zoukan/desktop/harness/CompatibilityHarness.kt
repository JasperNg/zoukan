package zoukan.desktop.harness

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.LoadResult
import java.io.File
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
) {
    data class Options(
        val storeUrl: String = DEFAULT_STORE,
        /** Package names, with or without the eu.kanade.tachiyomi.extension. prefix. Empty means the defaults. */
        val packages: List<String> = emptyList(),
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
        private val required get() = listOf(popular, details, chapters, pages)

        val passed: Boolean
            get() = error.isEmpty() && install.startsWith("ok") && (popular.isEmpty() || required.all { it.startsWith("ok") })

        fun allErrors(): String = (required + error).joinToString(" ")
    }

    // Leaves time for a Cloudflare challenge in the browser window (30 s hidden, then 3 min)
    private val timeout = if (options.interactive) INTERACTIVE_TIMEOUT else TIMEOUT

    private val traces = File(
        dirs.reports,
        "extensions-traces${if (options.forceApk) "-apk" else ""}-${Platform.id}.txt",
    )

    fun run(): Pair<Report, List<Row>> = runBlocking {
        traces.parentFile.mkdirs()
        traces.writeText("")
        val report = Report("Zoukan extension compatibility")
        val store = storeService.fetch(options.storeUrl).getOrThrow()
        val available = storeService.getExtensions(store).getOrThrow()
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
            else -> DEFAULT_PACKAGES
        }
        val rows = wanted.map { pkg ->
            val row = Row(pkg.removePrefix(PREFIX))
            val extension = available.firstOrNull { it.pkgName == pkg }
            if (extension == null) {
                row.error = "not in store"
            } else {
                row.version = extension.versionName
                runCatching { runOne(extension, store, row) }.onFailure { e ->
                    if (row.error.isEmpty()) row.error = describe(e)
                    trace(row.pkg, row.error.substringBefore(':'), e)
                }
            }
            println("  ${row.pkg}: ${if (row.passed) "PASS" else "FAIL ${row.error}"}")
            row
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
        val sources = when (val result = loader.load(installed.files, listOf(store))) {
            is LoadResult.Success -> result.extension.sources
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
        row.install = "ok (${installed.method.name.lowercase()})"
        val source = pickSource(sources)
        row.source = "${source.name} (${source.lang}), ${sources.size} total"
        if (options.offline) return
        val catalogue = source as? CatalogueSource ?: run {
            row.error = "not a CatalogueSource"
            return
        }
        callSource(catalogue, row)
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
            row.error = "pages: " + (lastError?.let(::describe) ?: "empty")
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
                    val size = response.body.bytes().size
                    row.image = if (response.isSuccessful) "ok $type ${size / 1024} KB" else "HTTP ${response.code}"
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
        row.error = "$name: ${describe(e)}"
        throw e
    }

    private suspend fun <T> step(row: Row, name: String, block: suspend () -> T): T = try {
        withTimeout(timeout) { block() }
    } catch (e: Throwable) {
        when (name) {
            "popular" -> row.popular = "fail"
            "details" -> row.details = "fail"
        }
        row.error = "$name: ${describe(e)}"
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

    companion object {
        const val DEFAULT_STORE = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json"
        private const val PREFIX = "eu.kanade.tachiyomi.extension."
        private val TIMEOUT = 60.seconds
        private const val CLOUDFLARE_MANUAL = "Failed to bypass Cloudflare"
        private val INTERACTIVE_TIMEOUT = 5.minutes

        /** The Phase 0 sample: big multi-language sources plus common site engines. */
        val DEFAULT_PACKAGES = listOf(
            "all.mangadex", "all.mangaplus", "all.webtoons", "all.mangafire", "all.kagane", "all.mangaball",
            "en.weebcentral", "en.asurascans", "en.flamecomics", "en.mangapill", "en.mangakatana", "en.mangabuddy",
            "en.mangakakalot", "en.mangahere", "en.mangatown", "en.mangago", "en.toonily", "en.comix",
            "en.tapastic", "en.templescan",
        ).map { PREFIX + it }

        fun describe(e: Throwable): String {
            val root = generateSequence(e) { it.cause }.last()
            val msg = (root.message ?: "").lines().first().take(160)
            return root.javaClass.simpleName + if (msg.isNotEmpty()) ": $msg" else ""
        }
    }
}
