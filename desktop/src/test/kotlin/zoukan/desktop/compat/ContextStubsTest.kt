package zoukan.desktop.compat

import android.content.Context
import android.content.Intent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import xyz.nulldev.androidcompat.androidimpl.FakePackageManager
import xyz.nulldev.androidcompat.config.ApplicationInfoConfigModule
import xyz.nulldev.androidcompat.config.FilesConfigModule
import xyz.nulldev.androidcompat.config.SystemConfigModule
import xyz.nulldev.androidcompat.info.ApplicationInfoImpl
import xyz.nulldev.androidcompat.io.AndroidFiles
import xyz.nulldev.androidcompat.pm.PackageController
import xyz.nulldev.androidcompat.service.ServiceSupport
import xyz.nulldev.ts.config.CONFIG_PREFIX
import xyz.nulldev.ts.config.ConfigManager
import zoukan.desktop.testing.FreshInjekt
import java.io.File
import java.io.FileNotFoundException

/**
 * What an extension gets from the emulated Context and PackageManager where Android returns a
 * value or a checked exception: the private files of the app work (under its files folder), the
 * query methods return empty lists, and a call that is not supported fails with an exception that
 * names it, one `catch (Exception)` can handle, not with null or a Kotlin Error.
 */
class ContextStubsTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null
    private lateinit var context: CustomContext

    @BeforeEach
    fun setUp() {
        previousRoot = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
        // FakePackageManager reads the app's global PackageController
        if (runCatching { Injekt.get<PackageController>() }.isFailure) {
            Injekt.addSingletonFactory { PackageController() }
        }
        val manager = object : ConfigManager() {}
        manager.registerModules(
            FilesConfigModule.register(manager.config),
            ApplicationInfoConfigModule.register(manager.config),
            SystemConfigModule.register(manager.config),
        )
        val scope = InjektScope(DefaultRegistrar())
        scope.addSingleton(AndroidFiles(manager))
        scope.addSingleton(ApplicationInfoImpl(manager))
        scope.addSingleton(ServiceSupport())
        scope.addSingleton(FakePackageManager())
        context = CustomContext(scope)
    }

    @AfterEach
    fun restoreRoot() {
        previousRoot?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    @Test
    fun `openFileOutput and openFileInput round trip under the files folder`() {
        context.openFileOutput("probe.txt", Context.MODE_PRIVATE).use { it.write("hello".toByteArray()) }

        context.openFileInput("probe.txt").use { it.readBytes().decodeToString() } shouldBe "hello"
        val path = context.getFileStreamPath("probe.txt")
        path.parentFile.canonicalFile shouldBe context.filesDir.canonicalFile
        path.isFile shouldBe true
    }

    @Test
    fun `openFileOutput in append mode adds to the file`() {
        context.openFileOutput("log.txt", Context.MODE_PRIVATE).use { it.write("a".toByteArray()) }
        context.openFileOutput("log.txt", Context.MODE_APPEND).use { it.write("b".toByteArray()) }

        context.openFileInput("log.txt").use { it.readBytes().decodeToString() } shouldBe "ab"
    }

    @Test
    fun `a file name with a path separator is refused, as on Android`() {
        shouldThrow<IllegalArgumentException> { context.openFileOutput("../escape.txt", Context.MODE_PRIVATE) }
        shouldThrow<IllegalArgumentException> { context.openFileInput("sub/file.txt") }
        shouldThrow<IllegalArgumentException> { context.getFileStreamPath("..\\escape.txt") }
        File(context.filesDir.parentFile, "escape.txt").exists() shouldBe false
    }

    @Test
    fun `openFileInput of a missing file throws FileNotFoundException`() {
        shouldThrow<FileNotFoundException> { context.openFileInput("missing.txt") }
    }

    @Test
    fun `getDir returns a folder under the app data`() {
        val dir = context.getDir("cache2", Context.MODE_PRIVATE)

        dir.isDirectory shouldBe true
        dir.name shouldBe "app_cache2"
        dir.canonicalFile.startsWith(context.dataDir.canonicalFile) shouldBe true
    }

    @Test
    fun `queryIntentActivities returns an empty list`() {
        context.packageManager.queryIntentActivities(Intent(), 0).shouldBeEmpty()
    }

    @Test
    fun `unsupported PackageManager calls throw a catchable exception naming the call`() {
        val error = shouldThrow<UnsupportedOperationException> { context.packageManager.getLaunchIntentForPackage("x") }
        error.message!! shouldContain "getLaunchIntentForPackage"
    }
}
