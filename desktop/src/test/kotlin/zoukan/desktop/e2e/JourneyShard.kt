package zoukan.desktop.e2e

/** Which journeys a CI job runs: shard [index] (1-based) of [count], by a stable hash of the class name. */
data class JourneyShard(val index: Int, val count: Int) {
    init {
        require(count >= 1 && index in 1..count) { "zoukan.shard must be i/n with 1 <= i <= n, got $index/$count" }
    }

    /** String.hashCode is specified by the JDK, so a class lands in the same shard on every JVM and machine. */
    fun includes(className: String): Boolean = Math.floorMod(className.hashCode(), count) == index - 1

    override fun toString() = "$index/$count"

    companion object {
        const val PROPERTY = "zoukan.shard"

        fun isJourney(className: String) = className.startsWith("zoukan.desktop.e2e.") && className.endsWith("JourneyTest")

        /** The shard from "i/n"; null when unset. */
        fun parse(value: String?): JourneyShard? {
            if (value.isNullOrBlank()) return null
            val parts = value.split('/')
            require(parts.size == 2) { "zoukan.shard must be i/n, got '$value'" }
            val i = requireNotNull(parts[0].trim().toIntOrNull()) { "zoukan.shard must be i/n, got '$value'" }
            val n = requireNotNull(parts[1].trim().toIntOrNull()) { "zoukan.shard must be i/n, got '$value'" }
            return JourneyShard(i, n)
        }
    }
}
