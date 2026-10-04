package app.gyrolet.mpvrx.domain.cloud

import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Directory batches are independent of composition; visible requests overtake queued tree work. */
internal class CloudWorkQueue(concurrency: Int = 1, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO), private val backgroundAllowed: () -> Boolean = { true }) {
  private class Task(val key: String, val owner: Job?, val active: () -> Boolean, val execute: suspend () -> Unit, val cancel: () -> Unit)
  private val lock = Mutex()
  private val foreground = ArrayDeque<Task>()
  private val background = ArrayDeque<Task>()
  private val wakeups = List(concurrency) { Channel<Unit>(Channel.CONFLATED) }

  init {
    require(concurrency > 0)
    repeat(concurrency) { worker ->
      scope.launch {
        for (ignored in wakeups[worker]) {
          while (true) {
            val task = lock.withLock { foreground.pollFirst() ?: if (backgroundAllowed()) background.pollFirst() else null }
            if (task == null) {
              val waiting = lock.withLock { background.isNotEmpty() }
              if (waiting) { delay(100); continue }
              break
            }
            if (!task.active() || task.owner?.isActive == false) { task.cancel(); continue }
            val running = CoroutineScope(coroutineContext + (task.owner ?: SupervisorJob())).launch { task.execute() }
            running.join()
            if (running.isCancelled) task.cancel()
          }
        }
      }
    }
  }

  suspend fun promote(key: String) = lock.withLock {
    val task = background.firstOrNull { it.key == key } ?: foreground.firstOrNull { it.key == key }
    if (task != null) {
      background.remove(task)
      foreground.remove(task)
      foreground.addFirst(task)
    }
  }

  suspend fun <T> run(key: String, backgroundWork: Boolean, action: suspend () -> T): T {
    val owner = coroutineContext[Job]
    val result = CompletableDeferred<T>()
    val handle = owner?.invokeOnCompletion { cause -> if (cause != null) result.cancel() }
    val task = Task(key, owner, { result.isActive }, {
      try { result.complete(action()) }
      catch (cancelled: CancellationException) { result.cancel(cancelled); throw cancelled }
      catch (error: Exception) { result.completeExceptionally(error) }
    }, { result.cancel() })
    lock.withLock { if (backgroundWork) background.addLast(task) else foreground.addLast(task) }
    wakeups.forEach { it.trySend(Unit) }
    try { return result.await() }
    finally {
      handle?.dispose()
      if (!result.isCompleted) result.cancel()
      withContext(NonCancellable) { lock.withLock { foreground.remove(task); background.remove(task) } }
    }
  }
}
