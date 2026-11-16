package zoukan.desktop.e2e

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.PostDiscoveryFilter
import java.io.File
import java.util.ServiceLoader

/** `-Pzoukan.shard=i/n` splits the journeys so that the n shards together run each exactly once. */
class JourneyShardTest {

    @Test
    fun `the shards are disjoint and together hold every journey`() {
        val journeys = journeyClasses()
        withClue("journey classes found: $journeys") { journeys.size shouldBeGreaterThanOrEqual 10 }
        for (count in 1..5) {
            val shards = (1..count).map { JourneyShard(it, count) }
            journeys.forEach { journey ->
                withClue("$journey in shards of $count") { shards.count { it.includes(journey) } shouldBe 1 }
            }
            shards.flatMap { shard -> journeys.filter { shard.includes(it) } } shouldContainExactlyInAnyOrder journeys
        }
    }

    @Test
    fun `parse reads i over n and rejects anything else`() {
        JourneyShard.parse(null).shouldBeNull()
        JourneyShard.parse("").shouldBeNull()
        JourneyShard.parse("2/3") shouldBe JourneyShard(2, 3)
        listOf("0/3", "4/3", "3/2", "3", "a/b", "1/0", "1/2/3", "-1/3").forEach { value ->
            withClue(value) { shouldThrow<IllegalArgumentException> { JourneyShard.parse(value) } }
        }
    }

    @Test
    fun `the filter excludes only journeys outside the shard`() {
        val journeys = journeyClasses()
        val shard = JourneyShard(1, 3)
        val filter = JourneyShardFilter { "1/3" }
        val others = listOf(
            "zoukan.desktop.ui.ScreenSmokeTest",
            "zoukan.desktop.InjektRegistrationsTest",
            JourneyShardTest::class.java.name,
            "zoukan.desktop.e2e.FixtureServerTest",
            // Named like a journey, but not one of the journeys' package
            "zoukan.desktop.ui.SomeJourneyTest",
        )
        others.forEach { name -> withClue(name) { filter.apply(classDescriptor(name)).included() shouldBe true } }
        journeys.forEach { name ->
            withClue(name) {
                filter.apply(classDescriptor(name)).included() shouldBe shard.includes(name)
                filter.apply(methodDescriptor(name)).included() shouldBe shard.includes(name)
            }
        }
        // Unset: everything runs
        journeys.forEach { name -> JourneyShardFilter { null }.apply(classDescriptor(name)).included() shouldBe true }
    }

    @Test
    fun `the filter fails on a bad shard instead of running everything or nothing`() {
        val filter = JourneyShardFilter { "4/3" }
        val error = shouldThrow<IllegalArgumentException> { filter.apply(classDescriptor("zoukan.desktop.e2e.AvifJourneyTest")) }
        error.message shouldBe "zoukan.shard must be i/n with 1 <= i <= n, got 4/3"
    }

    @Test
    fun `the launcher loads the filter from the service file`() {
        ServiceLoader.load(PostDiscoveryFilter::class.java).map { it::class.java } shouldContain JourneyShardFilter::class.java
    }

    @Test
    fun `every test class in the journey package is a journey that journeyTest runs`() {
        // `test` leaves out zoukan.desktop.e2e.*JourneyTest and journeyTest runs only those, so a
        // journey named otherwise would run in the shared JVM of `test`, where the runtime may
        // already be started. Unit tests of the kit itself are listed here.
        val unitTests = setOf(JourneyShardTest::class.java.name, ReadThroughMovesTest::class.java.name)
        val misnamed = topLevelClasses()
            .filter { it !in unitTests && !JourneyShard.isJourney(it) }
            .filter { name -> Class.forName(name, false, javaClass.classLoader).declaredMethods.any { it.isAnnotationPresent(Test::class.java) } }
        withClue("name each journey *JourneyTest, or list a unit test of the kit here") { misnamed.shouldBeEmpty() }
        journeyClasses() shouldContain AvifJourneyTest::class.java.name
    }

    /** The compiled top-level classes of zoukan.desktop.e2e and its subpackages (no nested or lambda classes). */
    private fun topLevelClasses(): List<String> {
        val root = File(JourneyShardTest::class.java.protectionDomain.codeSource.location.toURI())
        return File(root, "zoukan/desktop/e2e").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") && '$' !in it.name }
            .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
            .sorted()
            .toList()
    }

    /** The journey classes `journeyTest` runs: the compiled top-level ...JourneyTest classes of zoukan.desktop.e2e and its subpackages. */
    private fun journeyClasses(): List<String> = topLevelClasses().filter { JourneyShard.isJourney(it) }

    private fun classDescriptor(className: String): TestDescriptor = Descriptor(className, ClassSource.from(className))

    private fun methodDescriptor(className: String): TestDescriptor = Descriptor("$className#run", MethodSource.from(className, "run"))

    private class Descriptor(id: String, source: TestSource) :
        AbstractTestDescriptor(UniqueId.forEngine("fake").append("test", id), id, source) {
        override fun getType() = TestDescriptor.Type.CONTAINER
    }
}
