package zoukan.desktop.ui.reader

import tachiyomi.domain.manga.model.Manga

/**
 * SY's auto webtoon mode: an entry tagged or sourced as webtoon, manhwa or manhua reads as a
 * webtoon. The rules of the Android app's exh/util/MangaType.kt, which needs an Android Context.
 */
object AutoWebtoon {
    fun isWebtoon(manga: Manga, sourceName: String?): Boolean = isWebtoon(manga.genre.orEmpty(), sourceName)

    fun isWebtoon(tags: List<String>, sourceName: String?): Boolean {
        fun anyTag(vararg words: String) = tags.any { tag -> words.any { tag.contains(it, ignoreCase = true) } }
        fun sourceIs(vararg words: String) = sourceName != null && words.any { sourceName.contains(it, ignoreCase = true) }
        return when {
            anyTag("manga", "манга") -> false
            anyTag("long strip", "webtoon") || sourceIs(*WEBTOON_SOURCES) -> true
            anyTag("comic", "комикс") || sourceIs(*COMIC_SOURCES) -> false
            anyTag("manhua", "маньхуа") || sourceIs(*MANHUA_SOURCES) -> true
            anyTag("manhwa", "манхва") || sourceIs(*MANHWA_SOURCES) -> true
            else -> false
        }
    }

    private val MANHWA_SOURCES = arrayOf(
        "hiperdex", "hmanhwa", "instamanhwa", "manhwa18", "manhwa68", "manhwa365", "manhwahentaime",
        "manhwamanga", "manhwatop", "manhwa club", "manytoon", "manwha", "readmanhwa", "skymanga", "toonily",
        "webtoonxyz",
    )

    private val WEBTOON_SOURCES = arrayOf("mangatoon", "manmanga", "toomics", "webcomics", "webtoons", "webtoon")

    private val COMIC_SOURCES = arrayOf(
        "8muses", "allporncomic", "ciayo comics", "comicextra", "comicpunch", "cyanide", "dilbert", "eggporncomics",
        "existential comics", "hiveworks comics", "milftoon", "myhentaicomics", "myhentaigallery", "gunnerkrigg",
        "oglaf", "patch friday", "porncomix", "questionable content", "readcomiconline", "read comics online",
        "swords comic", "teabeer comics", "xkcd",
    )

    private val MANHUA_SOURCES = arrayOf(
        "1st kiss manhua", "hero manhua", "manhuabox", "manhuaus", "manhuas world", "manhuas.net", "readmanhua",
        "wuxiaworld", "manhua",
    )
}
