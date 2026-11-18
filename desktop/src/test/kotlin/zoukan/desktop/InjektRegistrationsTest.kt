package zoukan.desktop

import android.app.Application
import android.content.pm.PackageManager
import android.preference.PreferenceManager
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.TypeReference
import uy.kohesive.injekt.api.get
import zoukan.desktop.secrets.SecretStore
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
 * extensions look up (Application, NetworkHelper, Json) are among them. A lookup that passes a
 * Class makes no TypeReference and stays invisible to the scan, so a test forbids those.
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
     * A lookup that passes a Class (Injekt.getInstance(Foo::class.java), with or without a type
     * argument, getInstanceOrNull, or Foo.class from Java) makes no TypeReference, so the scan above
     * cannot see it; every lookup names its type with a reified call instead.
     */
    @Test
    fun `every lookup names its type so the scan sees it`() {
        val repo = File(System.getProperty("user.dir")).absoluteFile.parentFile
        val byClass = Regex("""getInstance(OrNull)?\s*(<[^>]*>)?\s*\(\s*[\w.]+(::class\.java|\.class)\s*\)""")
        val found = listOf("desktop/src/main", "extension-jvm/src/main", "android-compat/src/main")
            .map { File(repo, it) }.filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
            }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (byClass.containsMatchIn(line)) "${file.relativeTo(repo).invariantSeparatorsPath}:${i + 1}" else null
                }
            }
        found.shouldBeEmpty()
    }

    @Test
    fun `the secret store is one instance`() {
        System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
        ZoukanRuntime.start(ZoukanDirs(Files.createTempDirectory("zoukan-secrets").toFile()), interactiveBrowser = false)
        Injekt.get<SecretStore>() shouldBeSameInstanceAs ZoukanRuntime.secrets
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
        // Komikku's version, as AppInfo reports it, not Zoukan's
        val appInfo = pm.getPackageInfo(DesktopAppModule.PACKAGE_NAME, 0)
        appInfo.versionName shouldBe AppInfo.getVersionName()
        appInfo.longVersionCode shouldBe AppInfo.getVersionCode().toLong()
        // http.agent is the app's Chromium user agent (this OS, Chromium's version), not a fixed one
        System.getProperty("http.agent") shouldBe DesktopBrowser.userAgent
        System.getProperty("http.agent").shouldNotBeNull() shouldContain "Chrome/"

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
        return ProjectOutputs.onClassPath()
            .flatMap(::anonymousClassNames)
            .mapNotNull { name -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
            .filter { TypeReference::class.java.isAssignableFrom(it) && it.isAnonymousClass }
            .mapNotNull { (it.genericSuperclass as? ParameterizedType)?.actualTypeArguments?.single() }
            // Type parameters of generic helpers (inline functions' own T) are not lookups
            .filter { it is Class<*> || it is ParameterizedType }
            .distinctBy { it.typeName }
            .sortedBy { it.typeName }
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
