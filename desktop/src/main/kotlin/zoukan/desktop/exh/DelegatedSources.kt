package zoukan.desktop.exh

import android.app.Application
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.Lanraragi
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.source.online.all.NHentai
import eu.kanade.tachiyomi.source.online.all.Pururin
import eu.kanade.tachiyomi.source.online.english.EightMuses
import exh.source.DelegatedHttpSource
import exh.source.EHENTAI_EXT_SOURCES
import exh.source.EH_SOURCE_ID
import exh.source.EXH_SOURCE_ID
import exh.source.EnhancedHttpSource
import exh.source.ExhPreferences
import exh.source.LIBRARY_UPDATE_EXCLUDED_SOURCES
import exh.source.PURURIN_SOURCE_ID
import exh.source.lanraragiSourceIds
import exh.source.mangaDexSourceIds
import exh.source.metadataDelegatedSourceIds
import exh.source.nHentaiSourceIds
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * AndroidSourceManager's source delegation: the extensions of MangaDex, NHentai, LANraragi,
 * Pururin and 8Muses run inside the app's own enhanced versions (metadata, MDList, similar titles),
 * and the E-Hentai extension is dropped while the built-in E-Hentai is on.
 */
class DelegatedSources(
    private val context: Application,
    private val exhPreferences: ExhPreferences,
    // ZOUKAN -->
    /** Advanced > Developer tools > Enable delegated sources, read when the sources load (restart to apply) */
    private val delegateSourcePreferences: exh.pref.DelegateSourcePreferences? = null,
    // ZOUKAN <--
) {

    private data class Delegate(val className: String, val newClass: KClass<out DelegatedHttpSource>)

    private val delegates = listOf(
        Delegate("eu.kanade.tachiyomi.extension.all.pururin.Pururin", Pururin::class),
        Delegate("eu.kanade.tachiyomi.extension.all.mangadex.MangaDex", MangaDex::class),
        Delegate("eu.kanade.tachiyomi.extension.en.eightmuses.EightMuses", EightMuses::class),
        Delegate("eu.kanade.tachiyomi.extension.all.nhentai.NHentai", NHentai::class),
        Delegate("eu.kanade.tachiyomi.extension.all.lanraragi.LANraragi", Lanraragi::class),
    )

    /** Source id to the class it is delegated to. */
    private val current = ConcurrentHashMap<Long, KClass<out DelegatedHttpSource>>()

    /** Swaps in the enhanced source, or drops a source that a built-in one replaces. */
    fun transform(source: Source): Source? {
        if (exhPreferences.isHentaiEnabled().get() && source.id in EHENTAI_EXT_SOURCES.keys) return null
        val delegate = delegates.firstOrNull { it.className == source::class.qualifiedName }
        if (source !is HttpSource || delegate == null) return source
        // ZOUKAN -->
        if (delegateSourcePreferences?.delegateSources()?.get() == false) return source
        // ZOUKAN <--
        val enhanced = runCatching {
            delegate.newClass.constructors.first { it.parameters.size == 2 }.call(source, context)
        }.getOrElse { return source }
        current[source.id] = delegate.newClass
        updateIds()
        return EnhancedHttpSource(source, enhanced)
    }

    private fun updateIds() {
        fun idsOf(vararg classes: KClass<out DelegatedHttpSource>) = current.filterValues { it in classes }.keys.sorted()
        metadataDelegatedSourceIds = idsOf(Pururin::class, EightMuses::class, NHentai::class, Lanraragi::class)
        nHentaiSourceIds = idsOf(NHentai::class)
        mangaDexSourceIds = idsOf(MangaDex::class)
        lanraragiSourceIds = idsOf(Lanraragi::class)
        LIBRARY_UPDATE_EXCLUDED_SOURCES = listOf(EH_SOURCE_ID, EXH_SOURCE_ID, PURURIN_SOURCE_ID) + nHentaiSourceIds
    }
}
