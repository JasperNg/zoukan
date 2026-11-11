package zoukan.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import zoukan.desktop.Platform
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.Version
import zoukan.desktop.secrets.SecretStore
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

        data class Failed(val message: String) : Result
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

    data class Asset(val name: String, val apiUrl: String, val size: Long, val sha256: String?)

    var token: String?
        get() = runCatching { secrets.get(TOKEN_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) secrets.delete(TOKEN_KEY) else secrets.set(TOKEN_KEY, value.trim())
        }

    suspend fun check(includePrereleases: Boolean = false): Result = withContext(Dispatchers.IO) {
        val request = request("$apiUrl/repos/$repo/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 && token == null ->
                        Result.Failed("No releases found. If the repository is private, add a GitHub token.")
                    response.code == 401 -> Result.Failed("GitHub refused the token (401)")
                    response.code == 403 || response.code == 429 -> Result.Failed("GitHub rate limit or no access (${response.code}); try again later")
                    !response.isSuccessful -> Result.Failed("GitHub answered ${response.code}")
                    else -> select(response.body.string(), current, platform, includePrereleases)
                        ?.let { Result.Available(it) }
                        ?: Result.UpToDate
                }
            }
        } catch (e: IOException) {
            Result.Failed("Could not reach GitHub: ${e.message ?: e::class.simpleName}")
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
        val request = request("$apiUrl/repos/$repo/releases/tags/$tag")
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 && token == null ->
                        kotlin.Result.failure(IOException("No release notes for $version. If the repository is private, add a GitHub token in Settings > About."))
                    response.code == 404 -> kotlin.Result.failure(IOException("No release notes for $version on GitHub"))
                    response.code == 401 -> kotlin.Result.failure(IOException("GitHub refused the token (401)"))
                    !response.isSuccessful -> kotlin.Result.failure(IOException("GitHub answered ${response.code}"))
                    else -> kotlin.Result.success(parseNotes(response.body.string(), version))
                }
            }
        } catch (e: IOException) {
            kotlin.Result.failure(IOException("Could not reach GitHub: ${e.message ?: e::class.simpleName}", e))
        }
    }

    /**
     * Downloads the installer to [dir], checking its SHA-256 when GitHub lists one, and returns the
     * file. [progress] gets bytes read and the total.
     */
    suspend fun download(asset: Asset, dir: File, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, asset.name)
        val partial = File(dir, "${asset.name}.part")
        // The asset's API URL redirects to storage; OkHttp drops the token on that other host
        val request = request(asset.apiUrl).header("Accept", "application/octet-stream").build()
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

    private fun request(url: String) = Request.Builder().url(url)
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
        /** The GitHub token's name in the secret store. */
        const val TOKEN_KEY = "github-token"

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
                        asset = installerFor(release.assets, platform)?.let {
                            Asset(it.name, it.url, it.size, it.digest?.takeIf { d -> d.startsWith("sha256:") }?.removePrefix("sha256:"))
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
