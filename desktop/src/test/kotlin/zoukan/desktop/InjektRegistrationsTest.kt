package zoukan.desktop

import android.app.Application
import android.content.pm.PackageManager
import android.preference.PreferenceManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.TypeReference
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.AppVersion
import zoukan.extension.DesktopExtensionLoader
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
 * It needs no network, so it runs with every build, in its own JVM since it starts the runtime:
 *
 *   ./gradlew :desktop:wiringTest
 */
class InjektRegistrationsTest {

    @Test
    fun `every Injekt lookup has a registration`() {
        val types = lookedUpTypes()
        // About 180 when this was written; far fewer means the scan stopped finding them
        types.size shouldBeGreaterThan 100

        ZoukanRuntime.start(ZoukanDirs(Files.createTempDirectory("zoukan-injekt").toFile()), interactiveBrowser = false)
        val missing = types.filter { type -> runCatching { Injekt.getInstance<Any>(type) }.isFailure }
            .map { it.typeName }
        missing.shouldBeEmpty()
    }

    /**
     * The emulated app's identity as an extension sees it through the Application context: the
     * package name, the main looper, the default SharedPreferences name, and the package manager's
     * answer for the app and for an installed extension.
     */
    @Test
    fun `extensions see the app's package, looper and installed packages`() {
        ZoukanRuntime.start(ZoukanDirs(Files.createTempDirectory("zoukan-identity").toFile()), interactiveBrowser = false)
        val context = Injekt.get<Application>()
        context.packageName shouldBe DesktopAppModule.PACKAGE_NAME
        context.applicationInfo.packageName shouldBe DesktopAppModule.PACKAGE_NAME
        context.mainLooper.shouldNotBeNull()
        PreferenceManager.getDefaultSharedPreferencesName(context) shouldBe "app.komikku_preferences"
        // A name never opened is not an error
        context.deleteSharedPreferences("never_opened_${System.nanoTime()}") shouldBe true

        val pm = context.packageManager
        pm.getPackageInfo(DesktopAppModule.PACKAGE_NAME, 0).versionName shouldBe AppVersion.name

        // The loader tells the package manager where the extensions are
        Injekt.get<DesktopExtensionLoader>()
        val pkg = "eu.kanade.tachiyomi.extension.en.identitytest"
        val dir = File(Injekt.get<ZoukanDirs>().extensions, pkg).apply { mkdirs() }
        ZipOutputStream(File(dir, "7.jar").outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(
                """<manifest package="$pkg" android:versionCode="7" android:versionName="1.4.7"
                  xmlns:android="http://schemas.android.com/apk/res/android"/>""".toByteArray(),
            )
            out.closeEntry()
        }
        try {
            pm.getPackageInfo(pkg, 0).versionName shouldBe "1.4.7"
            // The API 33 overloads, reachable now that SDK_INT is 34
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName shouldBe "1.4.7"
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).packageName shouldBe pkg
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).map { it.packageName } shouldContain pkg
            pm.getInstalledPackages(0).map { it.packageName } shouldContain pkg
            shouldThrow<PackageManager.NameNotFoundException> { pm.getPackageInfo("$pkg.missing", 0) }
        } finally {
            dir.deleteRecursively()
        }
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
