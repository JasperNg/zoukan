package zoukan.desktop.browser.blocking

/**
 * The hosts a list blocks and the hosts it excepts (`@@||host^`). An exception host, and its
 * subdomains, is never blocked, whichever list blocks it.
 */
class BlockRules(val hosts: HostSet, val exceptions: HostSet = HostSet.EMPTY) {
    val isEmpty: Boolean get() = hosts.size == 0 && exceptions.size == 0

    operator fun plus(other: BlockRules): BlockRules = when {
        other.isEmpty -> this
        isEmpty -> other
        else -> BlockRules(hosts + other.hosts, exceptions + other.exceptions)
    }

    companion object {
        val EMPTY = BlockRules(HostSet.EMPTY)
    }
}
