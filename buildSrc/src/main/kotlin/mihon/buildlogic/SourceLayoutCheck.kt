// ZOUKAN -->
package mihon.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * Fails the build when a Kotlin Multiplatform module has files in the Android-only layout
 * (`src/main/java`, `src/main/kotlin`, `src/main/sqldelight`, `src/test/...`).
 *
 * Zoukan moved several upstream modules to `src/commonMain` (see `desktop/UPSTREAM-MERGES.md`).
 * An upstream merge can still add new files under the old `src/main` paths, where the
 * multiplatform plugin silently ignores them. This check makes that a build error instead.
 */
internal fun Project.checkMultiplatformSourceLayout() {
    val forbidden = listOf(
        "src/main/java",
        "src/main/kotlin",
        // data's schema and migrations moved to src/commonMain/sqldelight
        "src/main/sqldelight",
        // domain's tests moved to src/androidUnitTest
        "src/test/java",
        "src/test/kotlin",
    ).associateWith { layout.projectDirectory.dir(it).asFile }
    val projectPath = path
    val check = tasks.register("checkMultiplatformSourceLayout") {
        group = "verification"
        description = "Fails if this multiplatform module has files in the Android-only src/main or src/test layout."
        doLast {
            // An empty folder left behind is harmless; only files are lost
            val misplaced = forbidden.filterValues { dir -> dir.walkTopDown().any { it.isFile } }.keys
            if (misplaced.isNotEmpty()) {
                throw GradleException(
                    "$projectPath is a multiplatform module, but has sources in " +
                        misplaced.joinToString() +
                        ", which the multiplatform plugin ignores. This is usually an upstream merge: move " +
                        "them to src/commonMain (src/androidMain if they use Android-only APIs, " +
                        "src/androidUnitTest for tests). " +
                        "See desktop/UPSTREAM-MERGES.md.",
                )
            }
        }
    }
    tasks.named("check") { dependsOn(check) }
    tasks.withType(KotlinCompilationTask::class.java).configureEach { dependsOn(check) }
}
// ZOUKAN <--
