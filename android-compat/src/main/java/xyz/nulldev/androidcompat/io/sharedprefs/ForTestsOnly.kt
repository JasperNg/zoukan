// ZOUKAN -->
package xyz.nulldev.androidcompat.io.sharedprefs

/**
 * Marks a hook for tests: calling it from the app or an extension could block the calling thread,
 * the UI thread included. Tests opt in with `@OptIn(ForTestsOnly::class)`.
 */
@RequiresOptIn(
    message = "For tests only: it can block the calling thread while background writers finish",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class ForTestsOnly
// ZOUKAN <--
