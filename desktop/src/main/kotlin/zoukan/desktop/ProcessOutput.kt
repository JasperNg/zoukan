package zoukan.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** What a finished process printed (stdout, with stderr when it was redirected there). */
data class ProcessOutput(val exitCode: Int, val text: String)

/**
 * Waits up to [timeout] for this process and returns its exit code and output, or null after
 * stopping it when it runs longer. The output is read on another thread: reading it to the end
 * first would wait for the process to exit, so a process that hangs would never time out.
 */
fun Process.outputWithin(timeout: Long, unit: TimeUnit): ProcessOutput? {
    runCatching { outputStream.close() }
    val output = CompletableFuture.supplyAsync { inputStream.use { it.readBytes() } }
    if (!waitFor(timeout, unit)) {
        destroyForcibly()
        output.cancel(true)
        return null
    }
    // The process has exited; its output ends once any child holding the pipe lets go
    val bytes = runCatching { output.get(5, TimeUnit.SECONDS) }.getOrElse { ByteArray(0) }
    return ProcessOutput(exitValue(), bytes.decodeToString())
}
