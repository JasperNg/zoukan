package zoukan.desktop.property

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import kotlin.random.Random

/**
 * How the property tests run: a fixed seed, so a run is repeatable and a failure on one machine
 * fails the same way on another, and a bounded number of tries, so they stay in the fast unit tier.
 *
 * To repeat a run with another seed, or to search longer by hand:
 *
 *     ./gradlew :desktop:test --tests "zoukan.desktop.property.*" -Pzoukan.property.seed=42 -Pzoukan.property.iterations=20000
 *
 * Kotest names the seed in a failure's message.
 */
object PropertyRuns {
    const val DEFAULT_SEED = 20_261_118L

    val seed: Long = System.getProperty("zoukan.property.seed")?.toLongOrNull() ?: DEFAULT_SEED

    private val iterationsOverride: Int? = System.getProperty("zoukan.property.iterations")?.toIntOrNull()

    /** [iterations] tries with the run's seed, unless `zoukan.property.iterations` asks for more or fewer. */
    fun config(iterations: Int): PropTestConfig = PropTestConfig(seed = seed, iterations = iterationsOverride ?: iterations)
}

/** An [Arb] from a plain [Random]: the generators here pick from lists of known bad cases and random text. */
fun <T> arbFrom(generate: (Random) -> T): Arb<T> = arbitrary { rs -> generate(rs.random) }

/** [s] with every character that is not printable ASCII written as `\uXXXX`, for failure messages. */
fun escaped(s: String): String = buildString {
    s.forEach { c -> if (c in ' '..'~' && c != '\\') append(c) else append("\\u%04x".format(c.code)) }
}

/** The shortest of each kind of failure, so a report shows the simplest input that fails. */
class Counterexamples<K : Any> {
    private val shortest = linkedMapOf<K, String>()

    fun add(kind: K, input: String, detail: String) {
        val text = "input \"${escaped(input)}\" (${input.length} chars): $detail"
        val old = shortest[kind]
        if (old == null || text.length < old.length) shortest[kind] = text
    }

    fun kinds(): Set<K> = shortest.keys

    fun report(kinds: Collection<K> = shortest.keys): String =
        kinds.filter { it in shortest }.joinToString("\n") { "$it: ${shortest[it]}" }
}
