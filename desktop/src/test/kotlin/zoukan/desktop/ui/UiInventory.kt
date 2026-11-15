package zoukan.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import java.io.File
import java.lang.reflect.Modifier

/** What the app's UI is made of, found from its compiled classes: the smoke test and the coverage matrix share it. */
object UiInventory {
    private const val UI_PACKAGE = "zoukan.desktop.ui."

    /** The enums whose constants are matrix keys, by key prefix. */
    private val ENUMS = listOf(
        "tab" to "zoukan.desktop.ui.HomeTab",
        "browse" to "zoukan.desktop.ui.browse.BrowsePage",
        "settings" to "zoukan.desktop.ui.settings.SettingsCategory",
    )

    private val loader: ClassLoader get() = UiInventory::class.java.classLoader

    /** Every concrete Voyager Screen class in the app (the scan moved from ScreenSmokeTest.screenClasses). */
    @Suppress("UNCHECKED_CAST")
    fun screenClasses(): List<Class<out Screen>> {
        val root = File(HomeScreen::class.java.protectionDomain.codeSource.location.toURI())
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { Screen::class.java.isAssignableFrom(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
            .filter { !it.isAnonymousClass && !it.isLocalClass }
            .map { it as Class<out Screen> }
            .sortedBy { it.name }
            .toList()
    }

    /** The matrix key of a screen class: "screen:" + its name under zoukan.desktop.ui, e.g. screen:browse.SourceScreen. */
    fun screenKey(type: Class<*>): String = "screen:" + type.name.removePrefix(UI_PACKAGE)

    /** tab:<HomeTab>, browse:<BrowsePage>, settings:<SettingsCategory>, read without initialising the enums. */
    fun enumKeys(): List<String> = ENUMS.flatMap { (prefix, name) ->
        // HomeTab.entries would run the constructors, which load strings and icons
        Class.forName(name, false, loader).declaredFields
            .filter { it.isEnumConstant }
            .map { "$prefix:${it.name}" }
    }
}
