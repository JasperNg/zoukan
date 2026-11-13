package mihon.data.extension.service

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo
import mihon.data.extension.model.toAvailableExtensions
import mihon.domain.extension.model.ExtensionStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

class ExtensionStoreService(
    private val network: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
) {
    suspend fun fetch(indexUrl: String): Result<ExtensionStore> {
        var updatedIndexUrl: String = indexUrl
        return try {
            // KMK -->
            val store = /* ZOUKAN --> */network.client.awaitSecureStoreResponse/* ZOUKAN <-- */(updatedIndexUrl).use { response ->
                val source = response.body.source().decompressIfGzipped()
                // KMK <--
                val networkStore = when (source.peek().readByte()) {
                    // "[..."
                    0x5B.toByte() -> run {
                        if (!indexUrl.endsWith("/index.min.json")) {
                            throw IllegalArgumentException("Provided legacy store url is not valid")
                        }
                        updatedIndexUrl = indexUrl.replace("/index.min.json", "/repo.json")
                        /* ZOUKAN --> */network.client.awaitSecureStoreResponse/* ZOUKAN <-- */(updatedIndexUrl).use {
                            json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(it.body.source())
                        }
                    }
                    // "{..."
                    0x7B.toByte() -> try {
                        json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source.peek())
                        // KMK -->
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // KMK <--
                        json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                    }
                    else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                }

                if (networkStore is NetworkLegacyExtensionRepo && networkStore.indexV2 != null) {
                    return fetch(networkStore.indexV2)
                }

                networkStore.toExtensionStore(updatedIndexUrl)
            }
            Result.success(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) {
                "Failed to add extension store '$updatedIndexUrl'"
            }
            Result.failure(e)
        }
    }

    suspend fun getExtensions(store: ExtensionStore): Result<List<Extension.Available>> {
        return try {
            val extensions = if (store.extensionListUrl != null) {
                // KMK -->
                /* ZOUKAN --> */network.client.awaitSecureStoreResponse/* ZOUKAN <-- */(store.extensionListUrl!!).use { response ->
                    val source = response.body.source().decompressIfGzipped()
                    // KMK <--
                    when (source.peek().readByte()) {
                        // "{..."
                        0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore.ExtensionList>(source)
                        else -> protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(
                            source.readByteArray(),
                        )
                    }
                        .toAvailableExtensions(store)
                }
            } else if (!store.isLegacy) {
                // KMK -->
                /* ZOUKAN --> */network.client.awaitSecureStoreResponse/* ZOUKAN <-- */(store.indexUrl).use { response ->
                    val source = response.body.source().decompressIfGzipped()
                    // KMK <--
                    when (source.peek().readByte()) {
                        // "{..."
                        0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                        else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                    }
                        .extensionList!!
                        .toAvailableExtensions(store)
                }
            } else {
                val storeBaseUrl = store.indexUrl.removeSuffix("/repo.json")
                // KMK -->
                /* ZOUKAN --> */network.client.awaitSecureStoreResponse/* ZOUKAN <-- */("$storeBaseUrl/index.min.json").use { response ->
                    val source = response.body.source()
                    // KMK <--
                    json.decodeFromBufferedSource<List<NetworkLegacyExtension>>(source)
                        .map { it.toAvailableExtension(store, storeBaseUrl) }
                }
            }
            Result.success(extensions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzip) gzip().buffer() else this
    }
}

// ZOUKAN -->
/**
 * An extension store, or a file it links to, that is not served over https. Its message says so
 * plainly, for the screens that show why a store failed. Android allowed http stores; Zoukan's
 * shared code refuses them (see desktop/PARITY.md).
 */
class InsecureExtensionStoreException(message: String) : IllegalArgumentException(message)

/**
 * A store's index names the key its extensions are signed with and where their code is downloaded
 * from, so it must come over https: over plain http anyone on the network could swap both. Plain
 * http stays allowed for this computer (a local test store).
 *
 * @return [url], when it may be fetched.
 * @throws InsecureExtensionStoreException when it is a plain http address of another computer.
 * @throws IllegalArgumentException when it is not a web address at all.
 */
fun requireSecureStoreUrl(url: String): String {
    val parsed = url.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Not a valid extension store address: ${url.take(200)}")
    val local = parsed.host == "localhost" || parsed.host == "127.0.0.1" || parsed.host == "::1"
    if (!parsed.isHttps && !local) {
        throw InsecureExtensionStoreException(
            "This extension store uses http, which is not secure. Extension stores must use https: ${url.take(200)}",
        )
    }
    return url
}

/**
 * Fetches a store's file (index, extension list, or extension code) over https only. The address
 * is checked by [requireSecureStoreUrl], and a redirect from https to plain http is refused rather
 * than followed: otherwise a store or CDN that redirects would let anyone on the network swap the
 * index or the code. Redirects that stay on https are followed as usual.
 *
 * @return the successful response; the caller closes it.
 * @throws InsecureExtensionStoreException for an http address or an https-to-http redirect.
 * @throws HttpException for any other unsuccessful response.
 */
suspend fun OkHttpClient.awaitSecureStoreResponse(url: String): Response {
    val response = newBuilder()
        .followSslRedirects(false)
        .build()
        .newCall(GET(requireSecureStoreUrl(url)))
        .await()
    if (response.isSuccessful) return response
    response.close()
    if (response.isRedirect) {
        throw InsecureExtensionStoreException(
            "The extension store redirected ${url.take(200)} away from https, so it was not followed. " +
                "Extension stores must use https.",
        )
    }
    throw HttpException(response.code)
}
// ZOUKAN <--
