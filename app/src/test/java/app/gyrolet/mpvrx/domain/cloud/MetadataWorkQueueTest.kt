package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MetadataWorkQueueTest {
    private lateinit var backgroundScope: CoroutineScope
    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { withTimeout(15000) { block() } } finally { backgroundScope.cancel() }
    }
    private suspend fun runCurrent() { delay(100) }

    @Test
    fun slowFileDoesNotBlockAnotherDirectoryAndCancelledWorkCanRetry() = runTest {
        val queue = MetadataWorkQueue(2, backgroundScope)
        val blocked = CompletableDeferred<Unit>()
        val slow = async {
            queue.process(listOf("slow"), { it }, MetadataRequestPriority.BACKGROUND) {
                blocked.await()
                true
            }
        }
        runCurrent()
        val refreshed = async { queue.process(listOf("new"), { it }, MetadataRequestPriority.FOREGROUND) { true } }
        runCurrent()
        assertTrue(refreshed.isCompleted)
        assertTrue(refreshed.await())
        slow.cancelAndJoin()
        assertTrue(queue.process(listOf("slow"), { it }, MetadataRequestPriority.FOREGROUND) { true })
    }

    @Test
    fun overlappingRequestsSerializeSameFileAndProcessEveryItem() = runTest {
        val queue = MetadataWorkQueue(3, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var entered = 0
        val first = async {
            queue.process(listOf("same"), { it }, MetadataRequestPriority.BACKGROUND) {
                entered++
                gate.await()
                true
            }
        }
        runCurrent()
        val second = async {
            queue.process(listOf("same"), { it }, MetadataRequestPriority.FOREGROUND) {
                entered++
                true
            }
        }
        runCurrent()
        assertEquals(1, entered)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, entered)
        val completed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        queue.process((0 until 2500).toList(), { it.toString() }, MetadataRequestPriority.FOREGROUND) { completed.add(it) }
        assertEquals(2500, completed.size)
    }

    @Test
    fun foregroundOvertakesQueuedBackgroundWork() = runTest {
        val queue = MetadataWorkQueue(1, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val oldBatch = async {
            queue.process(listOf("running", "old-1", "old-2"), { it }, MetadataRequestPriority.BACKGROUND) {
                if (it == "running") gate.await()
                order += it
                true
            }
        }
        runCurrent()
        val currentPage = async {
            queue.process(listOf("visible"), { it }, MetadataRequestPriority.FOREGROUND) {
                order += it
                true
            }
        }
        runCurrent()
        gate.complete(Unit)
        oldBatch.await()
        currentPage.await()
        assertEquals(listOf("running", "visible", "old-1", "old-2"), order)
    }
    @Test
    fun reservedWorkerStartsForegroundWhileBackgroundIsBlocked() = runTest {
        val queue = MetadataWorkQueue(2, backgroundScope, foregroundWorkers = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val background = async {
            queue.process(listOf("background", "waiting"), { it }, MetadataRequestPriority.BACKGROUND) {
                started.complete(Unit)
                release.await()
                true
            }
        }
        started.await()
        assertTrue(withTimeout(2000) {
            queue.process(listOf("foreground"), { it }, MetadataRequestPriority.FOREGROUND) { true }
        })
        assertTrue(!background.isCompleted)
        release.complete(Unit)
        background.await()
    }
}
