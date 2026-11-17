package zoukan.desktop.harness

/**
 * The real stores the extension wiring check and the live tests download from. They live in the
 * tests only: the app names no store and ships no store URL. Each [id] must match a store in
 * `extensionWiringStores` in desktop/build.gradle.kts, which runs one test task per store.
 */
object SampleStores {

    /** A store and the fewest extensions it should list (fewer means it moved or was emptied). */
    data class SampleStore(val id: String, val url: String, val minExtensions: Int)

    val KEIYOUSHI = SampleStore(
        id = "keiyoushi",
        url = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json",
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
        minExtensions = 1,
    )

    val ALL = listOf(KEIYOUSHI, YUZONO)
}
