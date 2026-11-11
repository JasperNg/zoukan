package zoukan.desktop.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import java.io.File
import java.net.CookieManager
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The app's cookie store, kept in a file so site sessions and Cloudflare clearances survive a
 * restart. Installed as java.net.CookieHandler's store, which android.webkit.CookieManager (and so
 * OkHttp's AndroidCookieJar) uses on desktop. Session cookies (no expiry) are not kept. The file
 * holds site sessions, so it is encrypted with the key in the OS secret store (see FileCipher).
 */
class PersistentCookieStore(
    private val file: File,
    /** Encrypts the file; a plain file from an earlier version is still read, then written encrypted. */
    private val cipher: SettingsFileCodec? = null,
) : CookieStore {
    private val delegate: CookieStore = CookieManager().cookieStore
    private val saver = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cookie-store").apply { isDaemon = true }
    }
    private var pendingSave: ScheduledFuture<*>? = null

    // HttpCookie keeps its max age relative to when it was created, which it does not expose
    private val expiresAt = Collections.synchronizedMap(IdentityHashMap<HttpCookie, Long>())

    init {
        load()
        Runtime.getRuntime().addShutdownHook(Thread { save() })
    }

    override fun add(uri: URI?, cookie: HttpCookie) {
        if (cookie.maxAge > 0) expiresAt[cookie] = System.currentTimeMillis() + cookie.maxAge * 1000
        delegate.add(uri, cookie)
        scheduleSave()
    }

    override fun get(uri: URI): List<HttpCookie> = delegate.get(uri)

    override fun getCookies(): List<HttpCookie> = delegate.cookies

    override fun getURIs(): List<URI> = delegate.urIs

    override fun remove(uri: URI?, cookie: HttpCookie): Boolean = delegate.remove(uri, cookie).also { scheduleSave() }

    override fun removeAll(): Boolean = delegate.removeAll().also { scheduleSave() }

    @Synchronized
    private fun scheduleSave() {
        pendingSave?.cancel(false)
        pendingSave = saver.schedule(::save, 2, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun save() {
        val now = System.currentTimeMillis()
        val entries = delegate.urIs.flatMap { uri ->
            delegate.get(uri)
                .filter { it.maxAge > 0 }
                .map { StoredCookie.from(uri, it, expiresAt[it] ?: (now + it.maxAge * 1000)) }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            val plain = json.encodeToString(entries).toByteArray()
            tmp.writeBytes(cipher?.encode(plain) ?: plain)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { logcat(LogPriority.ERROR, it) { "Saving cookies failed" } }
    }

    private fun load() {
        if (!file.isFile) return
        val now = System.currentTimeMillis()
        var wasPlain = false
        runCatching {
            val stored = file.readBytes()
            val plain = if (cipher != null && cipher.isEncoded(stored)) cipher.decode(stored) else stored
            wasPlain = cipher != null && plain === stored
            json.decodeFromString<List<StoredCookie>>(plain.decodeToString())
        }
            .onFailure { logcat(LogPriority.ERROR, it) { "Reading cookies failed" } }
            .getOrDefault(emptyList())
            .filter { it.expiresAt > now }
            .forEach { stored ->
                val cookie = stored.toHttpCookie(now)
                expiresAt[cookie] = stored.expiresAt
                delegate.add(URI(stored.uri), cookie)
            }
        // A plain file from before encryption does not stay on disk
        if (wasPlain) save()
    }

    @Serializable
    private data class StoredCookie(
        val uri: String,
        val name: String,
        val value: String,
        val domain: String?,
        val path: String?,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
    ) {
        fun toHttpCookie(now: Long) = HttpCookie(name, value).also {
            it.domain = domain
            it.path = path
            it.maxAge = (expiresAt - now) / 1000
            it.secure = secure
            it.isHttpOnly = httpOnly
            it.version = 0
        }

        companion object {
            fun from(uri: URI, cookie: HttpCookie, expiresAt: Long) = StoredCookie(
                uri = uri.toString(),
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain,
                path = cookie.path,
                expiresAt = expiresAt,
                secure = cookie.secure,
                httpOnly = cookie.isHttpOnly,
            )
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
