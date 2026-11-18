package zoukan.desktop.testing

import java.io.File

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
