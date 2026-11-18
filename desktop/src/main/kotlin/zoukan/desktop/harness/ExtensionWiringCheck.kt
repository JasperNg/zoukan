package zoukan.desktop.harness

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.extension.model.Extension
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
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.ExtensionClassLoader
import zoukan.extension.LoadResult
import java.io.File
import java.io.IOException
import java.lang.reflect.ParameterizedType
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.minutes

/**
 * Checks that every extension of a store is wired up on the desktop. The network probe never
 * leaves this computer; building a source's client, its filters and its settings screen, and the
 * settings' change listeners, run the extension's own code, which may contact its site.
 *
 * It installs and loads the extension, then checks
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
 * `<data>/reports/extensions-wiring-<store>-<platform>.md`.
 */
class ExtensionWiringCheck(
    private val dirs: ZoukanDirs,
    private val storeService: ExtensionStoreService,
    private val installer: DesktopExtensionInstaller,
    private val loader: DesktopExtensionLoader,
    private val options: Options,
) {
    data class Options(
        /** The store's index URL. The app names no store: the caller passes one. */
        val storeUrl: String = "",
        /** Names the store in the report and trace files (see [CompatibilityHarness.Options.storeId]). */
        val storeId: String = "store",
        /** Package names, with or without the eu.kanade.tachiyomi.extension. prefix. Empty means all. */
        val packages: List<String> = emptyList(),
        val skipNsfw: Boolean = false,
        /** Extensions checked at the same time. */
        val parallelism: Int = 6,
        /** Package names (as [packages]) to leave out. */
        val exclude: List<String> = emptyList(),
        /** Fails the run when the store lists fewer extensions (a moved or emptied store). */
        val minExtensions: Int = 0,
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
        /** Hosts other than the local server that the network probe was sent to, and was stopped from reaching. */
        val blockedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
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

    /** The class loaders of the extensions being checked, by package: a timed-out one is closed from outside its worker. */
    private val loaders = ConcurrentHashMap<String, ExtensionClassLoader>()

    /** The stack trace of every failure, for finding what in the app is missing. */
    private val traces = File(dirs.reports, "extensions-wiring-traces-${options.storeId}-${Platform.id}.txt")

    private fun trace(row: Row, step: String, e: Throwable) = synchronized(traces) {
        traces.appendText("== ${row.pkg} ($step)\n" + e.stackTraceToString().lines().take(40).joinToString("\n") + "\n\n")
    }

    fun run(): Pair<Report, List<Row>> = runBlocking {
        require(options.storeUrl.isNotBlank()) { "No store URL: the app names no store, so the caller must give one" }
        val report = Report("Zoukan extension wiring")
        traces.parentFile.mkdirs()
        traces.writeText("")
        val store = storeService.fetch(options.storeUrl).getOrThrow()
        val available = storeService.getExtensions(store).getOrThrow()
        CompatibilityHarness.checkStoreSize(options.storeUrl, available.size, options.minExtensions)
        fun names(list: List<String>) = list.map { if (it.startsWith(PREFIX)) it else PREFIX + it }.toSet()
        val excluded = names(options.exclude)
        val wanted = if (options.packages.isEmpty()) {
            available.filterNot { options.skipNsfw && it.isNsfw }
        } else {
            val names = names(options.packages)
            available.filter { it.pkgName in names }
        }.filterNot { it.pkgName in excluded }
        report.line("Store: ${store.name} (${options.storeUrl}), ${available.size} extensions, ${wanted.size} checked")
        report.line(
            "The network probe never leaves this computer. Building a source's client, its filters and its " +
                "settings screen, and the settings' change listeners, run the extension's own code, which may contact its site.",
        )
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
        // Each extension runs on a thread of its own, so one that blocks for good (a listener or
        // interceptor waiting on something that never comes) is left behind at the timeout
        // instead of holding up the run
        val workers = Executors.newCachedThreadPool { r -> Thread(r, "wiring-check").apply { isDaemon = true } }
        val rows = try {
            wanted.mapIndexed { i, extension ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val row = Row(extension.pkgName.removePrefix(PREFIX), extension.versionName)
                        val work = workers.submit { checkExtension(extension, store, row, base) }
                        try {
                            runInterruptible { work.get(EXTENSION_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS) }
                        } catch (_: TimeoutException) {
                            work.cancel(true)
                            // The worker may stay blocked and never reach its own finally
                            loaders.remove(row.pkg)?.close()
                            row.sourceErrors += "timed out after $EXTENSION_TIMEOUT"
                        } catch (e: ExecutionException) {
                            row.sourceErrors += "check failed: ${CompatibilityHarness.describe(e.cause ?: e)}"
                            trace(row, "check", e.cause ?: e)
                        }
                        println("  [${i + 1}/${wanted.size}] ${row.pkg}: ${if (row.passed) "PASS" else "FAIL"}")
                        row
                    }
                }
            }.awaitAll()
        } finally {
            workers.shutdownNow()
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
        write(report, rows)
        report to rows
    }

    private fun checkExtension(extension: Extension.Available, store: ExtensionStore, row: Row, base: String) {
        val installed = try {
            runBlocking { installer.install(extension) }
        } catch (e: Throwable) {
            e.rethrowIfVmFailure()
            row.load = "install: ${CompatibilityHarness.describe(e)}"
            return
        }
        checkOne(installed, store, row, base)
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
        success.classLoader?.let { loaders[row.pkg] = it }
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
            // Whoever removes it closes it: not again after the coordinator did
            loaders.remove(row.pkg)?.close()
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
            .mapNotNull { cls ->
                // A looked-up type the app does not have cannot even be named
                runCatching { (cls.genericSuperclass as? ParameterizedType)?.actualTypeArguments?.singleOrNull() }
                    .onFailure { row.injekt += "${cls.name}: ${CompatibilityHarness.describe(it)}" }
                    .getOrNull()
            }
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
            val call = probeClient(client, row.blockedHosts)
                .newCall(Request.Builder().url(base + path).headers(headers).build())
            call.execute().use { response ->
                if (!response.isSuccessful) row.networkNotes += "${source.name}: HTTP ${response.code}"
            }
            val received = requests[path]
            val ua = headers["User-Agent"]
            when {
                // An interceptor that sends it elsewhere (the site's own host) is stopped by the probe client
                received == null -> row.networkNotes += "${source.name}: the request did not reach the server"
                ua != null && received["user-agent"] != ua ->
                    row.networkNotes += "${source.name}: User-Agent replaced by its client (sent ${received["user-agent"]})"
            }
        } catch (e: Throwable) {
            e.rethrowIfVmFailure()
            // The probe is not the source's site: an interceptor may insist on a login, https or
            // the site's own responses. Only a failure of the app (a missing class or method, a
            // stub) counts
            val blockedHost = blockedHostOf(e)
            val note = if (blockedHost != null) {
                "${source.name}: an interceptor sent the probe to $blockedHost; blocked"
            } else {
                "${source.name}: ${CompatibilityHarness.describe(e)}"
            }
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
            e.rethrowIfVmFailure()
            row.settings += "${source.name}: the settings screen failed to build: ${CompatibilityHarness.describe(e)}"
            trace(row, "settings", e)
            return
        }
        // Also the preferences in a category, as the settings screen lists them
        zoukan.desktop.ui.settings.flattenPreferences(screen).forEach { pref ->
            val key = pref.key ?: return@forEach
            val preferences = source.sourcePreferences()
            // What is stored, not the default: put back as it was, also after a failure
            val stored = preferences.all
            val hadValue = key in stored
            val before = stored[key]
            try {
                val changed = changedValue(pref) ?: return@forEach
                row.settingsChecked++
                // As the settings screen saves a value: a change listener may refuse it
                if (!pref.callChangeListener(changed)) return@forEach
                pref.saveNewValue(changed)
                val readBack = source.sourcePreferences().all[key]
                if (readBack != changed) {
                    row.settings += "${source.name}: $key saved $changed but reads back $readBack"
                }
            } catch (e: Throwable) {
                e.rethrowIfVmFailure()
                row.settings += "${source.name}: $key: ${CompatibilityHarness.describe(e)}"
                trace(row, "settings $key", e)
            } finally {
                runCatching { restore(preferences, key, hadValue, before) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restore(preferences: android.content.SharedPreferences, key: String, hadValue: Boolean, value: Any?) {
        val editor = preferences.edit()
        when {
            !hadValue || value == null -> editor.remove(key)
            value is Boolean -> editor.putBoolean(key, value)
            value is String -> editor.putString(key, value)
            value is Int -> editor.putInt(key, value)
            value is Long -> editor.putLong(key, value)
            value is Float -> editor.putFloat(key, value)
            value is Set<*> -> editor.putStringSet(key, value as Set<String>)
        }
        editor.commit()
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
        is androidx.preference.SeekBarPreference -> {
            val current = (pref.currentValue as? Int) ?: pref.min
            if (current < pref.max) current + 1 else pref.min
        }
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
                        val problem = knownReason(gap) ?: gap.reason
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
        rows.filter { it.blockedHosts.isNotEmpty() }.sortedBy { it.pkg }.forEach { row ->
            report.line("- ${row.pkg}: blocked ${row.blockedHosts.sorted().take(8).joinToString(", ")}")
        }
        report.line()
    }

    fun reportFile(): File = File(dirs.reports, "extensions-wiring-${options.storeId}-${Platform.id}.md")

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

        private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]", "::1")

        /** Why a probe was stopped: an interceptor sent it to a host that is not the local server. */
        private const val BLOCKED = "blocked by the wiring check"

        /**
         * The source's client, set up for the local network probe. The probe may only reach the local
         * server: a request that an interceptor sends to another host is stopped before any lookup or
         * connection, and the host is added to [blocked].
         */
        internal fun probeClient(client: OkHttpClient, blocked: MutableCollection<String>): OkHttpClient =
            client.newBuilder()
                .callTimeout(30, TimeUnit.SECONDS)
                // After the source's own interceptors: sees the request as they rewrote it
                .addInterceptor { chain ->
                    val host = chain.request().url.host
                    if (host !in LOOPBACK && "[$host]" !in LOOPBACK) {
                        blocked += host
                        throw IOException("$BLOCKED: $host is not the local probe server")
                    }
                    chain.proceed(chain.request())
                }
                // Also covers a redirect or retry: every connection of this client resolves here
                .dns { host ->
                    if (host in LOOPBACK) {
                        // Only loopback names get here: nothing leaves the computer
                        InetAddress.getAllByName(host.removeSurrounding("[", "]")).toList()
                    } else {
                        blocked += host
                        throw UnknownHostException("$BLOCKED: $host")
                    }
                }
                .build()

        /** The host the probe was sent to, when [e] says the guard stopped it. */
        internal fun blockedHostOf(e: Throwable): String? = generateSequence(e) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.startsWith("$BLOCKED: ") }
            ?.removePrefix("$BLOCKED: ")?.substringBefore(' ')

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
            "me.zhanghai.android.libarchive." to
                "Android-only native archive library (all.jellyfin's own use of it): not available on the desktop",
            "android.os.ResultReceiver" to
                "Gets a result back from the extension's own Activity (pt.mangalivre's reader check), which the " +
                "desktop cannot open",
        )

        /**
         * Libraries that extensions bundle and the app has too, by package prefix, with the reason.
         * Only the bundled class itself is known (the app's copy runs instead): a member or class
         * the app's copy lacks is still a missing API, since the extension's call to it fails.
         */
        val KNOWN_SHADOWED = mapOf(
            "org.chromium.support_lib_boundary." to
                "Interfaces of androidx.webkit that the extension bundles and the app has too; the extension " +
                "runs the app's copy, which declares the same interfaces",
            "nl.adaptivity.xmlutil." to
                "The XML library that the extension bundles and the app has too; the extension runs the app's copy",
        )

        /** Why [gap] is missing on purpose, or null when it is not. */
        fun knownReason(gap: ApiLinkage.Gap): String? =
            KNOWN.entries.firstOrNull { gap.symbol.startsWith(it.key) }?.value
                ?: KNOWN_SHADOWED.entries.takeIf { gap.reason == ApiLinkage.SHADOWED }
                    ?.firstOrNull { gap.symbol.startsWith(it.key) }?.value

        fun kindOf(gap: ApiLinkage.Gap): GapKind = when {
            gap.activityOnly -> GapKind.ACTIVITY
            knownReason(gap) != null -> GapKind.KNOWN
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
