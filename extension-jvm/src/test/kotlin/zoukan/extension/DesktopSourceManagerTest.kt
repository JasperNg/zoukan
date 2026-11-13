package zoukan.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import java.util.concurrent.TimeUnit

/** getOrStub is called from the UI, so it must never wait for the database. */
class DesktopSourceManagerTest {

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `getOrStub answers at once with a placeholder, then the stub once it is read`() {
        val stubs = MutableStateFlow(emptyList<StubSource>())
        val repository = mockk<StubSourceRepository> {
            every { subscribeAll() } returns stubs
            // The database thread is busy (a restore): a lookup would never return
            coEvery { getStubSource(any()) } coAnswers { awaitCancellation() }
        }
        val extensions = mockk<DesktopExtensionManager> {
            every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
            every { isInitialized } returns MutableStateFlow(true)
        }
        val manager = DesktopSourceManager(extensions, repository)

        manager.getOrStub(42).shouldBeInstanceOf<StubSource>().name shouldBe ""

        stubs.value = listOf(StubSource(42, "en", "Gone source"))
        runBlocking {
            withTimeout(5_000) {
                while ((manager.getOrStub(42) as StubSource).name.isEmpty()) kotlinx.coroutines.delay(10)
            }
        }
        manager.getOrStub(42).name shouldBe "Gone source"
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `awaitStubs waits for the first read, so a backup right after start-up has the names`() {
        val gate = CompletableDeferred<Unit>()
        val repository = mockk<StubSourceRepository> {
            every { subscribeAll() } returns flow {
                gate.await()
                emit(listOf(StubSource(42, "en", "Gone source")))
            }
        }
        val extensions = mockk<DesktopExtensionManager> {
            every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
            every { isInitialized } returns MutableStateFlow(true)
        }
        val manager = DesktopSourceManager(extensions, repository)

        runBlocking {
            val waiting = async { manager.awaitStubs() }
            delay(100)
            waiting.isCompleted shouldBe false
            gate.complete(Unit)
            withTimeout(5_000) { waiting.await() }
        }
        manager.getOrStub(42).name shouldBe "Gone source"
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `awaitStubs returns when the stubs cannot be read`() {
        val repository = mockk<StubSourceRepository> {
            every { subscribeAll() } returns flow { throw IllegalStateException("database closed") }
        }
        val extensions = mockk<DesktopExtensionManager> {
            every { installedExtensionsFlow } returns MutableStateFlow(emptyMap())
            every { isInitialized } returns MutableStateFlow(true)
        }
        val manager = DesktopSourceManager(extensions, repository)

        runBlocking { withTimeout(5_000) { manager.awaitStubs() } }
        manager.getOrStub(42).name shouldBe ""
    }
}
