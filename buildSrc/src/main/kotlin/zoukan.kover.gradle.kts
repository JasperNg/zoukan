// ZOUKAN -->
/**
 * Test coverage with Kover for a module whose code the desktop tests run (desktop/TESTING.md,
 * "Coverage"). Apply it after the Kotlin plugin. The root project aggregates every such module's
 * "unit" variant: `./gradlew :koverXmlReportUnit` writes build/reports/kover/reportUnit.xml.
 *
 * - The "unit" variant is the module's JVM classes (a Kotlin/JVM module) or its desktop target's
 *   (a multiplatform module), measured by its `test` or `desktopTest` task. The Android variants'
 *   classes and the Android unit tests stay out.
 * - Every other Test task (wiringTest, journeyTest, buttonCrawlTest, extensionWiringTest*, the
 *   Android unit tests) is never instrumented, and a Kover report does not run it.
 * - Tests are instrumented only when a Kover task is asked for on the command line, so an
 *   ordinary `test` run is not slowed down.
 * - No threshold: most of the code comes from upstream.
 */
plugins {
    id("org.jetbrains.kotlinx.kover")
}

/** The Test task the "unit" variant measures, and the Kover variant its classes come from. */
val (unitTestTask, unitVariantSource) =
    when {
        pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") -> "desktopTest" to "desktop"
        pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") -> "test" to "jvm"
        else -> throw GradleException("$path: apply zoukan.kover after the Kotlin JVM or multiplatform plugin")
    }

/**
 * Whether this build was asked for a Kover task (koverXmlReportUnit, koverHtmlReport, koverLog...),
 * or for coverage with -Pzoukan.coverage=true. CI's coverage job, which does not gate a merge, runs
 * `:koverXmlReportUnit -Pzoukan.coverage=true`: one build runs the tests instrumented and writes
 * the report.
 */
val coverageRequested = providers.gradleProperty("zoukan.coverage").orNull.toBoolean() ||
    gradle.startParameter.taskNames.any { "kover" in it.substringAfterLast(':').lowercase() }

kover {
    currentProject {
        createVariant("unit") { add(unitVariantSource) }
        instrumentation {
            disabledForAll.set(!coverageRequested)
            disabledForTestTasks.addAll(provider { tasks.withType<Test>().names.filter { it != unitTestTask } })
        }
    }
}
// ZOUKAN <--
