// ZOUKAN -->
/**
 * Gives each desktop test JVM a home folder and a data folder of its own under
 * build/test-home/<task>, so no test reads or writes the developer's real home or Zoukan profile
 * (desktop/TESTING.md, "The home folder and file-system side effects"): `user.home` (the macOS and Linux defaults, Skiko's
 * and JNA's native caches, PageActions' Pictures folder), `zoukan.dataDir` (ZoukanDirs.default()
 * everywhere, Windows' %APPDATA% too) and `zoukan.test.home`, which tells the tests' TestHome that
 * `user.home` is this folder. A key the task sets itself is left as it is.
 *
 * Apply it after the Kotlin plugin. In a Kotlin/JVM module it applies to every Test task (in
 * desktop: test, wiringTest, journeyTest, extensionWiringTest*); in a
 * multiplatform module only to desktopTest, so the Android unit tests are left as they are.
 *
 * The OS secret stores (Keychain, Credential Manager, the Secret Service) do not go by `user.home`,
 * so the extension wiring check still reaches them. Only the folder's place in the build is an
 * input, so the path of the checkout does not make the task out of date.
 */

/** The `-D` arguments; [taskProperties] is the task's own systemProperties, read when the JVM starts. */
class TestHomeArguments(
    @get:Internal val root: File,
    @get:Input val relativeRoot: String,
    @get:Internal val taskProperties: Map<String, Any?>,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> {
        val home = File(root, "home").apply { mkdirs() }
        val data = File(root, "data")
        return listOf("user.home" to home, "zoukan.test.home" to home, "zoukan.dataDir" to data)
            .filter { (key, _) -> key !in taskProperties }
            .map { (key, dir) -> "-D$key=${dir.absolutePath}" }
    }
}

val desktopTestsOnly = when {
    pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") -> true
    pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") -> false
    else -> throw GradleException("$path: apply zoukan.test-home after the Kotlin JVM or multiplatform plugin")
}

tasks.withType<Test>().matching { !desktopTestsOnly || it.name == "desktopTest" }.configureEach {
    jvmArgumentProviders.add(
        TestHomeArguments(layout.buildDirectory.dir("test-home/$name").get().asFile, "test-home/$name", systemProperties),
    )
}
// ZOUKAN <--
