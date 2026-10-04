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
    private val backgroundAllowed: () -> Boolean = { true },
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
    private var lastBackgroundGroup: String? = null
    private val fileLocks = Array(64) { Mutex() }

    init {
        require(concurrency > 0 && foregroundWorkers in 0 until concurrency)
        repeat(concurrency) { worker ->
            workerScope.launch {
                for (ignored in wakeups[worker]) {
                    while (true) {
                        val task = queueLock.withLock {
                            foreground.pollFirst() ?: if (worker >= foregroundWorkers && backgroundAllowed()) pollBackground() else null
                        }
                        if (task == null) {
                            val waiting = queueLock.withLock { worker >= foregroundWorkers && background.isNotEmpty() }
                            if (waiting) { kotlinx.coroutines.delay(100); continue }
                            break
                        }
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

    private fun pollBackground(): Task? {
        val groups = background.map { it.key.substringBefore('|') }.distinct()
        if (groups.isEmpty()) return null
        val group = groups[(groups.indexOf(lastBackgroundGroup) + 1) % groups.size]
        val next = background.first { it.key.substringBefore('|') == group }
        background.remove(next)
        lastBackgroundGroup = next.key.substringBefore('|')
        return next
    }

    suspend fun promote(keys: Set<String>) {
        queueLock.withLock {
            val tasks = background.filter { it.key in keys }
            tasks.forEach { background.remove(it); foreground.addLast(it) }
        }
        wakeups.forEach { it.trySend(Unit) }
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
                val handle = owner?.invokeOnCompletion { cause -> if (cause != null) result.cancel() }
                result.invokeOnCompletion { handle?.dispose() }
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
