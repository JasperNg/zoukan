package zoukan.desktop

import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test

class MainDispatcherTest {
    @Test
    fun `Dispatchers Main is the Swing dispatcher`() {
        // Reading the class name does not dispatch, so it cannot hang on a looper that is not running
        Dispatchers.Main.immediate.javaClass.name shouldContain "Swing"
    }
}
