package zoukan.desktop.ui.settings

import java.net.InetAddress
import java.net.URI

/**
 * Whether a server address typed in Settings can be saved. Only the text is read: no host name is
 * looked up (that would be a DNS query outside DNS over HTTPS).
 */
internal object ServerAddress {
    sealed interface Verdict {
        data object Ok : Verdict

        /** An address that sends data in clear over the internet. */
        data object PlainHttp : Verdict

        /** Not saved: not an http(s) address. */
        data object Invalid : Verdict
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val DIGITS_AND_DOTS = Regex("""[0-9.]+""")

    fun check(text: String): Verdict {
        val address = text.trim()
        if (address.isEmpty()) return Verdict.Ok
        val uri = runCatching { URI(address) }.getOrNull() ?: return Verdict.Invalid
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return Verdict.Invalid
        return when (uri.scheme?.lowercase()) {
            "https" -> Verdict.Ok
            "http" -> if (isLocalHost(host)) Verdict.Ok else Verdict.PlainHttp
            else -> Verdict.Invalid
        }
    }

    /** This computer or the local network, judged from the text of [host] alone. */
    private fun isLocalHost(host: String): Boolean {
        val name = host.lowercase()
        if (name.startsWith("[") && name.endsWith("]")) return isLocalIpv6(name.substring(1, name.length - 1))
        if (IPV4.matches(name)) return isLocalIpv4(name)
        // A dotted number that is not an address is not a local name
        if (DIGITS_AND_DOTS.matches(name)) return false
        return name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".lan") || '.' !in name
    }

    private fun isLocalIpv4(address: String): Boolean {
        val parts = address.split('.').map { it.toInt() }
        if (parts.any { it > 255 }) return false
        val (a, b) = parts
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    private fun isLocalIpv6(literal: String): Boolean {
        // An IP literal is parsed, never looked up
        val ip = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
        val unique = ip.address.size == 16 && (ip.address[0].toInt() and 0xFE) == 0xFC
        return ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || unique
    }
}
