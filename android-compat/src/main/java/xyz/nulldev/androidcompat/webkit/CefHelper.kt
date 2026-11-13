package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
// ZOUKAN -->
import kotlinx.coroutines.flow.flow
// ZOUKAN <--
import kotlinx.coroutines.flow.first
// ZOUKAN -->
import kotlinx.coroutines.withTimeoutOrNull
// ZOUKAN <--
import org.cef.CefApp
import org.cef.CefClient

private val logger = KotlinLogging.logger {}

object CefHelper {
    val cefApp = MutableStateFlow<Result<CefApp?>>(Result.success(null))

    /** Set by the server to start CEF the first time something waits for it */
    @Volatile
    var requestStart: () -> Unit = {}

    // ZOUKAN -->
    /**
     * Waits at most [START_TIMEOUT_MS] for Chromium, then throws [CefException]: a WebView is created
     * on the main looper, which would otherwise stay blocked for as long as a first download of
     * Chromium takes, or for good when it hangs. The start goes on, and the next WebView waits again.
     */
    suspend fun createClient(): CefClient {
        val app = withTimeoutOrNull(START_TIMEOUT_MS) { waitForInit().first() }
            ?: throw CefException("Chromium did not start in time; it may still be downloading")
        return app.createClient()
    }

    const val START_TIMEOUT_MS = 3 * 60 * 1000L
    // ZOUKAN <--

    // ZOUKAN -->
    // Upstream JCEF has no CefApp.onInitialization: Chromium starts in the first createClient(),
    // so this only waits for the app object.
    fun waitForInit() =
        flow {
            requestStart()
            val app = cefApp.first { it.isFailure || it.getOrThrow() != null }.getOrThrow()!!
            when (CefApp.getState()) {
                CefApp.CefAppState.SHUTTING_DOWN, CefApp.CefAppState.TERMINATED ->
                    throw CefException("Shutting down")
                CefApp.CefAppState.INITIALIZATION_FAILED -> throw CefException("Chromium failed to start")
                else -> emit(app)
            }
        }
    // ZOUKAN <--

    class CefException(
        msg: String,
    ) : Exception(msg)
}
