package zoukan.desktop.compat

import eu.kanade.tachiyomi.util.awaitSingle
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import rx.Observable
import kotlin.coroutines.Continuation

class RxAwaitSingleTest {

    @Test
    fun `awaitSingle returns the single item`() {
        runBlocking { Observable.just(42).awaitSingle() } shouldBe 42
    }

    @Test
    fun `awaitSingle keeps the binary name extensions link against`() {
        val method = Class.forName("eu.kanade.tachiyomi.util.RxExtensionKt")
            .getMethod("awaitSingle", Observable::class.java, Continuation::class.java)
        method.name shouldBe "awaitSingle"
    }
}
