package app.gyrolet.mpvrx.domain.cloud

import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/** Debug diagnostics never contain credentials, signed URLs, or raw filenames. */
object CloudTrace {
  private val started = java.util.concurrent.atomic.AtomicBoolean()
  private val events = Channel<String>(4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun initialize(context: android.content.Context) {
    if (!BuildConfig.DEBUG || !started.compareAndSet(false, true)) return
    val target = java.io.File(context.filesDir, "cloud-debug.log")
    scope.launch {
      for (first in events) {
        val batch = StringBuilder(first)
        // Drain bursts in one write; diagnostic disk I/O never blocks navigation or drawing.
        for (index in 0 until 127) {
          val next = events.tryReceive().getOrNull() ?: break
          batch.append(next)
        }
        runCatching {
          if (target.length() > 2 * 1024 * 1024) {
            val previous = java.io.File(target.parentFile, "cloud-debug.previous.log")
            java.nio.file.Files.move(target.toPath(), previous.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          }
          target.appendText(batch.toString())
        }
      }
    }
  }

  fun event(stage: String, connectionId: Long = 0, path: String? = null, detail: String = "") {
    if (!BuildConfig.DEBUG) return
    val identity = path?.let { " pathKey=${it.hashCode().toUInt().toString(16)}" }.orEmpty()
    val message = "stage=$stage connection=$connectionId$identity $detail"
    Log.i("CloudTrace", message)
    events.trySend("${System.currentTimeMillis()} $message\n")
  }
}
