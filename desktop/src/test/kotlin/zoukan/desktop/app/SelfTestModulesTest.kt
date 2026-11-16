package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SelfTestModulesTest {
    @Test
    fun `the modules the self-test checks are in the runtime and not marked for removal`() {
        val problems = SelfTest.checkedModules.mapNotNull { name ->
            val module = ModuleLayer.boot().findModule(name).orElse(null)
                ?: return@mapNotNull "$name is not in this runtime"
            val deprecated = module.getAnnotation(java.lang.Deprecated::class.java)
            if (deprecated?.forRemoval == true) "$name is deprecated for removal" else null
        }
        problems shouldBe emptyList()
    }
}
