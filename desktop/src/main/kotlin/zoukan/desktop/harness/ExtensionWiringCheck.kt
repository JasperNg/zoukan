package zoukan.desktop.harness

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.ExtensionClassLoader
import zoukan.extension.LoadResult
import java.io.File
import java.lang.reflect.ParameterizedType
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * Checks that every extension of a store is wired up on the desktop, without calling any manga
 * site: it installs and loads the extension, then checks
 *
 * - linkage: every class, method and field of the app that its bytecode uses exists ([ApiLinkage]),
 *   so no code path fails with NoClassDefFoundError or NoSuchMethodError;
 * - Injekt: every type it looks up through Injekt has a registration;
 * - sources: each source instantiates, and its client, headers and filters build;
 * - network: a request through each source's own client (its interceptors) to a local server
 *   arrives with the source's headers;
 * - settings: each configurable source's preference screen builds, and every switch, list,
 *   multi-select and text setting saves a new value that reads back from its SharedPreferences.
 *
 * It needs the network only to download the extensions. The report goes to
 * `<data>/reports/extensions-wiring-<platform>.md`.
 */
class ExtensionWiringCheck(
    private val dirs: ZoukanDirs,
    private val storeService: ExtensionStoreService,
    private val installer: DesktopExtensionInstaller,
    private val loader: DesktopExtensionLoader,
    private val options: Options,
) {
    data class Options(
        val storeUrl: String = CompatibilityHarness.DEFAULT_STORE,
        /** Package names, with or without the eu.kanade.tachiyomi.extension. prefix. Empty means all. */
        val packages: List<String> = emptyList(),
        val skipNsfw: Boolean = false,
        /** Extensions checked at the same time. */
        val parallelism: Int = 6,
    )

    /** One extension's result. Each list holds the problems found by that check. */
    data class Row(
        val pkg: String,
        val version: String,
        var sources: Int = 0,
        var load: String = "",
        val linkage: MutableList<ApiLinkage.Gap> = mutableListOf(),
        val injekt: MutableList<String> = mutableListOf(),
        val sourceErrors: MutableList<String> = mutableListOf(),
        val network: MutableList<String> = mutableListOf(),
        /** Failures of the local network probe that are the extension's own rules (a login, https only), not the app's. */
        val networkNotes: MutableList<String> = mutableListOf(),
        val settings: MutableList<String> = mutableListOf(),
        var settingsChecked: Int = 0,
        var interceptors: Int = 0,
    ) {
        val loaded get() = load == "ok"

        /** The missing APIs that are the app's to provide (see [kindOf]). */
        val appGaps get() = linkage.filter { kindOf(it) == GapKind.APP }

        val passed get() = loaded && appGaps.isEmpty() && injekt.isEmpty() && sourceErrors.isEmpty() &&
            network.isEmpty() && settings.isEmpty()
    }

    private val linkage = ApiLinkage(javaClass.classLoader)
    private val requests = ConcurrentHashMap<String, Map<String, String>>()

    /** The stack trace of every failure, for finding what in the app is missing. */
    private val traces = File(dirs.reports, "extensions-wiring-traces-${Platform.id}.txt")

    private fun trace(row: Row, step: String, e: Throwable) = synchronized(traces) {
        traces.appendText("== ${row.pkg} ($step)\n" + e.stackTraceToString().lines().take(40).joinToString("\n") + "\n\n")
    }

    fun run(): Pair<Report, List<Row>> = runBlocking {
        val report = Report("Zoukan extension wiring")
        traces.parentFile.mkdirs()
        traces.writeText("")
        val store = storeService.fetch(options.storeUrl).getOrThrow()
        val available = storeService.getExtensions(store).getOrThrow()
        val wanted = if (options.packages.isEmpty()) {
            available.filterNot { options.skipNsfw && it.isNsfw }
        } else {
            val names = options.packages.map { if (it.startsWith(PREFIX)) it else PREFIX + it }.toSet()
            available.filter { it.pkgName in names }
        }
        report.line("Store: ${store.name} (${options.storeUrl}), ${available.size} extensions, ${wanted.size} checked")
        report.line()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests[exchange.requestURI.path] = exchange.requestHeaders.mapValues { it.value.joinToString(",") }
                    .mapKeys { it.key.lowercase() }
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val gate = Semaphore(options.parallelism)
        val rows = try {
            wanted.mapIndexed { i, extension ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val row = Row(extension.pkgName.removePrefix(PREFIX), extension.versionName)
                        val done = withTimeoutOrNull(EXTENSION_TIMEOUT) {
                            val installed = try {
                                installer.install(extension)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                row.load = "install: ${CompatibilityHarness.describe(e)}"
                                return@withTimeoutOrNull Unit
                            }
                            runInterruptible { checkOne(installed, store, row, base) }
                        }
                        if (done == null) row.sourceErrors += "timed out after $EXTENSION_TIMEOUT"
                        println("  [${i + 1}/${wanted.size}] ${row.pkg}: ${if (row.passed) "PASS" else "FAIL"}")
                        row
                    }
                }
            }.awaitAll()
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
        write(report, rows)
        report to rows
    }

    private fun checkOne(installed: DesktopExtensionInstaller.Installed, store: ExtensionStore, row: Row, base: String) {
        // The linkage check reads the jar as it is installed, whether or not it loads
        row.linkage += runCatching { linkage.check(classesOf(installed.files.jar)) }
            .getOrElse { listOf(ApiLinkage.Gap("(jar)", CompatibilityHarness.describe(it))) }

        val result = loader.load(installed.files, listOf(store))
        val success = when (result) {
            is LoadResult.Success -> result
            is LoadResult.Untrusted -> {
                row.load = "untrusted: signer is not the store's key"
                return
            }
            is LoadResult.Error -> {
                row.load = "${result.error.stage.name.lowercase()}: ${CompatibilityHarness.describe(result.error)}"
                trace(row, "load", result.error)
                return
            }
        }
        row.load = "ok"
        val sources = success.extension.sources
        row.sources = sources.size
        try {
            success.classLoader?.let { checkInjekt(it, installed.files.jar, row) }
            sources.forEach { source ->
                checkSource(source, row)
                if (source is HttpSource) checkNetwork(source, row, base)
                if (source is ConfigurableSource) checkSettings(source, row)
            }
        } finally {
            success.classLoader?.close()
        }
    }

    private fun classesOf(jar: File): Map<String, ByteArray> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
            .associate { it.name.removeSuffix(".class") to zip.getInputStream(it).readBytes() }
    }

    /** Injekt's reified lookups compile to an anonymous TypeReference subclass whose type argument is the type looked up. */
    private fun checkInjekt(classLoader: ExtensionClassLoader, jar: File, row: Row) {
        val typeReference = uy.kohesive.injekt.api.TypeReference::class.java
        val names = ZipFile(jar).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && '$' in it }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .toList()
        }
        names.asSequence()
            .mapNotNull { runCatching { Class.forName(it, false, classLoader) }.getOrNull() }
            .filter { it.isAnonymousClass && typeReference.isAssignableFrom(it) }
            .mapNotNull { (it.genericSuperclass as? ParameterizedType)?.actualTypeArguments?.singleOrNull() }
            .filter { it is Class<*> || it is ParameterizedType }
            .distinctBy { it.typeName }
            .forEach { type ->
                runCatching { Injekt.getInstance<Any>(type) }
                    .onFailure { row.injekt += "${type.typeName}: ${CompatibilityHarness.describe(it)}" }
            }
    }

    private fun checkSource(source: Source, row: Row) {
        fun step(name: String, block: () -> Unit) {
            runCatching(block).onFailure {
                row.sourceErrors += "${source.name} ($name): ${CompatibilityHarness.describe(it)}"
                trace(row, name, it)
            }
        }
        step("name") {
            source.name
            source.lang
            source.id
        }
        if (source is HttpSource) {
            step("baseUrl") { source.baseUrl }
            step("headers") { source.headers }
            step("client") {
                val client = source.client
                row.interceptors = maxOf(row.interceptors, client.interceptors.size + client.networkInterceptors.size)
            }
        }
        if (source is CatalogueSource) step("filters") { source.getFilterList() }
    }

    /** One request through the source's client to the local server: its interceptors run and its headers arrive. */
    private fun checkNetwork(source: HttpSource, row: Row, base: String) {
        val headers = runCatching { source.headers }.getOrNull() ?: return
        val client = runCatching { source.client }.getOrNull() ?: return
        val path = "/wiring/${source.id}"
        try {
            val call = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(base + path).headers(headers).build())
            call.execute().use { response ->
                if (!response.isSuccessful) row.networkNotes += "${source.name}: HTTP ${response.code}"
            }
            val received = requests[path]
            val ua = headers["User-Agent"]
            when {
                // An interceptor may send it elsewhere (the site's own host), which is the extension's rule
                received == null -> row.networkNotes += "${source.name}: the request did not reach the server"
                ua != null && received["user-agent"] != ua ->
                    row.networkNotes += "${source.name}: User-Agent replaced by its client (sent ${received["user-agent"]})"
            }
        } catch (e: Throwable) {
            // The probe is not the source's site: an interceptor may insist on a login, https or
            // the site's own responses. Only a failure of the app (a missing class or method, a
            // stub) counts
            val note = "${source.name}: ${CompatibilityHarness.describe(e)}"
            if (isAppFailure(e)) row.network += note else row.networkNotes += note
            trace(row, "network", e)
        }
    }

    private fun checkSettings(source: ConfigurableSource, row: Row) {
        val screen = try {
            PreferenceScreen(Injekt.get<Application>()).also {
                it.sharedPreferences = source.sourcePreferences()
                source.setupPreferenceScreen(it)
            }
        } catch (e: Throwable) {
            row.settings += "${source.name}: the settings screen failed to build: ${CompatibilityHarness.describe(e)}"
            trace(row, "settings", e)
            return
        }
        screen.preferences.toList().forEach { pref ->
            val key = pref.key ?: return@forEach
            try {
                val changed = changedValue(pref) ?: return@forEach
                row.settingsChecked++
                val before = pref.currentValue
                // As the settings screen saves a value: a change listener may refuse it
                if (!pref.callChangeListener(changed)) return@forEach
                pref.saveNewValue(changed)
                val stored = source.sourcePreferences().all[key]
                if (stored != changed) {
                    row.settings += "${source.name}: $key saved $changed but reads back $stored"
                }
                if (before != null && pref.callChangeListener(before)) pref.saveNewValue(before)
            } catch (e: Throwable) {
                row.settings += "${source.name}: $key: ${CompatibilityHarness.describe(e)}"
                trace(row, "settings $key", e)
            }
        }
    }

    /** A value different from [pref]'s current one, of the type it stores; null for preferences that store nothing. */
    private fun changedValue(pref: Preference): Any? = when (pref) {
        is TwoStatePreference -> !((pref.currentValue as? Boolean) ?: false)
        is ListPreference -> pref.entryValues?.map { it.toString() }?.firstOrNull { it != pref.currentValue }
        is MultiSelectListPreference -> {
            val values = pref.entryValues?.map { it.toString() }.orEmpty()

            @Suppress("UNCHECKED_CAST")
            val current = (pref.currentValue as? Set<String>).orEmpty()
            values.firstOrNull()?.let { first -> if (first in current) current - first else current + first }
        }
        is EditTextPreference -> ((pref.currentValue as? String) ?: "") + "x"
        else -> null
    }

    private fun write(report: Report, rows: List<Row>) {
        val loaded = rows.count { it.loaded }
        report.line("Loaded: $loaded of ${rows.size}. Passed every check: ${rows.count { it.passed }} of ${rows.size}.")
        report.line(
            "Settings checked: ${rows.sumOf { it.settingsChecked }} in ${rows.count { it.settingsChecked > 0 }} extensions. " +
                "Sources: ${rows.sumOf { it.sources }}.",
        )
        report.line()

        // Gaps shared by several extensions are compat-layer gaps, not one extension's bug
        val gaps = rows.flatMap { row -> row.linkage.map { it to row.pkg } }.groupBy({ it.first }, { it.second })
        GapKind.entries.forEach { kind ->
            val ofKind = gaps.filterKeys { kindOf(it) == kind }
            report.line("## ${kind.title} (${ofKind.size})")
            report.line()
            report.line(kind.description)
            report.line()
            if (ofKind.isEmpty()) {
                report.line("None.")
                report.line()
                return@forEach
            }
            report.table(
                listOf("Symbol", "Problem", "Extensions", "Used by"),
                ofKind.entries.sortedWith(compareByDescending<Map.Entry<ApiLinkage.Gap, List<String>>> { it.value.size }.thenBy { it.key.symbol })
                    .map { (gap, pkgs) ->
                        val problem = KNOWN.entries.firstOrNull { gap.symbol.startsWith(it.key) }?.value ?: gap.reason
                        listOf(gap.symbol, problem, pkgs.size.toString(), pkgs.take(8).joinToString() + if (pkgs.size > 8) ", ..." else "")
                    },
            )
        }

        val injekt = rows.flatMap { row -> row.injekt.map { it to row.pkg } }.groupBy({ it.first }, { it.second })
        report.line("## Missing Injekt registrations (${injekt.size})")
        report.line()
        injekt.forEach { (type, pkgs) -> report.line("- $type (${pkgs.size}: ${pkgs.take(8).joinToString()})") }
        if (injekt.isEmpty()) report.line("None.")
        report.line()

        report.line("## Extensions with problems")
        report.line()
        report.table(
            listOf("Extension", "Version", "Load", "Sources", "App gaps", "Sources failing", "Network", "Settings"),
            rows.filterNot { it.passed }.sortedBy { it.pkg }.map { row ->
                listOf(
                    row.pkg,
                    row.version,
                    row.load,
                    row.sources.toString(),
                    row.appGaps.size.takeIf { it > 0 }?.toString().orEmpty(),
                    row.sourceErrors.take(3).joinToString("; "),
                    row.network.take(3).joinToString("; "),
                    row.settings.take(3).joinToString("; "),
                )
            },
        )

        val notes = rows.filter { it.networkNotes.isNotEmpty() }
        report.line("## Extension or site, not ours: network probe notes (${notes.size})")
        report.line()
        report.line(
            "A request through each source's own client to a local server. These failed by the extension's own " +
                "rules (a login, https only, a site-specific response), which is expected off the real site.",
        )
        report.line()
        notes.sortedBy { it.pkg }.forEach { report.line("- ${it.pkg}: ${it.networkNotes.take(2).joinToString("; ")}") }
        report.line()
    }

    fun reportFile(): File = File(dirs.reports, "extensions-wiring-${Platform.id}.md")

    /** Who a missing API is for. */
    enum class GapKind(val title: String, val description: String) {
        APP(
            "Missing APIs",
            "Used by extension code that runs on the desktop, and missing from the app or only a stub: each is a " +
                "crash waiting on some code path.",
        ),
        UI(
            "Android views and dialogs",
            "Android UI (views, dialogs, EditText listeners of settings) that extensions build on Android only. " +
                "The desktop draws its own settings and never runs this code.",
        ),
        ACTIVITY(
            "Activity only",
            "Used only by an extension's Activity (the deep-link activity Android opens for a site's URL), which " +
                "the desktop never starts.",
        ),
        KNOWN(
            "Known, not supported",
            "Missing on purpose; the reason is given for each.",
        ),
    }

    companion object {
        private const val PREFIX = "eu.kanade.tachiyomi.extension."
        private val EXTENSION_TIMEOUT = 3.minutes

        /** Android UI classes: see [GapKind.UI]. */
        private val UI_PREFIXES = listOf(
            "android.widget.",
            "android.view.",
            "android.app.Dialog",
            "android.app.AlertDialog",
            "android.text.method.",
            "android.graphics.drawable.",
        )

        /** Missing on purpose, by symbol prefix, with the reason. */
        val KNOWN = mapOf(
            "okhttp3.Interceptor\$Chain." to
                "The extension's own Chain wrapper delegates OkHttp API newer than the app's (5.3); the app's " +
                "OkHttp never calls it",
            "dalvik.system.DexClassLoader" to "Loads Android dex code at run time, which the JVM cannot run",
            "android.graphics.pdf." to "PDF chapters through Android's PdfRenderer: not implemented",
            "android.os.ParcelFileDescriptor" to "Opens files for PdfRenderer: not implemented",
            "android.app.Activity." to
                "Needs the current Activity, to show a dialog (a captcha or login). The desktop has no Activity, " +
                "so the extension's own lookup finds none and the call fails with its own error",
            "android.os.ResultReceiver" to
                "Gets a result back from the extension's own Activity (pt.mangalivre's reader check), which the " +
                "desktop cannot open",
        )

        fun kindOf(gap: ApiLinkage.Gap): GapKind = when {
            gap.activityOnly -> GapKind.ACTIVITY
            KNOWN.keys.any { gap.symbol.startsWith(it) } -> GapKind.KNOWN
            UI_PREFIXES.any { gap.symbol.startsWith(it) } -> GapKind.UI
            else -> GapKind.APP
        }

        /** The app gaps of [rows], each with the extensions that use it. */
        fun appGaps(rows: List<Row>): Map<String, List<String>> =
            rows.flatMap { row -> row.appGaps.map { it.symbol to row.pkg } }.groupBy({ it.first }, { it.second })

        /** A failure that is the app's: a class or member it lacks, or a stub of android.jar. */
        fun isAppFailure(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            it is LinkageError || (it is RuntimeException && it.message == "Stub!")
        }
    }
}
