package zoukan.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.TypeReference
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Every type that the code the desktop app compiles looks up through Injekt (Injekt.get(),
 * injectLazy(), get() in a module) has a registration, so no screen or source fails at run time
 * with "No registered instance or factory" (as metadata sources' page previews and Settings >
 * Advanced > Reset per-series reader settings did).
 *
 * Injekt's reified lookups compile to an anonymous TypeReference subclass at each call site, whose
 * generic superclass is the type looked up, so the test finds them in the compiled classes of
 * desktop and the project's modules rather than in the sources: inferred types count too. Each
 * type is then resolved, which also runs its factory and the lookups inside it. The types that
 * extensions look up (Application, NetworkHelper, Json) are among them.
 *
 *   ./gradlew :desktop:test -Pzoukan.e2e=true --tests "*InjektRegistrationsTest"
 */
class InjektRegistrationsTest {

    @Test
    fun `every Injekt lookup has a registration`() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "Starts the runtime; run with -Pzoukan.e2e=true")
        val types = lookedUpTypes()
        // About 180 when this was written; far fewer means the scan stopped finding them
        types.size shouldBeGreaterThan 100

        ZoukanRuntime.start(ZoukanDirs(Files.createTempDirectory("zoukan-e2e-injekt").toFile()), interactiveBrowser = false)
        val missing = types.filter { type -> runCatching { Injekt.getInstance<Any>(type) }.isFailure }
            .map { it.typeName }
        missing.shouldBeEmpty()
    }

    private fun lookedUpTypes(): List<Type> {
        val loader = javaClass.classLoader
        return System.getProperty("java.class.path").split(File.pathSeparator)
            .map(::File)
            .filter(::isProjectOutput)
            .flatMap(::anonymousClassNames)
            .mapNotNull { name -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
            .filter { TypeReference::class.java.isAssignableFrom(it) && it.isAnonymousClass }
            .mapNotNull { (it.genericSuperclass as? ParameterizedType)?.actualTypeArguments?.single() }
            // Type parameters of generic helpers (inline functions' own T) are not lookups
            .filter { it is Class<*> || it is ParameterizedType }
            .distinctBy { it.typeName }
            .sortedBy { it.typeName }
    }

    /** The desktop app's classes and the jars of the project's own modules, not libraries. */
    private fun isProjectOutput(file: File): Boolean {
        val root = File(System.getProperty("user.dir")).absoluteFile.parentFile
        val path = file.absoluteFile
        return path.startsWith(root) && !path.path.contains("${File.separator}test") &&
            (path.isDirectory || path.name.endsWith(".jar"))
    }

    private fun anonymousClassNames(file: File): List<String> {
        fun className(path: String) = path.removeSuffix(".class").replace('/', '.').replace('\\', '.')
        return if (file.isDirectory) {
            file.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") && '$' in it.name }
                .map { className(it.relativeTo(file).path) }
                .toList()
        } else {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") && '$' in it }
                    .map(::className)
                    .toList()
            }
        }
    }
}
