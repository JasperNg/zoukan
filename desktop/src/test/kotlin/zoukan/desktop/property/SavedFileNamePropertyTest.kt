package zoukan.desktop.property

import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.androidcompat.util.SafePath
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.ui.reader.PageImages
import java.io.File

/**
 * The two other name builders besides the downloads': saved pages and covers and the per-entry
 * Pictures folder ([PageImages.validFilename]), and the settings files ([SafePath.buildValidFilename]).
 * Every name they give is one name on Windows, macOS and Linux: no Windows device name, no half
 * surrogate pair (also not one made by the cut at the length limit). A settings file written under
 * the name an older version gave is still found, never renamed or lost.
 */
class SavedFileNamePropertyTest {

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null

    @BeforeEach
    fun setRoot() {
        previousRoot = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
    }

    @AfterEach
    fun restoreRoot() {
        previousRoot?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    private val strings = arbFrom { NastyStrings.next(it) }

    /** Problems a builder that keeps to 240 bytes may not have. */
    private fun problems(name: String) = PortableName.problems(name)

    @Test
    fun `saved picture names are valid on every OS`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            for (limit in listOf(240, 235, 21)) {
                val name = PageImages.validFilename(title, limit)
                withClue(title, name) { problems(name) shouldBe emptySet() }
                (name.toByteArray(Charsets.UTF_8).size <= limit) shouldBe true
                PageImages.validFilename(title, limit) shouldBe name
            }
        }
    }

    @Test
    fun `settings file names are valid on every OS`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { key ->
            val name = SafePath.buildValidFilename(key)
            withClue(key, name) { problems(name) shouldBe emptySet() }
            SafePath.buildValidFilename(key) shouldBe name
        }
    }

    @Test
    fun `a name that is already valid is kept as it is`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { key ->
            val untouched = key.isNotEmpty() &&
                key.trim('.', ' ') == key &&
                key.none { it.code < 0x20 || it.code == 0x7f || it in "<>:\"/\\|?*" } &&
                key.toByteArray(Charsets.UTF_8).size <= 240 &&
                !PortableName.hasLoneSurrogate(key) &&
                !PortableName.isWindowsDeviceName(key)
            if (untouched) SafePath.buildValidFilename(key) shouldBe key
            // Saved pictures also lose other white space at the ends, as before
            if (untouched && key.trim() == key) PageImages.validFilename(key) shouldBe key
        }
    }

    @Test
    fun `the smallest failing inputs found`() {
        // A Windows device name, also with an extension
        PageImages.validFilename("NUL") shouldBe "NUL_"
        PageImages.validFilename("con.page 1") shouldBe "con_.page 1"
        SafePath.buildValidFilename("nul") shouldBe "nul_"
        SafePath.buildValidFilename("COM1.prefs") shouldBe "COM1_.prefs"
        // Half a surrogate pair, in the name and made by the cut at the limit
        PageImages.validFilename("a\udbffb") shouldBe "a_b"
        SafePath.buildValidFilename("a\udbffb") shouldBe "a_b"
        // Counted a char at a time, the cut kept "a", two pairs and half of a third
        val cut = PageImages.validFilename("a" + "😀".repeat(10), 6)
        PortableName.hasLoneSurrogate(cut) shouldBe false
        cut shouldBe "a😀"
    }

    @Test
    fun `a settings file written under its old name is still found`() {
        val settings = File(root, "settings").apply { mkdirs() }
        // As an older version named it: a Windows device name, which only another OS could write
        val old = File(settings, "nul.xml")
        assumeTrue(runCatching { old.writeText("old") }.isSuccess && old.isFile, "this OS cannot write a device name")

        JavaSharedPreferences.fileFor("nul").readText() shouldBe "old"
        // Nothing is renamed
        old.readText() shouldBe "old"
    }

    @Test
    fun `a save an older version left half done under the old name is recovered`() {
        val settings = File(root, "settings").apply { mkdirs() }
        // The file deleted and the new one still in .tmp, under the name an older version gave
        val leftover = File(settings, "nul.xml.tmp")
        assumeTrue(
            runCatching { leftover.outputStream().use { java.util.Properties().apply { setProperty("kept", "yes") }.storeToXML(it, null) } }.isSuccess &&
                leftover.isFile,
            "this OS cannot write a device name",
        )

        JavaSharedPreferences.fileFor("nul").name shouldBe "nul.xml"
        JavaSharedPreferences("nul").getString("kept", null) shouldBe "yes"
    }

    @Test
    fun `a settings key with half a surrogate pair has a file, and one written under its old name is still found`() {
        val settings = File(root, "settings").apply { mkdirs() }
        // Where the old name can be spelled (Windows), an older version wrote the file under it;
        // elsewhere the old name could not be made into a path at all
        val old = runCatching { settings.toPath().resolve("a\udbffb.xml") }.getOrNull()
        if (old != null) java.nio.file.Files.writeString(old, "old")

        val file = JavaSharedPreferences.fileFor("a\udbffb")

        if (old != null) file.readText() shouldBe "old" else file.name shouldBe "a_b.xml"
        file.toPath()
    }

    @Test
    fun `a new settings file gets the portable name`() {
        JavaSharedPreferences.fileFor("nul").name shouldBe "nul_.xml"
        JavaSharedPreferences.fileFor("a\udbffb").name shouldBe "a_b.xml"
        JavaSharedPreferences.fileFor("source_12").name shouldBe "source_12.xml"
    }

    private inline fun withClue(input: String, name: String, block: () -> Unit) =
        io.kotest.assertions.withClue("\"${escaped(input)}\" gave \"${escaped(name)}\"") { block() }

    private companion object {
        const val ITERATIONS = 3000
    }
}
