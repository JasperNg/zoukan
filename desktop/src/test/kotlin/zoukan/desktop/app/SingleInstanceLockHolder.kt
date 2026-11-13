package zoukan.desktop.app

import java.io.File
import kotlin.system.exitProcess

/**
 * Run in a child JVM by [SingleInstanceTest]: takes the lock on the folder given as the argument
 * like a running Zoukan does, prints "locked", and holds it until its standard input closes.
 */
object SingleInstanceLockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = SingleInstance.acquire(File(args[0])) {}
        if (result !is SingleInstance.Result.Acquired) {
            println("held")
            exitProcess(1)
        }
        println("locked")
        System.out.flush()
        // Blocks until the parent closes our input (or destroys this process)
        runCatching { while (System.`in`.read() != -1) Unit }
        result.instance.close()
    }
}
