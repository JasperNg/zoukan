package zoukan.desktop.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import java.util.UUID

/**
 * Discord Rich Presence, as the Android app shows it: the screen in use, or the entry and chapter
 * being read with its cover. It talks to the Discord app on this computer ([DiscordIpc]), so it
 * needs Discord running but no login. The settings are the Android app's, under the same keys.
 */
class DiscordPresence(
    private val preferenceStore: PreferenceStore,
    private val getCategories: GetCategories,
    private val incognito: zoukan.desktop.security.Incognito,
) {
    enum class Screen(val label: String, val details: String, val image: String) {
        APP("Zoukan", "Using", KOMIKKU_IMAGE),
        LIBRARY("Library", "Browsing", LIBRARY_IMAGE),
        UPDATES("Updates", "Scrolling through", UPDATES_IMAGE),
        HISTORY("History", "Scrolling through", HISTORY_IMAGE),
        BROWSE("Sources", "Browsing", BROWSE_IMAGE),
        MORE("Settings", "Messing around with", MORE_IMAGE),
        WEBVIEW("WebView", "Browsing", WEBVIEW_IMAGE),
        MANGA("Manga", "Reading", MANGA_IMAGE),
    }

    /** What is being read, for the reader's presence. */
    data class Reading(
        val mangaId: Long,
        val title: String,
        val chapter: String,
        val chapterNumber: Double,
        val coverUrl: String?,
        val sourceId: Long,
    )

    fun enabled() = preferenceStore.getBoolean("pref_enable_discord_rpc", false)
    fun hideReading() = preferenceStore.getBoolean("pref_discord_rpc_incognito", false)
    fun hiddenCategories() = preferenceStore.getStringSet("discord_rpc_incognito_categories", emptySet())
    fun useChapterTitles() = preferenceStore.getBoolean("pref_discord_rpc_use_chapter_titles", false)
    fun customMessage() = preferenceStore.getString("pref_discord_custom_message", "")
    fun showProgress() = preferenceStore.getBoolean("pref_discord_show_progress", true)
    fun showTimestamp() = preferenceStore.getBoolean("pref_discord_show_timestamp", true)
    fun showButtons() = preferenceStore.getBoolean("pref_discord_show_buttons", true)
    fun showDownloadButton() = preferenceStore.getBoolean("pref_discord_show_download_button", true)
    fun showDiscordButton() = preferenceStore.getBoolean("pref_discord_show_discord_button", true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val since = System.currentTimeMillis()

    @Volatile private var screen = Screen.APP

    @Volatile private var reading: Reading? = null

    private val webViews = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var readingSince = since

    /** Whether the Discord app answered the last time presence was sent. */
    @Volatile var connected: Boolean = false
        private set

    private var ipc: DiscordIpc? = null

    fun start() {
        merge(
            enabled().changes(), hideReading().changes(), hiddenCategories().changes(), useChapterTitles().changes(),
            customMessage().changes(), showProgress().changes(), showTimestamp().changes(), showButtons().changes(),
            showDownloadButton().changes(), showDiscordButton().changes(), incognito.mode().changes(),
        ).drop(1).onEach { updates.trySend(Unit) }.launchIn(scope)
        scope.launch { loop(updates) }
        updates.trySend(Unit)
    }

    fun setScreen(screen: Screen) {
        if (this.screen == screen && reading == null) return
        this.screen = screen
        reading = null
        updates.trySend(Unit)
    }

    /** The reader closed: back to the screen it was opened from. */
    fun clearReading() {
        reading = null
        updates.trySend(Unit)
    }

    /** A site browser window opened or closed: it shows as WebView while one is open. */
    fun setWebView(open: Boolean) {
        if (open) webViews.incrementAndGet() else webViews.updateAndGet { (it - 1).coerceAtLeast(0) }
        updates.trySend(Unit)
    }

    fun setReading(reading: Reading) {
        if (this.reading?.mangaId != reading.mangaId) readingSince = System.currentTimeMillis()
        this.reading = reading
        updates.trySend(Unit)
    }

    private suspend fun loop(updates: ReceiveChannel<Unit>) {
        while (true) {
            // Also retry every so often, so presence appears once Discord starts
            select {
                updates.onReceive { }
                onTimeout(RETRY_EVERY) { }
            }
            runCatching { send() }.onFailure {
                logcat(LogPriority.DEBUG, it) { "Discord presence not sent" }
                disconnect()
            }
        }
    }

    private suspend fun send() {
        if (!enabled().get()) {
            if (ipc != null) {
                runCatching { setActivity(null) }
                disconnect()
            }
            return
        }
        val connection = ipc ?: connect() ?: return
        ipc = connection
        setActivity(activity())
    }

    /**
     * Reads Discord's answer, giving up after [READ_TIMEOUT]: a Discord app that stops answering
     * would otherwise block presence for good. The read runs on its own thread, which closing the
     * connection then ends.
     */
    private suspend fun readReply(connection: DiscordIpc): Pair<Int, String> =
        readWithTimeout(READ_TIMEOUT, close = connection::close, read = connection::read)

    private suspend fun connect(): DiscordIpc? {
        val connection = DiscordIpc.connect() ?: return null
        connection.send(DiscordIpc.OP_HANDSHAKE, """{"v":1,"client_id":"$APPLICATION_ID"}""")
        val (op, body) = readReply(connection)
        if (op != DiscordIpc.OP_FRAME || !body.contains("\"READY\"")) {
            connection.close()
            error("Discord refused the connection: $body")
        }
        connected = true
        return connection
    }

    private fun disconnect() {
        runCatching { ipc?.close() }
        ipc = null
        connected = false
    }

    private suspend fun setActivity(activity: JsonObject?) {
        val connection = ipc ?: return
        val payload = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            put("nonce", UUID.randomUUID().toString())
            putJsonObject("args") {
                put("pid", ProcessHandle.current().pid())
                if (activity != null) put("activity", activity)
            }
        }
        connection.send(DiscordIpc.OP_FRAME, payload.toString())
        val (op, body) = readReply(connection)
        if (op == DiscordIpc.OP_CLOSE || body.contains("\"evt\":\"ERROR\"")) error("Discord: $body")
    }

    internal suspend fun activity(): JsonObject {
        val screen = when {
            webViews.get() > 0 -> Screen.WEBVIEW
            reading != null -> Screen.MANGA
            else -> screen
        }
        val reading = reading.takeIf { screen == Screen.MANGA }
        // Incognito is read now, not when the chapter opened, so turning it on hides the title at once
        val hide = reading != null && (incognito.isOn(reading.sourceId) || hideReading().get() || inHiddenCategory(reading.mangaId))
        val title = reading?.title?.takeUnless { hide }
        val state = when {
            reading == null -> screen.label
            hide || !showProgress().get() -> null
            useChapterTitles().get() -> reading.chapter
            reading.chapterNumber >= 0 -> "Chapter ${formatNumber(reading.chapterNumber)}"
            else -> reading.chapter
        }
        val details = customMessage().get().takeIf { it.isNotBlank() } ?: title ?: screen.details
        val image = reading?.coverUrl?.takeIf { !hide && it.startsWith("https://") } ?: screen.image
        return buildJsonObject {
            put("type", ACTIVITY_WATCHING)
            put("details", fit(details))
            state?.let { put("state", fit(it)) }
            if (showTimestamp().get()) {
                putJsonObject("timestamps") { put("start", if (reading != null) readingSince else since) }
            }
            putJsonObject("assets") {
                put("large_image", image)
                put("large_text", fit("${screen.details} ${title ?: screen.label}"))
                put("small_image", KOMIKKU_IMAGE)
                put("small_text", "Zoukan - Komikku for desktop")
            }
            val buttons = buildList {
                if (showButtons().get()) {
                    if (showDownloadButton().get()) add("Read on Komikku" to DOWNLOAD_URL)
                    if (showDiscordButton().get()) add("Discord" to DISCORD_URL)
                }
            }
            if (buttons.isNotEmpty()) {
                putJsonArray("buttons") {
                    buttons.forEach { (label, url) ->
                        addJsonObject {
                            put("label", label)
                            put("url", url)
                        }
                    }
                }
            }
        }
    }

    private suspend fun inHiddenCategory(mangaId: Long): Boolean {
        val hidden = hiddenCategories().get()
        if (hidden.isEmpty()) return false
        val ids = getCategories.await(mangaId).map { it.id.toString() }.ifEmpty { listOf(Category.UNCATEGORIZED_ID.toString()) }
        return ids.any { it in hidden }
    }

    companion object {
        private val readers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Runs the blocking [read] on its own thread and waits [timeout] ms for it. On timeout it
         * calls [close], which ends the blocked read, and throws.
         */
        internal suspend fun <T> readWithTimeout(timeout: Long, close: () -> Unit, read: () -> T): T {
            val reply = readers.async { read() }
            return withTimeoutOrNull(timeout) { reply.await() } ?: run {
                runCatching { close() }
                error("Discord did not answer in time")
            }
        }

        /** The Android app's Discord application, so presence looks the same. */
        const val APPLICATION_ID = "1424627741256585271"
        private const val ACTIVITY_WATCHING = 3
        private const val RETRY_EVERY = 30_000L
        internal const val READ_TIMEOUT = 10_000L
        private const val DOWNLOAD_URL = "https://komikku-app.github.io/download/"
        private const val DISCORD_URL = "https://discord.gg/85jB7V5AJR"

        private const val CDN = "https://cdn.discordapp.com/"
        private const val KOMIKKU_IMAGE = CDN + "emojis/1365538288894738532.webp?quality=lossless"
        private const val LIBRARY_IMAGE = CDN + "emojis/1365262809050644591.webp?quality=lossless"
        private const val UPDATES_IMAGE = CDN + "emojis/1365261957883625492.webp?quality=lossless"
        private const val HISTORY_IMAGE = CDN + "emojis/1365262076787949598.webp?quality=lossless"
        private const val BROWSE_IMAGE = CDN + "emojis/1365263374992146576.webp?quality=lossless"
        private const val MORE_IMAGE = CDN + "emojis/1365261438276599849.webp?quality=lossless"
        private const val WEBVIEW_IMAGE = CDN + "emojis/1365262268811579443.webp?quality=lossless"
        private const val MANGA_IMAGE = CDN + "emojis/1365263962622529576.webp?quality=lossless"

        /** Discord wants 2 to 128 characters. */
        internal fun fit(text: String): String = text.take(128).padEnd(2, ' ')

        internal fun formatNumber(number: Double): String =
            if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
    }
}
