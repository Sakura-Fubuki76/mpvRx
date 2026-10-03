package app.gyrolet.mpvrx.repository

import android.media.MediaMetadataRetriever
import app.gyrolet.mpvrx.database.dao.CloudMetadataDao
import app.gyrolet.mpvrx.database.entities.CloudDirectoryItemEntity
import app.gyrolet.mpvrx.database.entities.CloudDirectoryStateEntity
import app.gyrolet.mpvrx.database.entities.CloudVideoMetadataEntity
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import app.gyrolet.mpvrx.domain.cloud.probeVideoDurationMs
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** Cloud state is isolated from local File-based metadata and its maintenance rules. */
class CloudMetadataRepository(
  private val dao: CloudMetadataDao,
  httpClient: OkHttpClient,
  private val keyframes: app.gyrolet.mpvrx.domain.cloud.CloudKeyframeExtractor,
) {
  private val http = httpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
  private val workers = Semaphore(2)
  private val locks = Array(64) { Mutex() }

  suspend fun cachedDirectory(connectionId: Long, rawPath: String): List<NetworkFile>? = withContext(Dispatchers.IO) {
    val path = NetworkPath.from(rawPath).value
    dao.getDirectoryState(connectionId, path) ?: return@withContext null
    dao.getDirectory(connectionId, path).map {
      NetworkFile(it.name, it.path, it.size, it.isDirectory, it.lastModified, it.mimeType)
    }
  }

  suspend fun saveDirectory(connectionId: Long, rawPath: String, files: List<NetworkFile>) = withContext(Dispatchers.IO) {
    val path = NetworkPath.from(rawPath).value
    dao.replaceDirectory(
      CloudDirectoryStateEntity(connectionId, path, System.currentTimeMillis()),
      files.map {
        CloudDirectoryItemEntity(connectionId, path, NetworkPath.from(it.path).value,
          it.name, it.size, it.lastModified, it.isDirectory, it.mimeType)
      },
    )
  }

  fun observeVideos(connectionId: Long, files: List<NetworkFile>): Flow<List<NetworkFile>> {
    if (files.isEmpty()) return flowOf(emptyList())
    val paths = files.map { NetworkPath.from(it.path).value }.distinct()
    return combine(paths.chunked(400).map { dao.observeVideos(connectionId, it) }) { batches ->
      batches.flatMap { it }.associateBy { it.path }
    }.map { byPath ->
      files.map { file ->
        val entry = byPath[NetworkPath.from(file.path).value]
        if (entry != null && entry.matches(file)) file.copy(durationMs = entry.durationMs) else file
      }
    }
  }

  suspend fun probeMissing(connection: NetworkConnection, files: List<NetworkFile>) = coroutineScope {
    files.filter { !it.isDirectory && (it.mimeType?.startsWith("video/") == true ||
      it.name.substringAfterLast('.', "").lowercase() in FileTypeUtils.VIDEO_EXTENSIONS) }
      .chunked(8).forEach { batch ->
        batch.map { file -> async(Dispatchers.IO) { workers.withPermit { probe(connection, file) } } }.awaitAll()
      }
  }

  private suspend fun probe(connection: NetworkConnection, file: NetworkFile) {
    val path = NetworkPath.from(file.path).value
    val lock = locks[((connection.id.hashCode() * 31 + path.hashCode()) and Int.MAX_VALUE) % locks.size]
    lock.withLock {
      val cached = dao.getVideo(connection.id, path)
      if (cached != null && cached.matches(file) && cached.durationMs > 0) return
      // Retry incomplete rows after a bounded cooldown, including across process restarts.
      if (cached != null && cached.matches(file) && System.currentTimeMillis() - cached.updatedAt < 30_000) return
      if (cached != null && !cached.matches(file)) {
        publish(connection.id, path, file.size, file.lastModified, 0, 0, 0, 0)
      }
      val proxy = NetworkStreamingProxy.getInstance()
      val streamId = "metadata_${UUID.randomUUID()}"
      try {
        // Proxy owns credential resolution; requests never persist an authenticated URL.
        val url = proxy.registerStream(streamId, connection, path, file.size, file.mimeType ?: "application/octet-stream")
        val extension = app.gyrolet.mpvrx.domain.cloud.cloudMediaExtension(file.name)
        val key = app.gyrolet.mpvrx.domain.cloud.cloudMediaKey(connection, path, file.size, file.lastModified)
        var duration = probeVideoDurationMs(url, http, extension) { keyframes.extractDurationMs(it, key, extension) } ?: 0
        if (duration <= 0 && keyframes.supports(extension)) duration = keyframes.extractDurationMs(url, key, extension) ?: 0
        var width = 0
        var height = 0
        if (duration > 0) {
          currentCoroutineContext().ensureActive()
          publish(connection.id, path, file.size, file.lastModified, duration, 0, 0, System.currentTimeMillis())
        } else {
          withTimeoutOrNull(10_000) {
            runInterruptible(Dispatchers.IO) {
              val retriever = MediaMetadataRetriever()
              try {
                retriever.setDataSource(url, emptyMap<String, String>())
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
              } finally {
                retriever.release()
              }
            }
          }
          currentCoroutineContext().ensureActive()
          publish(connection.id, path, file.size, file.lastModified, duration, width, height, System.currentTimeMillis())
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        publish(connection.id, path, file.size, file.lastModified, 0, 0, 0, System.currentTimeMillis())
      } finally {
        proxy.unregisterStream(streamId)
      }
    }
  }

  suspend fun publish(connectionId: Long, path: String, size: Long, modified: Long,
    duration: Long, width: Int, height: Int, updatedAt: Long = System.currentTimeMillis()) {
    dao.mergeCurrentVideo(connectionId, NetworkPath.from(path).value, size, modified, duration, width, height, updatedAt)
  }

  private fun CloudVideoMetadataEntity.matches(file: NetworkFile): Boolean =
    size == file.size && lastModified == file.lastModified
}
