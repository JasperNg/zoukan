package zoukan.desktop

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class CacheFoldersTest {
    @TempDir
    lateinit var temp: File

    private val roaming get() = File(temp, "Roaming/Zoukan")
    private val local get() = File(temp, "Local/Zoukan-local")
    private fun windowsDirs() = ZoukanDirs(roaming, local)

    private val anotherVolume: (Path, Path) -> Unit = { from, to -> throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "another volume") }

    private fun File.write(relative: String, text: String) = File(this, relative).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    /** The user's own data, which no move may touch. */
    private fun userData() {
        roaming.write("zoukan.db", "db")
        roaming.write("cookies.json", "cookies")
        roaming.write("cache/covers/custom/abc", "custom cover")
        roaming.write("cache/covers/def", "cover")
        roaming.write("backups/auto.tachibk", "backup")
    }

    private fun userDataIsIntact() {
        File(roaming, "zoukan.db").readText() shouldBe "db"
        File(roaming, "cookies.json").readText() shouldBe "cookies"
        File(roaming, "cache/covers/custom/abc").readText() shouldBe "custom cover"
        File(roaming, "cache/covers/def").readText() shouldBe "cover"
        File(roaming, "backups/auto.tachibk").readText() shouldBe "backup"
    }

    @Test
    fun `the old caches are renamed into the local folder`() {
        userData()
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        roaming.write("browser/install.lock", "")
        roaming.write("cache/images/journal", "journal")

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "chromium cookies"
        File(dirs.browser, "install.lock").exists() shouldBe true
        File(dirs.images, "journal").readText() shouldBe "journal"
        File(roaming, "cache/browser").exists() shouldBe false
        File(roaming, "browser").exists() shouldBe false
        File(roaming, "cache/images").exists() shouldBe false
        userDataIsIntact()
    }

    @Test
    fun `on another volume the folder is copied and the old one deleted only afterwards`() {
        userData()
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        roaming.write("cache/browser/Local State", "state")

        val dirs = CacheFolders.migrate(windowsDirs(), anotherVolume)

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "chromium cookies"
        File(dirs.browserCache, "Local State").readText() shouldBe "state"
        File(roaming, "cache/browser").exists() shouldBe false
        File(local, "cache/browser.moving").exists() shouldBe false
        userDataIsIntact()
    }

    @Test
    fun `a folder in use stays where it is for this run and the others still move`() {
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        roaming.write("cache/images/journal", "journal")
        val inUse: (Path, Path) -> Unit = { from, to ->
            if (from.endsWith("browser") && from.parent.endsWith("cache")) throw AccessDeniedException(from.toString())
            Files.move(from, to)
        }

        val dirs = CacheFolders.migrate(windowsDirs(), inUse)

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "chromium cookies"
        File(local, "cache/browser").exists() shouldBe false
        dirs.images shouldBe File(local, "cache/images")
        File(dirs.images, "journal").readText() shouldBe "journal"

        // The next start moves it
        val next = CacheFolders.migrate(windowsDirs())
        next.browserCache shouldBe File(local, "cache/browser")
        File(next.browserCache, "Default/Cookies").readText() shouldBe "chromium cookies"
    }

    @Test
    fun `a copy that fails keeps the old folder whole and leaves no half copy`() {
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        // A link inside the folder is never copied, so the copy fails
        Files.createSymbolicLink(File(roaming, "cache/browser/elsewhere").toPath(), temp.toPath())

        val dirs = CacheFolders.migrate(windowsDirs(), anotherVolume)

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        File(roaming, "cache/browser/Default/Cookies").readText() shouldBe "chromium cookies"
        File(local, "cache/browser").exists() shouldBe false
        File(local, "cache/browser.moving").exists() shouldBe false
        temp.exists() shouldBe true
    }

    @Test
    fun `an old folder that is a link is left alone and used where it is`() {
        val target = File(temp, "elsewhere").apply { mkdirs() }
        File(target, "keep").writeText("keep")
        File(roaming, "cache").mkdirs()
        Files.createSymbolicLink(File(roaming, "cache/browser").toPath(), target.toPath())

        val dirs = CacheFolders.migrate(windowsDirs(), anotherVolume)

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        File(target, "keep").readText() shouldBe "keep"
    }

    @Test
    fun `a leftover of the old folder is deleted once the new one exists`() {
        roaming.write("cache/browser/leftover", "old")
        local.write("cache/browser/Default/Cookies", "new")

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "new"
        File(roaming, "cache/browser").exists() shouldBe false
    }

    /** Sets the modification time of [this] folder and everything in it to [daysAgo] days ago. */
    private fun File.usedDaysAgo(daysAgo: Long) {
        val time = FileTime.fromMillis(System.currentTimeMillis() - daysAgo * 24 * 60 * 60 * 1000)
        walkBottomUp().forEach { Files.setLastModifiedTime(it.toPath(), time) }
    }

    @Test
    fun `a browser profile an older version used more recently replaces the new one`() {
        local.write("cache/browser/Default/Cookies", "new")
        local.write("cache/browser/Default/Only in new", "x")
        roaming.write("cache/browser/Default/Cookies", "old, used later")
        File(local, "cache/browser").usedDaysAgo(2)
        File(roaming, "cache/browser").usedDaysAgo(1)

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "old, used later"
        File(dirs.browserCache, "Default/Only in new").exists() shouldBe false
        File(roaming, "cache/browser").exists() shouldBe false
        File(local, "cache/browser.replaced").exists() shouldBe false
    }

    @Test
    fun `a browser profile used more recently in the new place is kept`() {
        local.write("cache/browser/Default/Cookies", "new, used later")
        roaming.write("cache/browser/Default/Cookies", "old")
        File(roaming, "cache/browser").usedDaysAgo(2)
        File(local, "cache/browser").usedDaysAgo(1)

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "new, used later"
        File(roaming, "cache/browser").exists() shouldBe false
    }

    @Test
    fun `a plain cache keeps the new folder even when the old one is more recent`() {
        local.write("cache/images/journal", "new")
        roaming.write("cache/images/journal", "old")
        File(local, "cache/images").usedDaysAgo(2)
        File(roaming, "cache/images").usedDaysAgo(1)

        val dirs = CacheFolders.migrate(windowsDirs())

        File(dirs.images, "journal").readText() shouldBe "new"
        File(roaming, "cache/images").exists() shouldBe false
    }

    @Test
    fun `a replacement that fails half way keeps both profiles and uses the more recent one`() {
        local.write("cache/browser/Default/Cookies", "new")
        roaming.write("cache/browser/Default/Cookies", "old, used later")
        File(local, "cache/browser").usedDaysAgo(2)
        File(roaming, "cache/browser").usedDaysAgo(1)
        // Setting the new folder aside works; moving the old one in fails (a file in use)
        val inUse: (Path, Path) -> Unit = { from, to ->
            if (from.startsWith(roaming.toPath())) throw AccessDeniedException(from.toString())
            Files.move(from, to)
        }

        val dirs = CacheFolders.migrate(windowsDirs(), inUse)

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "old, used later"
        File(local, "cache/browser/Default/Cookies").readText() shouldBe "new"
        File(local, "cache/browser.replaced").exists() shouldBe false

        // The next start finishes it
        val next = CacheFolders.migrate(windowsDirs())
        next.browserCache shouldBe File(local, "cache/browser")
        File(next.browserCache, "Default/Cookies").readText() shouldBe "old, used later"
        File(roaming, "cache/browser").exists() shouldBe false
        File(local, "cache/browser.replaced").exists() shouldBe false
    }

    @Test
    fun `a replacement cut short after the new profile was set aside is finished`() {
        // Stopped after setting the new folder aside, before the old one moved in
        local.write("cache/browser.replaced/Default/Cookies", "new")
        roaming.write("cache/browser/Default/Cookies", "old, used later")

        val dirs = CacheFolders.migrate(windowsDirs())

        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "old, used later"
        File(local, "cache/browser.replaced").exists() shouldBe false
        File(roaming, "cache/browser").exists() shouldBe false
    }

    @Test
    fun `a set-aside profile with nothing to replace it is put back`() {
        local.write("cache/browser.replaced/Default/Cookies", "new")

        val dirs = CacheFolders.migrate(windowsDirs())

        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "new"
        File(local, "cache/browser.replaced").exists() shouldBe false
    }

    @Test
    fun `a staging folder left by an interrupted copy is replaced`() {
        roaming.write("cache/images/a", "a")
        local.write("cache/images.moving/stale", "stale")

        val dirs = CacheFolders.migrate(windowsDirs(), anotherVolume)

        File(dirs.images, "a").readText() shouldBe "a"
        File(dirs.images, "stale").exists() shouldBe false
        File(local, "cache/images.moving").exists() shouldBe false
    }

    @Test
    fun `when the local folder cannot be made every cache stays in the data folder`() {
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        local.parentFile.mkdirs()
        local.writeText("a file where the folder should be")

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        dirs.browser shouldBe File(roaming, "browser")
        dirs.images shouldBe File(roaming, "cache/images")
        File(roaming, "cache/browser/Default/Cookies").readText() shouldBe "chromium cookies"
    }

    @Test
    fun `a new install has nothing to move and uses the local folder`() {
        val dirs = CacheFolders.migrate(windowsDirs()).create()
        dirs.browserCache shouldBe File(local, "cache/browser")
        dirs.browserCache.isDirectory shouldBe true
        File(roaming, "cache/browser").exists() shouldBe false
    }

    @Test
    fun `a data folder without a local folder is left as it is`() {
        val dirs = ZoukanDirs(roaming)
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")

        CacheFolders.migrate(dirs, anotherVolume) shouldBe dirs
        File(roaming, "cache/browser/Default/Cookies").readText() shouldBe "chromium cookies"
        local.exists() shouldBe false
    }

    @Test
    fun `deleting a folder never follows a link out of it`() {
        val outside = File(temp, "outside").apply { mkdirs() }
        File(outside, "keep").writeText("keep")
        val folder = File(temp, "folder").apply { mkdirs() }
        File(folder, "file").writeText("x")
        Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())

        CacheFolders.deleteTree(folder.toPath()) shouldBe false
        File(outside, "keep").readText() shouldBe "keep"
        File(folder, "file").exists() shouldBe false
    }
}
