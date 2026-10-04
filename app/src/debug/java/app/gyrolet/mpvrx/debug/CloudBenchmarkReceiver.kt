package app.gyrolet.mpvrx.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import app.gyrolet.mpvrx.domain.cloud.CloudTrace
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.repository.CloudMetadataRepository
import app.gyrolet.mpvrx.repository.NetworkRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.koin.java.KoinJavaComponent.get

/** ADB-only diagnostic in debug APKs; uses isolated thumbnail files, never clears user caches. */
class CloudBenchmarkReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != "app.gyrolet.mpvrx.CLOUD_BENCHMARK") return
    scope.launch {
      if (!running.compareAndSet(false, true)) return@launch
      try {
        val network = get<NetworkRepository>(NetworkRepository::class.java)
        val metadata = get<CloudMetadataRepository>(CloudMetadataRepository::class.java)
        val samples = network.getAllConnectionsIncludingDeleted().filter { !it.isDeleted }.take(2).flatMap { connection ->
          network.connect(connection).getOrThrow()
          metadata.cachedFilesBelow(connection.id, "/").filter {
            !it.isDirectory && (it.mimeType?.startsWith("video/") == true ||
              it.name.substringAfterLast('.').lowercase() in setOf("mkv", "mp4", "mov"))
          }.sortedBy { it.path }.take(4).map { connection to it }
        }
        CloudTrace.event("benchmark.begin", detail = "samples=${samples.size} heapMax=${Runtime.getRuntime().maxMemory()}")
        if (samples.isEmpty()) return@launch
        for ((round, concurrency) in listOf(2, 1, 4, 2, 6, 3, 8, 3, 6, 2, 4, 1, 8).withIndex()) {
          val root = File(context.filesDir, "cloud-benchmark/$round").apply { mkdirs() }
          val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getFilesDir(): File = root
          }
          val thumbnails = ThumbnailRepository(isolated, concurrency)
          val success = AtomicInteger()
          val peakPss = AtomicLong()
          val started = SystemClock.elapsedRealtime()
          val monitor = launch {
            while (isActive) {
              val info = Debug.MemoryInfo()
              Debug.getMemoryInfo(info)
              peakPss.updateAndGet { maxOf(it, info.totalPss.toLong()) }
              delay(200)
            }
          }
          try {
            withTimeout(240_000) {
              samples.map { (connection, file) -> async {
                if (thumbnails.getThumbnailForNetworkPath(file.path, 480, 300, connection,
                    file.size, file.mimeType, file.lastModified, ignoreDisplayPreference = true) != null) success.incrementAndGet()
              } }.awaitAll()
            }
          } catch (error: Exception) {
            CloudTrace.event("benchmark.error", detail = "round=$round error=${error.javaClass.simpleName}")
          } finally {
            monitor.cancelAndJoin()
            CloudTrace.event("benchmark.result", detail = "round=$round concurrency=$concurrency samples=${samples.size} success=${success.get()} elapsedMs=${SystemClock.elapsedRealtime() - started} peakPssKb=${peakPss.get()} nativeHeap=${Debug.getNativeHeapAllocatedSize()}")
            root.deleteRecursively()
          }
          delay(1_000)
        }
        CloudTrace.event("benchmark.finished")
      } finally { running.set(false) }
    }
  }

  companion object {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.atomic.AtomicBoolean()
  }
}
