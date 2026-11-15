package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.dohProviderUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Applies the DNS over HTTPS preference to Chromium, which resolves names itself instead of
 * through OkHttp.
 *
 * Chromium has no command line switch for DoH any more (the DnsOverHttps feature's Templates and
 * Fallback parameters are gone), and JCEF cannot set its global (local state) preferences. So the
 * `dns_over_https.mode` and `.templates` preferences are written to Chromium's `Local State` file
 * before Chromium starts: "secure" mode with the provider's server, like OkHttp, which does not
 * fall back to the system resolver either. With DoH off, they are removed, back to Chromium's
 * default. Chromium reads the file once at start, so a change applies after a restart.
 */
object ChromiumDns {
    private const val LOCAL_STATE = "Local State"
    private const val GROUP = "dns_over_https"
    private const val MODE = "mode"
    private const val TEMPLATES = "templates"

    /** Writes the preference for [provider] (a `PREF_DOH_*` value, -1 for off) to [rootCachePath]. */
    fun apply(rootCachePath: File, provider: Int) {
        val file = File(rootCachePath, LOCAL_STATE)
        runCatching {
            val current = if (file.exists()) file.readText() else null
            val updated = updateLocalState(current, dohProviderUrl(provider)) ?: return
            rootCachePath.mkdirs()
            val temp = File(rootCachePath, "$LOCAL_STATE.zoukan-tmp")
            temp.writeText(updated)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not apply DNS over HTTPS to the browser" } }
    }

    /**
     * [localState] (the file's JSON, null when there is none) with the DoH preferences for
     * [template] (null for off). Null when nothing changes, or the file is not a JSON object: an
     * unreadable file is left to Chromium rather than replaced.
     */
    fun updateLocalState(localState: String?, template: String?): String? {
        val root = if (localState.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { Json.parseToJsonElement(localState) as? JsonObject }.getOrNull() ?: return null
        }
        val group = root[GROUP] as? JsonObject ?: JsonObject(emptyMap())
        val newGroup = if (template == null) {
            group - MODE - TEMPLATES
        } else {
            group + mapOf(MODE to JsonPrimitive("secure"), TEMPLATES to JsonPrimitive(template))
        }
        if (newGroup == group) return null
        val newRoot = if (newGroup.isEmpty()) root - GROUP else root + (GROUP to JsonObject(newGroup))
        return Json.encodeToString(JsonObject.serializer(), JsonObject(newRoot))
    }
}
