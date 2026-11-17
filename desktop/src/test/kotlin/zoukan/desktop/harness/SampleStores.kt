package zoukan.desktop.harness

import zoukan.desktop.harness.CompatibilityHarness.Companion.DEFAULT_PACKAGES
import zoukan.desktop.harness.CompatibilityHarness.SampleStore

/**
 * The real stores the harness tests and the extension wiring check run over. They live in the
 * tests only: the app names no store and ships no store URL.
 */
object SampleStores {
    private const val PREFIX = "eu.kanade.tachiyomi.extension."

    val KEIYOUSHI = SampleStore(
        id = "keiyoushi",
        url = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json",
        packages = DEFAULT_PACKAGES + listOf(
            "all.cubari", "all.namicomi", "all.globalcomix", "all.mangatoon", "all.mangataro", "en.mangafox",
            "en.manhuaplus", "en.rizzcomic", "en.thunderscans", "en.drakescans",
        ).map { PREFIX + it },
        minExtensions = 1000,
    )

    /**
     * yuzono's "Cursed" store: six NSFW extensions with their own signing key. Only the tests use
     * it, so its extensions go through the same checks; the app does not add or trust it
     * (desktop/THREAT-MODEL.md), a user adds it as any other store.
     */
    val YUZONO = SampleStore(
        id = "yuzono",
        url = "https://raw.githubusercontent.com/yuzono/cursed-manga-repo/repo/repo.json",
        packages = listOf(
            "all.ehentai",
            "all.hitomi",
            "all.nhentai",
            "all.pururin",
            "en.hentai20",
            "en.hentai2read",
        ).map { PREFIX + it },
        minExtensions = 1,
    )

    /** The stores the wiring check runs over, each on its own. */
    val ALL = listOf(KEIYOUSHI, YUZONO)
}
