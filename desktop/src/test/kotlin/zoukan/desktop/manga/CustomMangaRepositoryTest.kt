package zoukan.desktop.manga

import android.content.Context
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.manga.CustomMangaRepositoryImpl
import tachiyomi.domain.manga.model.CustomMangaInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CustomMangaRepositoryTest {

    @TempDir
    lateinit var dir: File

    private fun context(): Context = mockk { every { getExternalFilesDir(null) } returns dir }

    @Test
    fun `resetting the only custom info survives a restart`() {
        val repo = CustomMangaRepositoryImpl(context())
        repo.set(CustomMangaInfo(id = 1, title = "Custom"))
        repo.set(CustomMangaInfo(1, null))

        CustomMangaRepositoryImpl(context()).get(1).shouldBeNull()
    }

    @Test
    fun `concurrent edits do not throw or get lost`() {
        val repo = CustomMangaRepositoryImpl(context())
        val pool = Executors.newFixedThreadPool(2)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>(null)
        listOf(0L, 500L).forEach { start ->
            pool.execute {
                try {
                    for (i in 1..500) {
                        val id = start + i
                        repo.set(CustomMangaInfo(id = id, title = "T$id"))
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }
        done.await()
        pool.shutdown()

        failure.get().shouldBeNull()
        val reloaded = CustomMangaRepositoryImpl(context())
        for (id in 1L..1000L) reloaded.get(id).shouldNotBeNull()
    }

    @Test
    fun `the file is never left half written`() {
        val repo = CustomMangaRepositoryImpl(context())
        repo.set(CustomMangaInfo(id = 7, title = "Seven"))

        val file = File(dir, "edits.json")
        Json.parseToJsonElement(file.readText()).jsonObject.size shouldBe 1
        File(dir, "edits.json.tmp").exists() shouldBe false
    }
}
