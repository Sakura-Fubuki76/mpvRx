package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CloudStoragePipelineTest {
  @Test fun metadataStartsBeforeDirectoryTreeFinishes() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val finishEnumeration = CompletableDeferred<Unit>()
    val task = async {
      streamStorageMetadata<Int>(enumerate = { submit ->
        submit(listOf(1))
        finishEnumeration.await()
        true
      }, process = { started.complete(Unit) })
    }
    withTimeout(5000) { started.await() }
    assertFalse(task.isCompleted)
    finishEnumeration.complete(Unit)
    assertTrue(task.await())
  }
  @Test fun processesEveryItemInSmallBatchesAndSkipsEmptyDirectories() = runBlocking {
    val sizes = mutableListOf<Int>()
    val completed = mutableListOf<Int>()
    val complete = streamStorageMetadata<Int>(enumerate = { submit ->
      submit(emptyList())
      submit((0 until 2500).toList())
      true
    }, process = { sizes.add(it.size); completed.addAll(it) })
    assertTrue(complete)
    assertTrue(sizes.all { it in 1..8 })
    assertEquals((0 until 2500).toList(), completed)
  }
  @Test fun cancellationStopsMetadataConsumer() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val stopped = CompletableDeferred<Unit>()
    val task = launch {
      streamStorageMetadata<Int>(enumerate = { submit -> submit(listOf(1)); true }, process = {
        try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
      })
    }
    withTimeout(5000) { started.await() }
    task.cancelAndJoin()
    assertTrue(stopped.isCompleted)
  }
}
