package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
// ZOUKAN -->
import kotlinx.coroutines.flow.flow
// ZOUKAN <--
import kotlinx.coroutines.flow.first
import org.cef.CefApp
import org.cef.CefClient

private val logger = KotlinLogging.logger {}

object CefHelper {
    val cefApp = MutableStateFlow<Result<CefApp?>>(Result.success(null))

    /** Set by the server to start CEF the first time something waits for it */
    @Volatile
    var requestStart: () -> Unit = {}

    suspend fun createClient(): CefClient = waitForInit().first().createClient()

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
