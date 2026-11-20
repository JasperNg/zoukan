package zoukan.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import zoukan.desktop.Platform
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.Version
import zoukan.desktop.compat.localized
import zoukan.desktop.secrets.SecretStore
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.could_not_use_the_secret_store
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The in-app update check: reads the GitHub releases of [repo], picks the newest desktop release
 * (tagged desktop-v<version>) newer than [current], and downloads its installer for this platform.
 *
 * A private repository needs a GitHub token with read access to its contents (a fine-grained token
 * limited to the repository is enough); it is kept in the OS secret store under [TOKEN_KEY].
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val secrets: SecretStore,
    private val repo: String = AppVersion.releaseRepo,
    private val current: Version = AppVersion.version,
    private val platform: Platform = Platform.current,
    private val apiUrl: String = "https://api.github.com",
) {
    sealed interface Result {
        data object UpToDate : Result

        data class Available(val release: Release) : Result

        /** [rateLimited]: GitHub refused the request for its rate limit, not for this installation or token. */
        data class Failed(val message: String, val rateLimited: Boolean = false) : Result
    }

    data class Release(
        val version: Version,
        val tag: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        /** The installer for this platform, or null when the release has none */
        val asset: Asset?,
    )

    data class Asset(
        val name: String,
        val apiUrl: String,
        val size: Long,
        val sha256: String?,
        /** The API address of the installer's detached release signature (`<name>.sig`), if the release has one */
        val signatureApiUrl: String? = null,
    )

    var token: String?
        get() = runCatching { secrets.get(TOKEN_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) secrets.delete(TOKEN_KEY) else secrets.set(TOKEN_KEY, value.trim())
        }

    /**
     * The stored token (null when none is stored), or a failure when the secret store could not be
     * read: a locked Keychain, a denied prompt, or the native store failing to load (a LinkageError
     * from JNA). Unlike [token], a store that cannot be read is not taken for no token.
     */
    fun storedToken(): kotlin.Result<String?> = try {
        readToken()
    } catch (e: LinkageError) {
        kotlin.Result.failure(e)
    }

    suspend fun check(includePrereleases: Boolean = false): Result = withContext(Dispatchers.IO) {
        val stored = readToken()
        val request = request("$apiUrl/repos/$repo/releases?per_page=30", stored.getOrNull())
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val hint = if (response.code == 404) missingTokenHint(stored, "") else null
                when {
                    hint != null -> Result.Failed("No releases found. $hint")
                    response.code == 401 -> Result.Failed("GitHub refused the token (401)")
                    response.code == 403 || response.code == 429 ->
                        Result.Failed("GitHub rate limit or no access (${response.code}); try again later", isRateLimited(response))
                    !response.isSuccessful -> Result.Failed("GitHub answered ${response.code}")
                    else -> select(response.body.string(), current, platform, includePrereleases)
                        ?.let { Result.Available(it) }
                        ?: Result.UpToDate
                }
            }
        } catch (e: IOException) {
            Result.Failed("Could not reach GitHub: ${e.message ?: e::class.simpleName}")
        } catch (e: IllegalArgumentException) {
            // A 200 answer that is not GitHub's release list (SerializationException), such as a
            // captive portal's page: the check failed, the app goes on
            Result.Failed("GitHub's answer is not a list of releases")
        }
    }

    /** A release's notes, for the What's new screen. */
    data class Notes(val name: String, val body: String, val pageUrl: String)

    /**
     * The notes of the desktop release tagged for [version] (by default the running one), as
     * Android's "What's new" reads the app's release on GitHub.
     */
    suspend fun releaseNotes(version: Version = current): kotlin.Result<Notes> = withContext(Dispatchers.IO) {
        val tag = AppVersion.TAG_PREFIX + version
        val stored = readToken()
        val request = request("$apiUrl/repos/$repo/releases/tags/$tag", stored.getOrNull())
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val hint = if (response.code == 404) missingTokenHint(stored, " in Settings > About") else null
                when {
                    hint != null -> kotlin.Result.failure(IOException("No release notes for $version. $hint"))
                    response.code == 404 -> kotlin.Result.failure(IOException("No release notes for $version on GitHub"))
                    response.code == 401 -> kotlin.Result.failure(IOException("GitHub refused the token (401)"))
                    !response.isSuccessful -> kotlin.Result.failure(IOException("GitHub answered ${response.code}"))
                    else -> kotlin.Result.success(parseNotes(response.body.string(), version))
                }
            }
        } catch (e: IOException) {
            kotlin.Result.failure(IOException("Could not reach GitHub: ${e.message ?: e::class.simpleName}", e))
        } catch (e: IllegalArgumentException) {
            // A 200 answer that is not a release (SerializationException), such as a captive
            // portal's page: a failed result, which What's new shows, not an exception it does not catch
            kotlin.Result.failure(IOException("GitHub's answer is not a release", e))
        }
    }

    /**
     * The stored token, read once for a request and its message (on macOS each read may show a
     * Keychain prompt): null when none is stored, a failure when the secret store could not be read.
     */
    private fun readToken(): kotlin.Result<String?> = try {
        kotlin.Result.success(secrets.get(TOKEN_KEY)?.takeIf { it.isNotBlank() })
    } catch (e: Exception) {
        kotlin.Result.failure(e)
    }

    /**
     * Why a 404 may be a private repository read without its token: none is stored (add one,
     * [where]), or the secret store could not be read, so a stored token was not sent. Null when
     * the token [stored] was sent: then the 404 is GitHub's own answer.
     */
    private fun missingTokenHint(stored: kotlin.Result<String?>, where: String): String? = stored.fold(
        onSuccess = { if (it == null) "If the repository is private, add a GitHub token$where." else null },
        // A locked or unavailable store: telling the user to add a token they have would mislead
        onFailure = { e -> "${localized(DMR.strings.could_not_use_the_secret_store)}: ${e.message ?: e::class.simpleName}" },
    )

    /**
     * Downloads the installer to [dir], checking its SHA-256 when GitHub lists one, and returns the
     * file. [progress] gets bytes read and the total.
     */
    suspend fun download(asset: Asset, dir: File, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, asset.name)
        val partial = File(dir, "${asset.name}.part")
        // The asset's API URL redirects to storage; OkHttp drops the token on that other host
        val request = assetRequest(asset.apiUrl).build()
        val context = coroutineContext
        try {
            downloadTo(request, partial, asset, progress) { context.ensureActive() }
        } catch (e: Throwable) {
            // Cancelled or failed: no half file is left behind
            partial.delete()
            throw e
        }
        target.delete()
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        target
    }

    /**
     * Downloads the detached release signature of [asset] to `<name>.sig` in [dir], or returns null
     * when the release lists none. The signature is 64 bytes; a larger body is refused.
     */
    suspend fun downloadSignature(asset: Asset, dir: File): File? = withContext(Dispatchers.IO) {
        val url = asset.signatureApiUrl ?: return@withContext null
        dir.mkdirs()
        val target = File(dir, asset.name + ".sig")
        val request = assetRequest(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_SIGNATURE_BYTES) throw IOException("The release signature is too large")
            val bytes = body.byteStream().use { it.readNBytes(MAX_SIGNATURE_BYTES + 1) }
            if (bytes.size > MAX_SIGNATURE_BYTES) throw IOException("The release signature is too large")
            target.writeBytes(bytes)
        }
        target
    }

    private fun downloadTo(
        request: Request,
        partial: File,
        asset: Asset,
        progress: (Long, Long) -> Unit,
        ensureActive: () -> Unit,
    ) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: asset.size
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        // A cancelled download stops at the next block
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        progress(read, total)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (asset.sha256 != null && !sha.equals(asset.sha256, ignoreCase = true)) {
                throw IOException("The download is damaged (SHA-256 does not match)")
            }
        }
    }

    /**
     * A download request for a release asset. The address comes from the release's JSON, and the
     * token is attached to it, so only an https address on the API host is accepted: no request is
     * made, and no token sent, for anything else.
     */
    private fun assetRequest(url: String): Request.Builder {
        val asset = url.toHttpUrlOrNull()
        val api = apiUrl.toHttpUrl()
        if (asset == null || !asset.isHttps || !asset.host.equals(api.host, ignoreCase = true)) {
            throw IOException("The release names an unexpected download address; get the update from the release page instead")
        }
        return request(url).header("Accept", "application/octet-stream")
    }

    private fun request(url: String, token: String? = this.token) = Request.Builder().url(url)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .apply { token?.let { header("Authorization", "Bearer $it") } }

    @Serializable
    internal data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GithubAsset> = emptyList(),
    )

    @Serializable
    internal data class GithubAsset(
        val name: String,
        val url: String,
        val size: Long = 0,
        val digest: String? = null,
    )

    companion object {
        /**
         * GitHub's answer to a request over its rate limit: 429, or 403 with no requests left or a
         * Retry-After (its secondary limit). A 403 without these is a refused token or repository.
         */
        internal fun isRateLimited(response: okhttp3.Response): Boolean =
            response.code == 429 || response.header("x-ratelimit-remaining") == "0" || response.header("retry-after") != null

        /** The GitHub token's name in the secret store. */
        const val TOKEN_KEY = "github-token"

        private const val MAX_SIGNATURE_BYTES = 1024

        private val json = Json { ignoreUnknownKeys = true }

        /** One release's JSON [body] as [Notes]. */
        fun parseNotes(body: String, version: Version): Notes {
            val release = json.decodeFromString<GithubRelease>(body)
            return Notes(
                name = release.name?.takeIf { it.isNotBlank() } ?: "Zoukan $version",
                body = release.body.orEmpty(),
                pageUrl = release.htmlUrl,
            )
        }

        /** The newest desktop release newer than [current] in the releases list [body], if any. */
        fun select(body: String, current: Version, platform: Platform, includePrereleases: Boolean = false): Release? =
            json.decodeFromString<List<GithubRelease>>(body)
                .asSequence()
                .filter { !it.draft && (includePrereleases || !it.prerelease) }
                .filter { it.tagName.startsWith(AppVersion.TAG_PREFIX) }
                .mapNotNull { release -> Version.parse(release.tagName.removePrefix(AppVersion.TAG_PREFIX))?.let { it to release } }
                .filter { (version, _) -> version > current }
                .maxByOrNull { (version, _) -> version }
                ?.let { (version, release) ->
                    Release(
                        version = version,
                        tag = release.tagName,
                        name = release.name?.takeIf { it.isNotBlank() } ?: "Zoukan $version",
                        notes = release.body.orEmpty(),
                        pageUrl = release.htmlUrl,
                        asset = installerFor(release.assets, platform)?.let { installer ->
                            Asset(
                                installer.name,
                                installer.url,
                                installer.size,
                                installer.digest?.takeIf { d -> d.startsWith("sha256:") }?.removePrefix("sha256:"),
                                release.assets.firstOrNull { it.name == installer.name + ".sig" }?.url,
                            )
                        },
                    )
                }

        /** A plain file name: the installer is saved under it, so no folders or odd characters. */
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+ ()-]{0,150}")

        private fun installerFor(all: List<GithubAsset>, platform: Platform): GithubAsset? =
            installerAmong(all.filter { it.name.matches(SAFE_NAME) }, platform)

        private fun installerAmong(assets: List<GithubAsset>, platform: Platform): GithubAsset? = when (platform) {
            Platform.WINDOWS -> assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }
            Platform.MACOS -> assets.filter { it.name.endsWith(".dmg", ignoreCase = true) }
                .let { dmgs -> dmgs.firstOrNull { "arm64" in it.name || "aarch64" in it.name } ?: dmgs.firstOrNull() }
            Platform.LINUX -> null
        }
    }
}
