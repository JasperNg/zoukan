package zoukan.desktop.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import zoukan.desktop.app.Toasts
import zoukan.desktop.tray.Notifications
import java.io.File

/**
 * Waits on a condition instead of sleeping for a guessed time. Tests that wait for a state, a
 * file, a thread or a callback use these (or a latch, a `CompletableDeferred`, `StateFlow.first`
 * under `withTimeout`, Compose's `waitUntil`, or virtual time in `runTest`); `ThreadSleepScanTest`
 * refuses a new `Thread.sleep` in test code.
 */
object Await {
    /** How long a wait lasts before it fails, unless the caller gives its own. */
    const val TIMEOUT_MILLIS = 10_000L

    /** How often a condition is checked. */
    const val POLL_MILLIS = 10L

    /**
     * Returns as soon as [condition] holds; fails naming [what] (and [state], when given) after
     * [timeoutMillis]. Blocks the calling thread, so it is for code outside a coroutine.
     */
    fun until(
        what: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        state: () -> Any? = { null },
        condition: () -> Boolean,
    ) {
        check(within(timeoutMillis, condition)) {
            "Timed out after $timeoutMillis ms waiting for $what" + (state()?.let { "; state: $it" } ?: "")
        }
    }

    /**
     * Whether [condition] held within [timeoutMillis]; returns as soon as it does. For a harness
     * that goes on either way instead of failing.
     */
    fun within(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(POLL_MILLIS)
        }
        return true
    }

    /**
     * [until] for a coroutine on a real dispatcher: suspends instead of blocking. Under `runTest`
     * the timeout and the polling run in virtual time, so use a latch or `advanceUntilIdle` there.
     */
    suspend fun untilSuspending(
        what: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        condition: suspend () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMillis) { while (!condition()) delay(POLL_MILLIS) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("Timed out after $timeoutMillis ms waiting for $what", e)
        }
    }
}

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

/**
 * The home folder and the system properties a test may use. The build gives every test JVM a home
 * folder and a data folder of their own under `desktop/build/test-home/<task>` (`-Duser.home`,
 * `-Dzoukan.dataDir`, and `-Dzoukan.test.home`, which marks that the home folder is the build's).
 * Test code reads the home folder only through [root] (`HomeFolderScanTest` checks this), so a
 * test run from an IDE without those properties still never writes to the developer's real home.
 */
object TestHome {
    /** Set by the build to the same folder as `user.home`. */
    const val MARKER = "zoukan.test.home"

    /**
     * The home folder of this test JVM: `user.home` when the build set it, else a folder of its own
     * in the temporary folder (never the real home).
     */
    fun root(): File {
        val marked = System.getProperty(MARKER)
        val home = System.getProperty("user.home")
        val dir = if (!marked.isNullOrBlank() && home != null && File(marked).absoluteFile == File(home).absoluteFile) {
            File(home)
        } else {
            File(System.getProperty("java.io.tmpdir"), "zoukan-test-home")
        }
        dir.mkdirs()
        return dir.absoluteFile
    }

    /** Whether `user.home` is the folder the build set, not the real home. */
    fun isSandboxed(): Boolean {
        val marked = System.getProperty(MARKER) ?: return false
        return File(marked).absoluteFile == File(System.getProperty("user.home").orEmpty()).absoluteFile
    }

    /**
     * The `-D` arguments that give a child JVM this JVM's test home ([root], marked as the build's)
     * and, when [dataDir] is true, its data folder (`zoukan.dataDir`, or a folder in [root] when the
     * build did not set one). A test that starts a JVM (`java`, the app's `MainKt`) passes these, so
     * the child does not fall back to the real home or profile. A test whose child must use a data
     * folder of its own passes `dataDir = false` and sets its own.
     */
    fun childJvmArguments(dataDir: Boolean = true): List<String> {
        val home = root()
        val data = System.getProperty("zoukan.dataDir")?.takeIf { it.isNotBlank() } ?: File(home, "zoukan-data").path
        return listOf("-Duser.home=${home.path}", "-D$MARKER=${home.path}") +
            if (dataDir) listOf("-Dzoukan.dataDir=$data") else emptyList()
    }

    /** A `java` command line [argv] (the java binary first) with [childJvmArguments] put after the binary. */
    fun javaCommand(argv: List<String>, dataDir: Boolean = true): List<String> =
        listOf(argv.first()) + childJvmArguments(dataDir) + argv.drop(1)

    /** [childJvmArguments] for a JDK tool (keytool, jarsigner, jcmd), which takes JVM options as `-J<option>`. */
    fun jdkToolArguments(dataDir: Boolean = false): List<String> = childJvmArguments(dataDir).map { "-J$it" }

    /**
     * Runs [block] with the system property [key] set to [value] (cleared when null), then puts back
     * the value it had, so a value the build set for every test (such as `zoukan.dataDir`) is not
     * lost for the tests after it.
     */
    fun <T> withSystemProperty(key: String, value: String?, block: () -> T): T {
        val previous = System.getProperty(key)
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        return try {
            block()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}

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

/**
 * Records what the user is shown while it is open: toasts ([Toasts]) and notifications
 * ([Notifications]). Both are shared flows without replay, so it starts listening when made, before
 * the code under test runs. A fault-injection test uses it to check that a failure reaches the user,
 * not only the log.
 */
class UserMessages : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val toastList = MutableStateFlow<List<String>>(emptyList())
    private val notificationList = MutableStateFlow<List<Notifications.Message>>(emptyList())

    init {
        // Undispatched: subscribed before the constructor returns
        scope.launch(start = CoroutineStart.UNDISPATCHED) { Toasts.flow.collect { t -> toastList.update { it + t } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { Notifications.flow.collect { n -> notificationList.update { it + n } } }
    }

    val toasts: List<String> get() = toastList.value
    val notifications: List<Notifications.Message> get() = notificationList.value

    /** The error notifications so far. */
    val errors: List<Notifications.Message> get() = notifications.filter { it.kind == Notifications.Kind.ERROR }

    /** Waits until a toast [matches], and returns it. */
    fun awaitToast(timeoutMillis: Long = 10_000, matches: (String) -> Boolean): String = runBlocking {
        withTimeout(timeoutMillis) { toastList.first { list -> list.any(matches) }.first(matches) }
    }

    /** Waits until an error notification [matches], and returns it. */
    fun awaitError(timeoutMillis: Long = 10_000, matches: (Notifications.Message) -> Boolean = { true }): Notifications.Message = runBlocking {
        val wanted = { m: Notifications.Message -> m.kind == Notifications.Kind.ERROR && matches(m) }
        withTimeout(timeoutMillis) { notificationList.first { list -> list.any(wanted) }.first(wanted) }
    }

    override fun close() {
        scope.cancel()
    }
}
