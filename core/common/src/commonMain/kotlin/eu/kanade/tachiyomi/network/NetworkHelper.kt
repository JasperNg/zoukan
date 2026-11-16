package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import exh.log.EHLogLevel
import logcat.LogPriority
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

/* SY --> */ open /* SY <-- */ class NetworkHelper(
    private val context: Context,
    private val preferences: NetworkPreferences,
) {

    /* SY --> */ open /* SY <-- */val cookieJar = AndroidCookieJar()

    /**
     * Timeout in unit of seconds.
     */
    private /* KMK --> */ fun /* KMK <-- */ clientBuilder(
        // KMK -->
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
        // KMK <--
    ): OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // KMK -->
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .callTimeout(callTimeout, TimeUnit.SECONDS)
            // KMK <--
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

        // KMK -->
        if (EHLogLevel.isExtraLogging()) {
            // KMK <--
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                // ZOUKAN -->
                // Logs get shared; sessions and tokens must not be in them
                listOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie").forEach(::redactHeader)
                // URLs are logged as upstream does, without the values of query parameters that
                // commonly carry credentials
                redactQueryParams(*REDACTED_QUERY_PARAMS)
                // ZOUKAN <--
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        when (/* ZOUKAN --> */supportedDohProvider(/* ZOUKAN <-- */preferences.dohProvider().get()/* ZOUKAN --> */)/* ZOUKAN <-- */) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            // ZOUKAN: Quad101 removed (read as Cloudflare)
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            // ZOUKAN: Shecan removed (read as Cloudflare)
            else -> builder
        }
    }

    /* SY --> */ open /* SY <-- */ val client /* KMK --> */ by lazy /* KMK <-- */ {
        clientBuilder()
            .addInterceptor(
                /* ZOUKAN --> */cloudflareInterceptor/* ZOUKAN <-- */(context, cookieJar, ::defaultUserAgentProvider),
            )
            .build()
    }

    // ZOUKAN -->
    /**
     * [client] without its cookies, cache, user agent, logging or Cloudflare solver, and with
     * OkHttp's default timeouts: what `OkHttpClient()` gives, but with this client's DNS (over
     * HTTPS when it is set) and connection pool. For services that are not sources (sync, E-Hentai's
     * settings) and send their own headers. A client of its own would resolve names through the
     * system's DNS, and leak them when DNS over HTTPS is on.
     */
    val plainClient: OkHttpClient by lazy {
        client.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
    // ZOUKAN <--

    // KMK -->
    /**
     * Timeout in unit of seconds.
     */
    private fun clientWithTimeOut(
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
    ) = clientBuilder(connectTimeout, readTimeout, callTimeout)
        .addInterceptor(
            /* ZOUKAN --> */cloudflareInterceptor/* ZOUKAN <-- */(context, cookieJar, ::defaultUserAgentProvider),
        )
        .build()

    /**
     * Allow to download a big file with retry & resume capability because
     * normally it would get a Timeout exception.
     */
    fun downloadFileWithResume(url: String, outputFile: File, progressListener: ProgressListener) {
        val client = clientWithTimeOut(
            callTimeout = 120,
        )

        var downloadedBytes: Long

        var attempt = 0

        while (attempt < MAX_RETRY) {
            try {
                // Check how much has already been downloaded
                downloadedBytes = outputFile.length()
                // Set up request with Range header to resume from the last byte
                val request = GET(
                    url = url,
                    headers = Headers.Builder()
                        .add("Range", "bytes=$downloadedBytes-")
                        .build(),
                )

                var failed = false
                client.newCachelessCallWithProgress(request, progressListener).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) { // 206 indicates partial content
                        saveResponseToFile(response, outputFile, downloadedBytes)
                        if (response.isSuccessful) {
                            return
                        }
                    } else {
                        attempt++
                        logcat(LogPriority.ERROR) { "Unexpected response code: ${response.code}. Retrying..." }
                        if (response.code == 416) {
                            // 416: Range Not Satisfiable
                            outputFile.delete()
                        }
                        failed = true
                    }
                }
                if (failed) exponentialBackoff(attempt - 1)
            } catch (e: IOException) {
                logcat(LogPriority.ERROR) { "Download interrupted: ${e.message}. Retrying..." }
                // Wait or handle as needed before retrying
                attempt++
                exponentialBackoff(attempt - 1)
            }
        }
        throw IOException("Max retry attempts reached.")
    }

    // Helper function to save data incrementally
    private fun saveResponseToFile(response: Response, outputFile: File, startPosition: Long) {
        val body = response.body

        // Use RandomAccessFile to write from specific position
        RandomAccessFile(outputFile, "rw").use { file ->
            file.seek(startPosition)
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    file.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    // Increment attempt and apply exponential backoff
    private fun exponentialBackoff(attempt: Int) {
        val backoffDelay = calculateExponentialBackoff(attempt)
        Thread.sleep(backoffDelay)
    }

    // Helper function to calculate exponential backoff with jitter
    private fun calculateExponentialBackoff(attempt: Int, baseDelay: Long = 1000L, maxDelay: Long = 32000L): Long {
        // Calculate the exponential delay
        val delay = baseDelay * 2.0.pow(attempt).toLong()
        logcat(LogPriority.ERROR) { "Exponential backoff delay: $delay ms" }
        // Apply jitter by adding a random value to avoid synchronized retries in distributed systems
        return (delay + Random.nextLong(0, 1000)).coerceAtMost(maxDelay)
    }
    // KMK <--

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default", ReplaceWith("client"))
    @Suppress("UNUSED")
    /* SY --> */
    open /* SY <-- */val cloudflareClient: OkHttpClient
        get() = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent().get().trim()

    companion object {
        // KMK -->
        private const val MAX_RETRY = 5
        // KMK <--

        // ZOUKAN -->
        /** Query parameters whose values verbose network logging leaves out. */
        internal val REDACTED_QUERY_PARAMS = arrayOf(
            "token", "access_token", "refresh_token", "id_token", "key", "api_key", "apikey",
            "password", "pass", "sid", "session", "sessionid", "auth", "signature", "sig",
        )
        // ZOUKAN <--
    }
}
