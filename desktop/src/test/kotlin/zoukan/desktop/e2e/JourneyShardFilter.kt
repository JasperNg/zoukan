package zoukan.desktop.e2e

import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.PostDiscoveryFilter
import java.util.Collections

/**
 * Keeps only the journeys of the shard `-Pzoukan.shard=i/n` asks for (the `zoukan.shard` system
 * property, see [JourneyShard]); every other test class passes untouched, and with the property
 * unset everything does. The JUnit Platform launcher loads it through ServiceLoader
 * (META-INF/services/org.junit.platform.launcher.PostDiscoveryFilter).
 */
class JourneyShardFilter(private val property: () -> String? = { System.getProperty(JourneyShard.PROPERTY) }) : PostDiscoveryFilter {

    /** Parsed on the first descriptor, so a bad value fails the run instead of running everything or nothing. */
    private val shard by lazy { JourneyShard.parse(property()) }

    private val reported = Collections.synchronizedSet(mutableSetOf<String>())

    override fun apply(descriptor: TestDescriptor): FilterResult {
        val shard = shard ?: return FilterResult.included("no journey shard")
        val className = when (val source = descriptor.source.orElse(null)) {
            is ClassSource -> source.className
            is MethodSource -> source.className
            else -> null
        }
        if (className == null || !JourneyShard.isJourney(className)) return FilterResult.included("not a journey")
        val kept = shard.includes(className)
        // Each journey has its own JVM (forkEvery = 1), so this prints once per journey
        if (reported.add(className)) println("Journey shard $shard: ${if (kept) "kept" else "skipped"} $className")
        return if (kept) FilterResult.included("in shard $shard") else FilterResult.excluded("not in shard $shard")
    }
}
