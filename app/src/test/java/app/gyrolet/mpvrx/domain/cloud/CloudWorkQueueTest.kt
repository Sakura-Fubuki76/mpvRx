package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CloudWorkQueueTest {
  @Test fun backgroundResumesAfterPlaybackWithoutAnotherRequest() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val allowed = java.util.concurrent.atomic.AtomicBoolean(false)
    try {
      val queue = CloudWorkQueue(2, scope, backgroundAllowed = { allowed.get() })
      val background = async { queue.run("background", true) { "done" } }
      delay(150)
      assertFalse(background.isCompleted)
      assertEquals("visible", withTimeout(2000) { queue.run("visible", false) { "visible" } })
      allowed.set(true)
      assertEquals("done", withTimeout(2000) { background.await() })
    } finally { scope.cancel() }
  }
  @Test fun visibleWorkOvertakesQueuedDirectoryWork() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val queue = CloudWorkQueue(1, scope)
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val order = mutableListOf<String>()
      val running = async { queue.run("running", true) { started.complete(Unit); release.await(); order.add("running") } }
      started.await()
      val background = async { queue.run("background", true) { order.add("background") } }
      yield()
      val visible = async { queue.run("visible", false) { order.add("visible") } }
      yield()
      release.complete(Unit)
      awaitAll(running, background, visible)
      assertEquals(listOf("running", "visible", "background"), order)
    } finally { scope.cancel() }
  }

  @Test fun cancelledDirectoryDoesNotBlockNewRequests() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val queue = CloudWorkQueue(1, scope)
      val started = CompletableDeferred<Unit>()
      val old = async { queue.run("old", true) { started.complete(Unit); awaitCancellation() } }
      started.await()
      old.cancelAndJoin()
      assertEquals("fresh", withTimeout(5_000) { queue.run("fresh", false) { "fresh" } })
    } finally { scope.cancel() }
  }

  @Test fun allOffscreenItemsAreProcessedWithBoundedConcurrency() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val queue = CloudWorkQueue(2, scope)
      val running = java.util.concurrent.atomic.AtomicInteger()
      val maximum = java.util.concurrent.atomic.AtomicInteger()
      val completed = java.util.concurrent.atomic.AtomicInteger()
      (0 until 2500).map { index -> async {
        queue.run(index.toString(), true) {
          val active = running.incrementAndGet()
          maximum.updateAndGet { maxOf(it, active) }
          yield()
          running.decrementAndGet()
          completed.incrementAndGet()
        }
      } }.awaitAll()
      assertEquals(2500, completed.get())
      assertTrue(maximum.get() <= 2)
    } finally { scope.cancel() }
  }
}
