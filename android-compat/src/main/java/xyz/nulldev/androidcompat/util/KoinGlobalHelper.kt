package xyz.nulldev.androidcompat.util

// ZOUKAN -->
// Zoukan uses Injekt (like Komikku and the extensions) instead of Suwayomi's Koin, so this helper
// resolves from Injekt. The name is kept so the Java callers stay unchanged.
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

/**
 * Helper class to allow access to the dependency container from Java
 */
object KoinGlobalHelper {
    /**
     * Get the dependency container
     */
    @JvmStatic
    fun koin(): InjektScope = Injekt

    /**
     * Get a dependency
     */
    @JvmStatic
    fun <T : Any> instance(
        type: Class<T>,
        koin: InjektScope? = null,
    ): T = (koin ?: koin()).getInstance(type)

    @JvmStatic
    fun <T : Any> instance(type: Class<T>): T = instance(type, null)
}
// ZOUKAN <--
