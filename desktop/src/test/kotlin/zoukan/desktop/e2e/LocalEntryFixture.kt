package zoukan.desktop.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/** What the "Clear manga" journeys share: the user's own files in the local folder, and the dialog. */
object LocalEntryFixture {

    /** A file's SHA-256 and size: what must be the same after the dialog. */
    fun fingerprint(file: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) } to file.length()
    }

    private fun cbz(file: File, label: String) {
        CbzWriter(file).use { writer ->
            (1..3).forEach { i ->
                val image = BufferedImage(300, 450, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.color = java.awt.Color(40 * i, 90, 160)
                g.fillRect(0, 0, 300, 450)
                g.color = java.awt.Color.WHITE
                g.drawString("$label $i", 100, 220)
                g.dispose()
                writer.write("%03d.png".format(i), ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
            }
        }
    }

    /** A local entry with two CBZs in the local folder, added to the library. */
    fun addLocalEntry(): Pair<Manga, List<File>> {
        val dir = File(Injekt.get<ZoukanStorage>().local, "Local Title").apply { mkdirs() }
        val files = (1..2).map { File(dir, "Chapter $it.cbz").also { f -> cbz(f, "Local Title $it") } }
        val manga = runBlocking {
            Injekt.get<SourceManager>().isInitialized.first { it }
            val source = Injekt.get<SourceManager>().get(LocalSource.ID) as CatalogueSource
            val found = source.getPopularManga(1).mangas.single { it.title == "Local Title" }
            val local = Injekt.get<NetworkToLocalManga>()(listOf(found.toDomainManga(LocalSource.ID))).single()
            Injekt.get<MangaRepository>().update(MangaUpdate(id = local.id, favorite = true, dateAdded = System.currentTimeMillis()))
            Injekt.get<MangaRepository>().getMangaById(local.id)
        }
        return manga to files
    }

    /** More > Clear manga on the entry's screen. */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.openClearManga() {
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Clear manga").performClick()
        waitForIdle()
    }
}
