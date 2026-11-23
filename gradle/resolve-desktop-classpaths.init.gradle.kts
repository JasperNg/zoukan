// Used only to refresh gradle/verification-metadata.xml for another OS (see CONTRIBUTING.md):
//   ./gradlew -I gradle/resolve-desktop-classpaths.init.gradle.kts --write-verification-metadata sha256 \
//       -Pzoukan.targetPlatform=linux-x64 :desktop:resolveDesktopClasspaths
// Resolves the desktop module's compile and runtime classpaths, so Gradle records the checksums of the
// native libraries zoukan.targetPlatform picks, without building anything.
allprojects {
    if (path == ":desktop") {
        tasks.register("resolveDesktopClasspaths") {
            val files = listOf(
                "runtimeClasspath",
                "testRuntimeClasspath",
                "compileClasspath",
                "testCompileClasspath",
                "toolsCompileClasspath",
                "toolsRuntimeClasspath",
            ).map { configurations.getByName(it) }
            doLast { files.forEach { it.files } }
        }
    }
}
