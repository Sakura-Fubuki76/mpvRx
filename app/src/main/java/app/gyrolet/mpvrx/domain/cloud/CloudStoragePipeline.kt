package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Enumerate and process concurrently, with bounded buffering and small fair batches. */
internal suspend fun <T> streamStorageMetadata(
  enumerate: suspend (suspend (List<T>) -> Unit) -> Boolean,
  process: suspend (List<T>) -> Unit,
): Boolean = coroutineScope {
  val batches = Channel<List<T>>(8)
  val consumer = launch {
    for (batch in batches) for (chunk in batch.chunked(8)) process(chunk)
  }
  val complete = try {
    enumerate { batch -> if (batch.isNotEmpty()) batches.send(batch) }
  } finally { batches.close() }
  consumer.join()
  complete
}
