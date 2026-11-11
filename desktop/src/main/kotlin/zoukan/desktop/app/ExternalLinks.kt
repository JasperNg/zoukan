package zoukan.desktop.app

import java.awt.Desktop
import java.net.URI

/**
 * Opens web addresses in the system browser. Addresses come from sources, trackers and GitHub, so
 * only http and https are passed on: the system would start whatever handles any other scheme (a
 * local file, a protocol handler of another app).
 */
object ExternalLinks {
    /** The address as a URI when it is an http or https address with a host, else null. */
    fun webUri(url: String): URI? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return uri
    }

    fun isWebUrl(url: String): Boolean = webUri(url) != null

    /** Opens [url] in the system browser. Throws when it is not a web address or cannot open. */
    fun browse(url: String) {
        val uri = webUri(url) ?: throw IllegalArgumentException("Not a web address: ${url.take(100)}")
        Desktop.getDesktop().browse(uri)
    }
}
