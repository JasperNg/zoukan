package zoukan.desktop.browser.blocking

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.AppScope
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The content blocker of the built-in browser and of extensions' pages: it cancels requests a page
 * makes to known ad, tracker and pop-under hosts of other sites (see [BlockPolicy]).
 *
 * Its list is the app's own (`blocklist.txt` next to this class), plus in the built-in browser any
 * lists the user added by address (see [UserBlockLists]). It is on by default and has a setting
 * ([ContentBlockerPreferences.enabled]). Every check fails open: an error never blocks a request.
 */
object ContentBlocker {
    private const val BUILT_IN_LIST = "blocklist.txt"

    private val builtIn: HostSet by lazy {
        runCatching {
            val text = ContentBlocker::class.java.getResourceAsStream(BUILT_IN_LIST)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("The built-in block list is missing")
            HostSet.of(BlockListParser.parse(text))
        }.onFailure { logcat(LogPriority.ERROR, it) { "The built-in block list could not be read" } }
            .getOrDefault(HostSet.EMPTY)
    }

    private val builtInRules: BlockRules by lazy { BlockRules(builtIn) }

    // The built-in list and the user's lists (with their exceptions) together, for the built-in browser
    @Volatile
    private var withUserLists: BlockRules? = null

    private val backoff = RefreshBackoff()

    private val userListsLoaded = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)

    private val preferences by lazy { ContentBlockerPreferences(Injekt.get()) }

    /** Whether the blocker is on; false when the setting cannot be read. */
    fun isEnabled(): Boolean = runCatching { preferences.enabled().get() }.getOrDefault(false)

    /** Whether to cancel a subresource or frame request in the built-in browser. Never throws. */
    fun shouldBlockInBrowser(requestUrl: String?, pageUrl: String?): Boolean = check {
        val rules = browserRules()
        BlockPolicy.shouldBlock(requestUrl, pageUrl, rules.hosts, rules.exceptions)
    }

    /** Whether to cancel a subresource or frame request in an extension's page (the app's list only). */
    fun shouldBlockInExtension(requestUrl: String?, pageUrl: String?): Boolean =
        check { BlockPolicy.shouldBlock(requestUrl, pageUrl, builtIn) }

    /** Whether a pop-up to [url] goes to a blocked host, for the built-in browser. Never throws. */
    fun isBlockedPopup(url: String?): Boolean = check {
        val target = url?.toHttpUrlOrNull() ?: return@check false
        val rules = browserRules()
        rules.hosts.matches(target.host) &&
            !rules.exceptions.matches(target.host) &&
            !BlockPolicy.isAlwaysAllowed(target)
    }

    private inline fun check(block: () -> Boolean): Boolean {
        if (!isEnabled()) return false
        return try {
            block()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "The content blocker failed; the request goes ahead" }
            false
        }
    }

    private fun browserRules(): BlockRules = withUserLists ?: builtInRules

    /**
     * Loads the user's saved lists once, and downloads them again when they are a week old (or,
     * after a refresh in which none could be downloaded, when [RefreshBackoff] says so). Runs in the
     * background; call it whenever a browser opens. Never throws.
     */
    fun ensureFresh() {
        runCatching {
            val due = backoff.isDue(preferences.listsCheckedAt().get(), System.currentTimeMillis())
            val firstCall = userListsLoaded.compareAndSet(false, true)
            if (!firstCall && !due) return
            AppScope.launch {
                runCatching {
                    when (freshAction(firstCall, due, hasLists = urls().isNotEmpty())) {
                        FreshAction.REFRESH -> refresh()
                        FreshAction.MARK_CHECKED -> {
                            preferences.listsCheckedAt().set(System.currentTimeMillis())
                            reload()
                        }
                        FreshAction.RELOAD -> reload()
                        FreshAction.NOTHING -> Unit
                    }
                }.onFailure { logcat(LogPriority.WARN, it) { "Could not load the block lists" } }
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not load the block lists" } }
    }

    /** What [ensureFresh] does. */
    internal enum class FreshAction { NOTHING, RELOAD, REFRESH, MARK_CHECKED }

    /**
     * The step [ensureFresh] takes: download the lists when they are due and there are any; with
     * none, restart the weekly clock (so later browser windows do nothing until it is due again;
     * [refreshNow] covers lists added in the meantime); else load the saved lists on the first call.
     */
    internal fun freshAction(firstCall: Boolean, due: Boolean, hasLists: Boolean): FreshAction = when {
        due && hasLists -> FreshAction.REFRESH
        due -> FreshAction.MARK_CHECKED
        firstCall -> FreshAction.RELOAD
        else -> FreshAction.NOTHING
    }

    /** Downloads the user's lists now (after the setting changed), then uses them. */
    fun refreshNow() {
        AppScope.launch { refresh() }
    }

    private fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            val urls = urls()
            val updated = lists().refresh(urls)
            logcat { "Downloaded $updated of ${urls.size} block lists" }
            val now = System.currentTimeMillis()
            // The weekly clock restarts only when a list came through; otherwise try again sooner
            if (backoff.recordRefresh(updated, urls.size, now)) preferences.listsCheckedAt().set(now)
            reload()
        } catch (e: Exception) {
            backoff.recordFailure(System.currentTimeMillis())
            logcat(LogPriority.WARN, e) { "Updating the block lists failed" }
        } finally {
            refreshing.set(false)
        }
    }

    private fun reload() {
        runCatching {
            val user = lists().load(urls())
            withUserLists = if (user.isEmpty) null else builtInRules + user
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not load the block lists" } }
    }

    private fun urls(): List<String> = UserBlockLists.parseUrls(preferences.listUrls().get())

    private fun lists() = UserBlockLists(File(Injekt.get<ZoukanDirs>().root, "blocklists"), ::download)

    /** One list over https, through the app's DNS and without cookies; refused past the size cap. */
    private fun download(url: String): String {
        val client = Injekt.get<NetworkHelper>().plainClient.newBuilder().followSslRedirects(false).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            if (!response.request.url.isHttps) throw IOException("Not https")
            val body = response.body
            if (body.contentLength() > UserBlockLists.MAX_LIST_BYTES) throw IOException("The list is too large")
            val bytes = body.byteStream().use { it.readNBytes((UserBlockLists.MAX_LIST_BYTES + 1).toInt()) }
            if (bytes.size > UserBlockLists.MAX_LIST_BYTES) throw IOException("The list is too large")
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
