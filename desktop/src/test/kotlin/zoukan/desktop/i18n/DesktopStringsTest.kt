package zoukan.desktop.i18n

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import zoukan.desktop.compat.localized
import zoukan.desktop.backup.restoredMessage
import zoukan.i18n.desktop.DMR
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Checks the desktop string resources for text that breaks on Android or reads wrong for a count of one. */
class DesktopStringsTest {

    private fun strings(): Map<String, String> {
        val file = File("../i18n-desktop/src/commonMain/moko-resources/base/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    @Test
    fun `strings have no bare apostrophe`() {
        val bad = strings().filter { (_, text) -> Regex("""(?<!\\)'""").containsMatchIn(text) }.keys
        bad shouldBe emptySet()
    }

    @Test
    fun `the relative timestamps summary has balanced quotes`() {
        localized(DMR.strings.today_instead_of, "x") shouldBe "\"Today\" instead of \"x\""
    }

    @Test
    fun `a restore with one error reads one error`() {
        restoredMessage(3, 1) shouldBe "Restored 3 entries with 1 error"
        restoredMessage(1, 2) shouldBe "Restored 1 entry with 2 errors"
        restoredMessage(1, 0) shouldBe "Restored 1 entry"
    }

    @Test
    fun `counts are plurals, not strings`() {
        // These show a count that can be one, so each is a plural with a singular form, not a string
        val counted = setOf(
            "entries",
            "chapters",
            "categories",
            "n_1_entry",
            "n_1_chapter",
            "n_1_category",
            "restored_entries",
            "restored_entries_with_errors",
            "backed_up_entries_to",
            "cache_cleared_files_deleted",
        )
        strings().keys.filter { it in counted } shouldBe emptyList()
    }
}
