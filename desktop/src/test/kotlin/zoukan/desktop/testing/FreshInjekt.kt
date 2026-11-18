package zoukan.desktop.testing

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.registry.default.DefaultRegistrar

/**
 * Gives each test an empty Injekt scope of its own and puts the previous scope back after it, also
 * when the test's setup (`@BeforeEach`) or the test throws, so nothing a test registers leaks into
 * the tests that run after it in the same JVM. Register it on a field:
 *
 *     @JvmField @RegisterExtension val injekt = FreshInjekt()
 *
 * and register what the test needs in `@BeforeEach`, which runs after the swap. JUnit runs
 * [afterEach] after every `@AfterEach` method, whatever failed before it.
 */
class FreshInjekt : BeforeEachCallback, AfterEachCallback {
    private var previous: InjektScope? = null

    override fun beforeEach(context: ExtensionContext) {
        previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
    }

    override fun afterEach(context: ExtensionContext) {
        previous?.let { Injekt = it }
        previous = null
    }
}
