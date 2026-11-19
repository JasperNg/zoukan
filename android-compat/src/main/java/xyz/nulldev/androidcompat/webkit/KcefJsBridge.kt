package xyz.nulldev.androidcompat.webkit

import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.javaMethod

/**
 * Bridges `@JavascriptInterface` objects and `evaluateJavascript` calls between Chromium and an
 * extension's WebView: marshals calls/responses, chunks large JS-to-Kotlin payloads, and dispatches
 * invocations onto the WebView's looper.
 */
internal class KcefJsBridge(
    private val isDestroyed: () -> Boolean,
    private val postToLooper: (Runnable) -> Boolean,
    private val chunkChars: Int = CHUNK_CHARS,
    maxCallChars: Int = MAX_CALL_CHARS,
    maxPendingCalls: Int = MAX_PENDING_CALLS,
    maxPendingChars: Int = MAX_PENDING_CHARS,
    private val queryFn: String = KcefWebViewProvider.QUERY_FN,
) {
    /** Read on Chromium's threads while the WebView's looper changes them. */
    val mappings: MutableList<FunctionMapping> = CopyOnWriteArrayList()

    private val callChunks = KcefCallChunkAssembler(chunkChars, maxCallChars, maxPendingCalls, maxPendingChars)

    val pendingChunkChars: Long
        get() = callChunks.pendingChars

    fun clearPendingChunks() {
        callChunks.clear()
    }

    fun addJavascriptInterface(
        obj: Any,
        interfaceName: String,
    ) {
        val cls = obj::class
        mappings.addAll(
            cls.declaredMemberFunctions
                .filter { isJavascriptInterface(it.javaMethod) }
                .map { fn ->
                    fn.javaMethod?.isAccessible = true
                    FunctionMapping(interfaceName, fn.name, obj, fn).also {
                        Log.v(TAG, "Exposing: ${it.toNice()}")
                    }
                },
        )
    }

    fun removeJavascriptInterface(interfaceName: String): Boolean {
        val removed = mappings.removeAll { it.interfaceName == interfaceName }
        Log.v(
            TAG,
            if (removed) "Removed interface mappings for $interfaceName" else "No interface mappings for $interfaceName to remove",
        )
        return removed
    }

    fun isJavascriptInterface(method: Method?): Boolean =
        method != null &&
            Modifier.isPublic(method.modifiers) &&
            method.isAnnotationPresent(JavascriptInterface::class.java)

    fun buildInterfaceScript(mapping: FunctionMapping): String =
        """
                        window.${mapping.interfaceName} = window.${mapping.interfaceName} || {}
                        window.${mapping.interfaceName}.${mapping.functionName} = async function() {
                            const args = await Promise.all(Array.from(arguments));
                            // ASCII only (\uXXXX escapes), and in chunks: a long call can otherwise be truncated or crash Chromium
                            const request = JSON.stringify({
                                functionName: ${Json.encodeToString(mapping.functionName)},
                                interfaceName: ${Json.encodeToString(mapping.interfaceName)},
                                args,
                            }).replace(/[\u007f-\uffff]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
                            const query = r => new Promise((resolve, reject) => {
                                window.$queryFn({
                                    request: r,
                                    persistent: false,
                                    onSuccess: resolve,
                                    onFailure: (_, err) => reject(err),
                                })
                            });
                            const size = $chunkChars;
                            if (request.length <= size) return query(request);
                            const id = Math.random().toString(36).slice(2) + Date.now().toString(36);
                            const total = Math.ceil(request.length / size);
                            let result;
                            for (let i = 0; i < total; i++) {
                                result = await query(JSON.stringify({ chunkId: id, index: i, total, data: request.slice(i * size, (i + 1) * size) }));
                            }
                            return result;
                        }
                        """

    fun pushMappings(executeScript: (js: String, sourceUrl: String) -> Unit) {
        mappings.forEach { mapping ->
            executeScript(buildInterfaceScript(mapping), "SUWAYOMI ${mapping.toNice()}")
        }
    }

    fun handleQuery(
        request: String,
        responder: QueryResponder,
    ): Boolean {
        if (isDestroyed()) return false
        val whole = if (request.startsWith("{\"chunkId\"")) {
            val call = try {
                val chunk = Json.decodeFromString<CallChunk>(request)
                callChunks.add(chunk.chunkId, chunk.index, chunk.total, chunk.data)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid request chunk received $e")
                return false
            }
            if (call == null) {
                responder.success("")
                return true
            }
            call
        } else {
            request
        }
        val invoke = try {
            Json.decodeFromString<FunctionCall>(whole)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid request received (${whole.length} chars) $e")
            return false
        }
        val mapping = mappings.find {
            it.functionName == invoke.functionName &&
                it.interfaceName == invoke.interfaceName
        } ?: return false

        postToLooper(
            Runnable {
                if (isDestroyed()) return@Runnable
                try {
                    Log.v(
                        TAG,
                        "Received request to invoke ${mapping.toNice()} with ${invoke.args.size} args",
                    )
                    val retval = mapping.fn.call(mapping.obj, *invoke.args)
                    responder.success(retval.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "JS-invoke on ${mapping.toNice()} failed:", e)
                    responder.failure(0, e.message)
                }
            },
        )
        return true
    }

    fun normalizeEvaluateScript(script: String): String = script.removePrefix("javascript:")

    fun evaluateJavaScript(
        script: String,
        evaluate: (normalizedScript: String, onResult: (String) -> Unit) -> Unit,
        onResult: (String) -> Unit,
    ) {
        evaluate(normalizeEvaluateScript(script)) { result ->
            Log.v(TAG, "JS returned: $result")
            postToLooper(Runnable { onResult(result) })
        }
    }

    interface QueryResponder {
        fun success(response: String)

        fun failure(
            errorCode: Int,
            errorMessage: String?,
        )
    }

    @Serializable
    internal data class CallChunk(
        val chunkId: String,
        val index: Int,
        val total: Int,
        val data: String,
    )

    @Serializable
    internal data class FunctionCall(
        val interfaceName: String,
        val functionName: String,
        val args: Array<String>,
    )

    internal data class FunctionMapping(
        val interfaceName: String,
        val functionName: String,
        val obj: Any,
        val fn: KFunction<*>,
    ) {
        fun toNice(): String = "$interfaceName.$functionName"
    }

    companion object {
        private const val TAG = KcefWebViewProvider.TAG
        const val CHUNK_CHARS = 16_000
        const val MAX_CALL_CHARS = 16 * 1024 * 1024
        const val MAX_PENDING_CALLS = 64
        const val MAX_PENDING_CHARS = 32 * 1024 * 1024
    }
}

/**
 * Reassembles chunked JavaScript-interface calls while enforcing per-call and total pending
 * character budgets.
 */
internal class KcefCallChunkAssembler(
    private val chunkChars: Int,
    maxCallChars: Int,
    private val maxPending: Int,
    private val maxPendingChars: Int,
) {
    private val maxChunks = (maxCallChars + chunkChars - 1) / chunkChars
    private val pending = LinkedHashMap<String, Array<String?>>()

    @get:Synchronized
    var pendingChars = 0L
        private set

    @Synchronized
    fun add(
        chunkId: String,
        index: Int,
        total: Int,
        data: String,
    ): String? {
        require(total in 1..maxChunks) { "Chunk count $total out of range" }
        require(index in 0 until total) { "Chunk index $index out of range" }
        require(data.length <= chunkChars) { "Chunk of ${data.length} characters" }
        val parts =
            pending[chunkId] ?: run {
                require(index == 0) { "Call starts at chunk $index" }
                if (pending.size >= maxPending) drop(pending.keys.first())
                arrayOfNulls<String>(total).also { pending[chunkId] = it }
            }
        require(parts.size == total) { "Chunk count changed from ${parts.size} to $total" }
        val grow = data.length - (parts[index]?.length ?: 0)
        while (pendingChars + grow > maxPendingChars) {
            drop(pending.keys.firstOrNull { it != chunkId } ?: break)
        }
        if (pendingChars + grow > maxPendingChars) {
            drop(chunkId)
            throw IllegalArgumentException("Unfinished calls over $maxPendingChars characters")
        }
        parts[index] = data
        pendingChars += grow
        if (parts.any { it == null }) return null
        drop(chunkId)
        return parts.joinToString("")
    }

    @Synchronized
    fun clear() {
        pending.clear()
        pendingChars = 0
    }

    private fun drop(chunkId: String) {
        val parts = pending.remove(chunkId) ?: return
        pendingChars -= parts.sumOf { it?.length ?: 0 }
    }
}
