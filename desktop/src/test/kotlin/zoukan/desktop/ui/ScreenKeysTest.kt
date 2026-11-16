package zoukan.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test

/**
 * Voyager disposes every screen model whose key starts with the key of a screen that left. So no
 * screen's key may start with another's, or leaving one screen cancels the work of another that
 * is still shown (leaving manga 1 stopped manga 12's screen).
 */
class ScreenKeysTest {

    @Test
    fun `every screen class has prefix-free keys`() {
        val variants = ScreenScan.variants()
        val classes = ScreenScan.screenClasses().filter { it != HomeScreen::class.java }
        // Every screen that takes arguments has variants here; a new one fails and is named
        classes.filter { ScreenScan.maker(it) == null }.filter { it !in variants }.map { it.name }.shouldBeEmpty()
        val screens: List<Screen> = classes.flatMap { type ->
            variants[type]?.map { it() } ?: listOfNotNull(ScreenScan.maker(type)?.invoke())
        }
        val clashes = screens.flatMap { a ->
            screens.filter { b -> a !== b && a.key != b.key && b.key.startsWith(a.key) }.map { b -> "${a.key} -> ${b.key}" }
        }
        clashes.shouldBeEmpty()
    }

    /**
     * A key that ends with a terminator ("/", or ")" where the argument is closed in brackets) can't
     * be the start of another screen's key that names a longer ID. Object screens are left out: each
     * has one fixed key, which the prefix-free test checks against every other key.
     */
    @Test
    fun `every screen key with arguments ends with a terminator`() {
        val open = ScreenScan.variants().values.flatten().map { it() }
            .filterNot { it.key.endsWith("/") || it.key.endsWith(")") }
            .map { "${it::class.simpleName}: ${it.key}" }
        open.shouldBeEmpty()
    }
}
