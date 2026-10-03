package app.gyrolet.mpvrx.domain.cloud

import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Global bounded workers. Foreground directory work overtakes queued background scans. */
internal class MetadataWorkQueue(
    concurrency: Int,
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val foregroundWorkers: Int = 0,
) {
    private data class Task(
        val key: String,
        val owner: Job?,
        val result: CompletableDeferred<Boolean>,
        val action: suspend () -> Boolean,
    )

    private val queueLock = Mutex()
    private val foreground = ArrayDeque<Task>()
    private val background = ArrayDeque<Task>()
    private val wakeups = List(concurrency) { Channel<Unit>(Channel.CONFLATED) }
    private val fileLocks = Array(64) { Mutex() }

    init {
        require(concurrency > 0 && foregroundWorkers in 0 until concurrency)
        repeat(concurrency) { worker ->
            workerScope.launch {
                for (ignored in wakeups[worker]) {
                    while (true) {
                        val task = queueLock.withLock {
                            foreground.pollFirst() ?: if (worker >= foregroundWorkers) background.pollFirst() else null
                        } ?: break
                        if (!task.result.isActive || task.owner?.isActive == false) continue
                        val lock = fileLocks[(task.key.hashCode() and Int.MAX_VALUE) % fileLocks.size]
                        val taskScope = CoroutineScope(coroutineContext + (task.owner ?: SupervisorJob()))
                        val running = taskScope.launch {
                            runCatching { lock.withLock { task.action() } }
                                .onSuccess(task.result::complete)
                                .onFailure(task.result::completeExceptionally)
                        }
                        running.join()
                        if (running.isCancelled && task.result.isActive) task.result.cancel()
                    }
                }
            }
        }
    }

    suspend fun <T> process(
        items: List<T>,
        key: (T) -> String,
        priority: MetadataRequestPriority,
        action: suspend (T) -> Boolean,
    ): Boolean {
        if (items.isEmpty()) return false
        val owner = coroutineContext[Job]
        val results = items.map { item ->
            CompletableDeferred<Boolean>().also { result ->
                owner?.invokeOnCompletion { cause -> if (cause != null) result.cancel() }
                val task = Task(key(item), owner, result) { action(item) }
                queueLock.withLock {
                    when (priority) {
                        MetadataRequestPriority.FOREGROUND -> foreground.addLast(task)
                        MetadataRequestPriority.BACKGROUND -> background.addLast(task)
                    }
                }
                wakeups.forEach { it.trySend(Unit) }
            }
        }
        return results.awaitAll().any { it }
    }
}

internal enum class MetadataRequestPriority { FOREGROUND, BACKGROUND }
