package zoukan.desktop.testing

import kotlinx.coroutines.test.runTest

/**
 * For a test that lets an error escape a coroutine on purpose (a fatal error going on to the crash
 * report): once any `runTest` has run in the JVM, kotlinx-coroutines-test also keeps every
 * uncaught coroutine error and fails the next `runTest` with it, in another test class. [drain]
 * takes them, so they stay with the test that caused them.
 */
object UncaughtCoroutineErrors {
    /** The uncaught coroutine errors kept since the last `runTest`, now removed. */
    fun drain(): List<Throwable> = try {
        runTest { }
        emptyList()
    } catch (e: IllegalStateException) {
        // UncaughtExceptionsBeforeTest, internal to kotlinx-coroutines-test
        if (e::class.simpleName != "UncaughtExceptionsBeforeTest") throw e
        e.suppressed.toList()
    }
}
