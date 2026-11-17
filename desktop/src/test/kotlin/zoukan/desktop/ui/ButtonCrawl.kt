package zoukan.desktop.ui

import java.io.File

/**
 * The checked-in files of [ButtonCrawlTest]: the inventory of every button the crawl found
 * (button-inventory.txt) and the buttons allowed to show no effect (button-crawl-allowlist.txt).
 * A button's key is `<check key>/<node id>`, and a menu item's `<check key>/<button> > <item>`.
 */
internal object ButtonCrawl {
    const val INVENTORY = "button-inventory.txt"
    const val ALLOWLIST = "button-crawl-allowlist.txt"

    /** Where the files are in the source tree, from the desktop project folder (the tests' working folder). */
    val sources = File("src/test/resources")

    /**
     * A button key that stays the same from run to run: digits become `#`, since counts, sizes,
     * dates and times in a row's label change with the day and the machine, not with the app.
     * Month and day names, a.m. and p.m., and the words of a relative date go too.
     */
    fun stable(id: String): String = id
        .replace(DATE_WORDS, "#")
        .replace(Regex("""\d+"""), "#")

    private val DATE_WORDS = Regex(
        """(?i)\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec|january|february|march|april|june|july|august|september|october|november|december|""" +
            """mon|tue|wed|thu|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday|am|pm|today|yesterday|tomorrow|now|""" +
            """seconds?|minutes?|hours?|days?|weeks?|months?|years?) ?(ago)?\b""",
    )

    /** How deep the crawl goes into menus: a button's menu, and a submenu of one of its items, but not a menu inside a submenu. */
    const val MAX_MENU_DEPTH = 2

    /**
     * Whether a click opened a menu, from the clickable items of each menu over the window before
     * and after it (in root order). A new popup is a menu; so is a menu whose items were all
     * replaced: a submenu shown in place of its menu (the library entry's "Download"), or a new
     * popup in place of the one that closed. An item that only changes its own state (a checkbox
     * in a menu) keeps the menu's items, so it opens nothing.
     */
    fun openedMenu(before: List<Set<String>>, after: List<Set<String>>): Boolean {
        if (after.size > before.size) return true
        if (after.isEmpty() || after.size != before.size) return false
        val now = after.last()
        val was = before.last()
        return now.isNotEmpty() && was.isNotEmpty() && now.none { it in was }
    }

    /** The lines of [text] that are not blank and not `#` comments, trimmed. */
    private fun entries(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** The inventory: each button key with its effect signature, `<button key>  <signature>`. */
    fun parseInventory(text: String): Map<String, String> = entries(text).associate { line ->
        val split = line.indexOf("  ")
        require(split > 0) { "$INVENTORY: expected `<button key>  <signature>`: $line" }
        line.substring(0, split).trim() to line.substring(split + 2).trim()
    }

    /** The inventory file's text: a header, then each button and its signature, sorted. */
    fun formatInventory(signatures: Map<String, String>): String = buildString {
        appendLine("# Every button the button crawl finds and what clicking it does: <button key>  <effect signature>.")
        appendLine("# See desktop/TESTING.md, 'The button crawl'. The crawl fails when a button appears, goes or does")
        appendLine("# something else. After a change on purpose, regenerate it:")
        appendLine("#   ./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.updateBaseline=true")
        signatures.toSortedMap().forEach { (key, signature) -> appendLine("$key  $signature") }
    }

    /**
     * The allowlist: `<button key>  # <reason>`, two spaces before the `#`. A line with no reason is
     * an error, so that no button is allowed to do nothing without saying why.
     */
    fun parseAllowlist(text: String): Map<String, String> = text.lines().mapIndexedNotNull { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
        val split = line.lastIndexOf("  # ")
        require(split > 0) { "$ALLOWLIST line ${index + 1}: expected `<button key>  # <reason>`: $line" }
        val reason = line.substring(split + 4).trim()
        require(reason.isNotEmpty()) { "$ALLOWLIST line ${index + 1}: an entry needs a reason: $line" }
        line.substring(0, split).trim() to reason
    }.toMap()

    /** Reads a checked-in file from the test resources. */
    fun resource(name: String): String =
        requireNotNull(ButtonCrawl::class.java.getResourceAsStream("/$name")) { "/$name is not on the test classpath" }
            .reader().use { it.readText() }

    /**
     * A shard of [keys] for -Pzoukan.shard=i/n: every n-th key, so that each shard gets some of the
     * long Settings pages. Variants of one check share a key, so they stay in one shard.
     */
    fun shard(keys: List<String>, index: Int, count: Int): Set<String> =
        keys.distinct().filterIndexed { i, _ -> i % count == index - 1 }.toSet()
}
