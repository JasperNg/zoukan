package zoukan.desktop.ui

import android.os.Looper
import android.os.Message
import android.os.SystemClock
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.StateScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** What an end-to-end test prints when it times out, to see where a stalled step waits. */
object E2EDiagnostics {

    @Suppress("UNCHECKED_CAST")
    fun dump() {
        println("E2E: Dispatchers.Main is ${runCatching { "${Dispatchers.Main} (${Dispatchers.Main.javaClass.name}), immediate ${Dispatchers.Main.immediate}" }.getOrElse { "unavailable: $it" }}")
        val looper = Looper.getMainLooper()
        if (looper == null) {
            println("E2E: no main looper")
        } else {
            println("E2E: main looper queue at uptime ${SystemClock.uptimeMillis()}:")
            runCatching {
                val queue = looper.queue
                synchronized(queue) {
                    var message = field(queue, "mMessages") as Message?
                    if (message == null) println("E2E:   (empty)")
                    while (message != null) {
                        println("E2E:   when=${message.`when`} async=${message.isAsynchronous} target=${message.target} callback=${field(message, "callback")}")
                        message = field(message, "next") as Message?
                    }
                }
            }.onFailure { println("E2E:   reading it failed: $it") }
        }
        runCatching {
            println("E2E: screen models:")
            (ScreenModelStore.javaClass.getMethod("getScreenModels").invoke(ScreenModelStore) as Map<String, Any>).forEach { (key, model) ->
                val state = (model as? StateScreenModel<*>)?.state?.value
                val jobs = model.javaClass.declaredFields.filter { Job::class.java.isAssignableFrom(it.type) }
                    .joinToString { f -> "${f.name}=" + (f.apply { isAccessible = true }.get(model) as Job?)?.let(::jobState) }
                println("E2E:   $key -> $state ${if (jobs.isEmpty()) "" else "[$jobs]"}")
            }
            println("E2E: screen model dependencies:")
            (ScreenModelStore.javaClass.getMethod("getDependencies").invoke(ScreenModelStore) as Map<String, Pair<Any, *>>).forEach { (key, value) ->
                val job = (value.first as? CoroutineScope)?.coroutineContext?.get(Job)
                println("E2E:   $key -> ${job?.let(::jobState) ?: value.first}")
            }
        }.onFailure { println("E2E:   reading the screen models failed: $it") }
        runCatching {
            androidx.compose.runtime.Recomposer.runningRecomposers.value.forEach { info ->
                println("E2E: recomposer state=${runBlocking { info.state.first() }} changeCount=${info.changeCount}")
            }
        }.onFailure { println("E2E:   reading the recomposers failed: $it") }
        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
            println("E2E: thread \"${thread.name}\" ${thread.state}:\n    " + stack.take(30).joinToString("\n    "))
        }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun jobState(job: Job): String = when {
        job.isCancelled -> "cancelled"
        job.isCompleted -> "completed"
        job.isActive -> "active (${job.children.count()} children)"
        else -> "new"
    }
}
