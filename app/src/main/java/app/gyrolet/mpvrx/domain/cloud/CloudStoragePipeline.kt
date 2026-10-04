package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll

/** Queue lightweight file descriptions, not video data; slow probes must not stop tree discovery. */
internal suspend fun <T> streamStorageMetadata(
  enumerate: suspend (suspend (List<T>) -> Unit) -> Boolean,
  process: suspend (List<T>) -> Unit,
  consumers: Int = 1,
): Boolean = coroutineScope {
  require(consumers > 0)
  val batches = Channel<List<T>>(Channel.UNLIMITED)
  val workers = List(consumers) { launch {
    for (batch in batches) process(batch)
  } }
  val complete = try {
    enumerate { batch -> batch.chunked(8).forEach { batches.send(it) } }
  } finally { batches.close() }
  workers.joinAll()
  complete
}
