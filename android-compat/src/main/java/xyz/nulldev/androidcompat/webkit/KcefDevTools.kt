package xyz.nulldev.androidcompat.webkit

import android.util.Log
import android.view.MotionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Chrome DevTools Protocol (CDP) helpers for [KcefWebViewProvider]:
 * builds CDP payloads (user-agent override, touch emulation, touch dispatch) and dispatches
 * DevTools requests with non-blocking async callback handling and structured logging.
 */
internal object KcefDevTools {
    private const val TAG = KcefWebViewProvider.TAG
    internal const val TOUCH_EMULATION_PARAMS = """{"enabled": true, "maxTouchPoints": 5}"""

    fun buildUserAgentOverrideArgs(
        userAgent: String,
        metadata: KcefWebSettings.UserAgentMetadata?,
    ): String =
        if (metadata != null) {
            Log.d(TAG, "Using user-agent $userAgent with metadata $metadata")
            buildJsonObject {
                put("userAgent", userAgent)
                put("userAgentMetadata", Json.encodeToJsonElement(metadata))
            }.toString()
        } else {
            Log.d(TAG, "Using user-agent $userAgent without metadata")
            buildJsonObject {
                put("userAgent", userAgent)
            }.toString()
        }

    fun applyUserAgentOverride(
        browser: CefBrowser,
        userAgent: String,
        metadata: KcefWebSettings.UserAgentMetadata?,
    ): CompletableFuture<String>? {
        val args = buildUserAgentOverrideArgs(userAgent, metadata)
        return sendMethod(
            client = browser.devToolsClient,
            method = "Emulation.setUserAgentOverride",
            paramsJson = args,
            failureMessage = "Failed to set user agent",
            successPrefix = "Successfully set user agent: ",
        )
    }

    fun enableTouch(browser: CefBrowser): CompletableFuture<String>? =
        sendMethod(
            client = browser.devToolsClient,
            method = "Emulation.setTouchEmulationEnabled",
            paramsJson = TOUCH_EMULATION_PARAMS,
            failureMessage = "Failed to turn on touch input",
        )

    fun buildTouchEventParams(event: MotionEvent): String? {
        val type = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "touchStart"
            MotionEvent.ACTION_MOVE -> "touchMove"
            MotionEvent.ACTION_UP -> "touchEnd"
            MotionEvent.ACTION_CANCEL -> "touchCancel"
            else -> return null
        }
        val coords = MotionEvent.PointerCoords().also { event.getPointerCoords(0, it) }
        val point = buildJsonObject {
            put("x", event.x.toDouble())
            put("y", event.y.toDouble())
            put("radiusX", (coords.touchMajor / 2).toDouble().coerceAtLeast(1.0))
            put("radiusY", (coords.touchMinor / 2).toDouble().coerceAtLeast(1.0))
            put("force", coords.pressure.toDouble().takeIf { it > 0 } ?: 0.5)
            put("id", 0)
        }
        return buildJsonObject {
            put("type", type)
            // touchEnd and touchCancel list the touches still down: none
            put("touchPoints", if (type == "touchStart" || type == "touchMove") JsonArray(listOf(point)) else JsonArray(emptyList()))
        }.toString()
    }

    fun dispatchTouch(
        browser: CefBrowser?,
        event: MotionEvent,
    ): Boolean {
        val target = browser ?: return false
        val params = buildTouchEventParams(event) ?: return false
        sendMethod(
            client = target.devToolsClient,
            method = "Input.dispatchTouchEvent",
            paramsJson = params,
            failureMessage = "Touch event failed",
        )
        return true
    }

    /**
     * Sends a CDP command without blocking the caller and logs failures (and optional success
     * responses) when the future completes.
     */
    fun sendMethod(
        client: CefDevToolsClient?,
        method: String,
        paramsJson: String,
        failureMessage: String,
        successPrefix: String? = null,
    ): CompletableFuture<String>? {
        if (client == null) {
            Log.w(TAG, "$failureMessage (no DevTools client)")
            return null
        }
        val future = client.executeDevToolsMethod(method, paramsJson) ?: return null
        future.whenComplete { res, ex ->
            if (ex != null) {
                Log.e(TAG, failureMessage, ex)
            } else if (successPrefix != null) {
                Log.v(TAG, "$successPrefix$res")
            }
        }
        return future
    }

    /**
     * Sends a CDP command and dispatches [onResult] asynchronously on [executor] (never on
     * Chromium's DevTools reply thread), logging both CDP failures and callback exceptions.
     */
    fun sendMethodAsync(
        client: CefDevToolsClient?,
        method: String,
        paramsJson: String,
        failureMessage: String,
        callbackFailureMessage: String = "$failureMessage (callback)",
        executor: Executor = ForkJoinPool.commonPool(),
        onResult: (String?, Throwable?) -> Unit,
    ): CompletableFuture<Void> {
        val future = client?.executeDevToolsMethod(method, paramsJson)
        if (future == null) {
            val error = IllegalStateException("DevTools client unavailable for $method")
            Log.w(TAG, "$failureMessage (no DevTools client)")
            return CompletableFuture.runAsync(
                { onResult(null, error) },
                executor,
            ).whenComplete { _, callbackEx ->
                if (callbackEx != null) Log.e(TAG, callbackFailureMessage, callbackEx)
            }
        }
        return future
            .handleAsync(
                { res, ex ->
                    if (ex != null) Log.e(TAG, failureMessage, ex)
                    onResult(res, ex)
                },
                executor,
            ).thenAccept { }
            .whenComplete { _, callbackEx ->
                if (callbackEx != null) Log.e(TAG, callbackFailureMessage, callbackEx)
            }
    }
}
