package zoukan.desktop.browser.blocking

/**
 * A set of host names that also covers their subdomains: a set with `ads.example` matches
 * `ads.example` and `cdn.ads.example`, but not `example` or `badads.example`.
 */
class HostSet private constructor(private val hosts: Set<String>) {
    val size: Int get() = hosts.size

    /** Whether [host] or one of its parent domains is in the set. Never throws. */
    fun matches(host: String?): Boolean {
        if (hosts.isEmpty() || host.isNullOrEmpty()) return false
        var name = normalize(host) ?: return false
        while (true) {
            if (name in hosts) return true
            val dot = name.indexOf('.')
            if (dot < 0) return false
            name = name.substring(dot + 1)
        }
    }

    operator fun plus(other: HostSet): HostSet = when {
        other.hosts.isEmpty() -> this
        hosts.isEmpty() -> other
        else -> HostSet(hosts + other.hosts)
    }

    companion object {
        val EMPTY = HostSet(emptySet())

        /** A set of [hosts], each lowercased and without a trailing dot; blank names are left out. */
        fun of(hosts: Iterable<String>): HostSet = HostSet(hosts.mapNotNullTo(HashSet()) { normalize(it) })

        fun of(vararg hosts: String): HostSet = of(hosts.asIterable())

        internal fun normalize(host: String): String? =
            host.trim().trimEnd('.').lowercase().takeIf { it.isNotEmpty() }
    }
}
