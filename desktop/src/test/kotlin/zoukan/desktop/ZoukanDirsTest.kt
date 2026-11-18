package zoukan.desktop

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File

class ZoukanDirsTest {
    private val home = "C:\\Users\\someone"
    private val windowsEnv = mapOf(
        "APPDATA" to "C:\\Users\\someone\\AppData\\Roaming",
        "LOCALAPPDATA" to "C:\\Users\\someone\\AppData\\Local",
    )

    /** The value the build set for every test, put back after each one. */
    private val previousDataDir: String? = System.getProperty("zoukan.dataDir")

    @AfterEach
    fun tearDown() {
        previousDataDir?.let { System.setProperty("zoukan.dataDir", it) } ?: System.clearProperty("zoukan.dataDir")
    }

    @Test
    fun `on Windows the caches leave the roaming data folder and the rest stays`() {
        val dirs = ZoukanDirs.default(Platform.WINDOWS, null, windowsEnv::get, home)
        val roaming = File(windowsEnv.getValue("APPDATA"), "Zoukan")
        val local = File(windowsEnv.getValue("LOCALAPPDATA"), "Zoukan-local")

        dirs.root shouldBe roaming
        dirs.localRoot shouldBe local
        dirs.hasLocalRoot shouldBe true
        dirs.browser shouldBe File(local, "browser")
        dirs.browserCache shouldBe File(local, "cache/browser")
        dirs.images shouldBe File(local, "cache/images")
        dirs.javacppCache shouldBe File(local, "cache/javacpp")
        dirs.archives shouldBe File(local, "cache/archives")
        dirs.tempFiles shouldBe File(local, "cache/tmp")
        // The user's data, and covers (custom covers are the user's own), stay in the data folder
        dirs.database shouldBe File(roaming, "zoukan.db")
        dirs.covers shouldBe File(roaming, "cache/covers")
        dirs.cookies shouldBe File(roaming, "cookies.json")
        dirs.backups shouldBe File(roaming, "backups")
        dirs.secrets shouldBe File(roaming, "secrets")
        dirs.logs shouldBe File(roaming, "logs")
    }

    @Test
    fun `the local folder is not the folder the per-user installer uses`() {
        val dirs = ZoukanDirs.default(Platform.WINDOWS, null, windowsEnv::get, home)
        (dirs.localRoot == File(windowsEnv.getValue("LOCALAPPDATA"), "Zoukan")) shouldBe false
        // Nor inside the harness profile, nor the other way round
        val harness = File(windowsEnv.getValue("LOCALAPPDATA"), "Zoukan\\harness").toPath()
        dirs.localRoot.toPath().startsWith(harness) shouldBe false
        harness.startsWith(dirs.localRoot.toPath()) shouldBe false
    }

    @Test
    fun `without the environment variables Windows falls back to the home folder`() {
        val dirs = ZoukanDirs.default(Platform.WINDOWS, null, { null }, home)
        dirs.root shouldBe File("$home\\AppData\\Roaming", "Zoukan")
        dirs.localRoot shouldBe File("$home\\AppData\\Local", "Zoukan-local")
    }

    @Test
    fun `a chosen data folder keeps its caches inside it`() {
        val byProperty = ZoukanDirs.default(Platform.WINDOWS, "D:\\Portable\\Zoukan", windowsEnv::get, home)
        byProperty.hasLocalRoot shouldBe false
        byProperty.browserCache shouldBe File(byProperty.root, "cache/browser")

        val byVariable = ZoukanDirs.default(Platform.WINDOWS, null, (windowsEnv + ("ZOUKAN_DATA_DIR" to "D:\\Z"))::get, home)
        byVariable.hasLocalRoot shouldBe false
        byVariable.browser shouldBe File(byVariable.root, "browser")
    }

    @Test
    fun `macOS and Linux keep everything in the data folder`() {
        for (platform in listOf(Platform.MACOS, Platform.LINUX)) {
            val dirs = ZoukanDirs.default(platform, null, { null }, "/home/someone")
            dirs.hasLocalRoot shouldBe false
            dirs.browserCache shouldBe File(dirs.root, "cache/browser")
            // On macOS the disposable caches take their `.noindex` names, in the data folder
            val archives = if (platform == Platform.MACOS) "cache/archives.noindex" else "cache/archives"
            dirs.archives shouldBe File(dirs.root, archives)
        }
    }

    @Test
    fun `only macOS gives the disposable folders the noindex names, whatever this computer runs`() {
        ZoukanDirs.default(Platform.MACOS, null, { null }, "/home/someone").hidesFromSpotlight shouldBe true
        ZoukanDirs.default(Platform.LINUX, null, { null }, "/home/someone").hidesFromSpotlight shouldBe false
        val windows = ZoukanDirs.default(Platform.WINDOWS, null, windowsEnv::get, home)
        windows.hidesFromSpotlight shouldBe false
        windows.images shouldBe File(windows.localRoot, "cache/images")
    }

    @Test
    fun `a cache kept in the data folder keeps the noindex name on macOS`() {
        val dirs = ZoukanDirs(File("/tmp/profile"), File("/tmp/profile-local"), hidesFromSpotlight = true)
            .keepingInRoot(setOf(ZoukanDirs.IMAGES))
        dirs.hidesFromSpotlight shouldBe true
        dirs.images shouldBe File("/tmp/profile", "cache/images.noindex")
        dirs.archives shouldBe File("/tmp/profile-local", "cache/archives.noindex")
        dirs.tempFiles shouldBe File("/tmp/profile-local", "cache/tmp.noindex")
    }

    @Test
    fun `a folder built from one root keeps everything in it, as the tests and the harness need`() {
        val dirs = ZoukanDirs(File("/tmp/profile"))
        dirs.hasLocalRoot shouldBe false
        dirs.browserCache shouldBe File("/tmp/profile", "cache/browser")
    }

    @Test
    fun `the harness profile keeps its caches inside it`() {
        System.setProperty("zoukan.dataDir", "/tmp/zoukan-data")
        ZoukanDirs.harness().hasLocalRoot shouldBe false
    }

    @Test
    fun `a cache that could not move is used in the data folder, the others in the local folder`() {
        val dirs = ZoukanDirs.default(Platform.WINDOWS, null, windowsEnv::get, home)
            .keepingInRoot(setOf(ZoukanDirs.BROWSER_PROFILE))
        dirs.browserCache shouldBe File(dirs.root, "cache/browser")
        dirs.browser shouldBe File(dirs.localRoot, "browser")
        dirs.images shouldBe File(dirs.localRoot, "cache/images")
    }
}
