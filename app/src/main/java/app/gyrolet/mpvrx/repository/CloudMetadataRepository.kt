package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.domain.cloud.CloudTrace
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
  private val mediaConcurrency = app.gyrolet.mpvrx.domain.cloud.cloudMediaConcurrency()
  private val metadataQueue = app.gyrolet.mpvrx.domain.cloud.MetadataWorkQueue(mediaConcurrency, foregroundWorkers = if (mediaConcurrency > 1) 1 else 0, backgroundAllowed = { app.gyrolet.mpvrx.domain.cloud.cloudBackgroundAllowed() })
  private val locks = Array(64) { Mutex() }

  private val storageScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
  private data class StorageScan(val revision: String, val job: kotlinx.coroutines.Job, var completedAt: Long = 0)
  private val storageScans = java.util.concurrent.ConcurrentHashMap<Long, StorageScan>()

  /** Survives directory navigation; starts at the connection root, not the visible folder. */
  @Synchronized
  fun scanStorage(connection: NetworkConnection, network: NetworkRepository, includeThumbnails: Boolean,
    strategy: String = "", force: Boolean = false) {
    if (connection.protocol !in setOf(app.gyrolet.mpvrx.domain.network.NetworkProtocol.WEBDAV, app.gyrolet.mpvrx.domain.network.NetworkProtocol.OPENLIST)) return
    val revision = "${connection.copy(lastConnected = 0, name = "", autoConnect = false).hashCode()}|$includeThumbnails|$strategy"
    val previous = storageScans[connection.id]
    if (!force && previous?.revision == revision && (previous.job.isActive ||
      previous.completedAt > 0 && System.currentTimeMillis() - previous.completedAt < 30 * 60_000)) {
      CloudTrace.event("storage.skip", connection.id, detail = "reason=${if (previous.job.isActive) "running" else "fresh_complete"}")
      return
    }
    CloudTrace.event("storage.schedule", connection.id, detail = "thumbnails=$includeThumbnails force=$force replacing=${previous != null}")
    previous?.job?.cancel()
    val job = storageScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
      try {
        android.util.Log.d("CloudBatch", "storage start connection=${connection.id}")
        val discovered = hashSetOf<String>()
        var fileCount = 0
        val metadataComplete = java.util.concurrent.atomic.AtomicBoolean(true)
        suspend fun processBatch(batch: List<NetworkFile>) {
          if (!cacheMissingMetadata(connection, batch,
              app.gyrolet.mpvrx.domain.cloud.MetadataRequestPriority.BACKGROUND, includeThumbnails)) metadataComplete.set(false)
        }
        val enumerationComplete = app.gyrolet.mpvrx.domain.cloud.streamStorageMetadata<NetworkFile>(
          enumerate = { submit ->
            fun video(file: NetworkFile) = !file.isDirectory && (file.mimeType?.startsWith("video/") == true ||
              app.gyrolet.mpvrx.domain.cloud.cloudMediaExtension(file.name) in FileTypeUtils.VIDEO_EXTENSIONS)
            fun revision(file: NetworkFile) = "${file.path}|${file.size}|${file.lastModified}"
            val warm = cachedFilesBelow(connection.id, "/").filter(::video)
            warm.forEach { discovered.add(revision(it)) }
            submit(warm)
            CloudTrace.event("storage.warm", connection.id, detail = "cachedVideos=${warm.size}")
            val complete = scanFolders(connection, listOf("/"), network) { listed ->
              val videos = listed.filter { video(it) && discovered.add(revision(it)) }
              submit(videos)
            }
            val files = cachedFilesBelow(connection.id, "/")
            fileCount = files.size
            // Retain offline cached work for directories whose refreshed listing failed.
            submit(files.filter { video(it) && discovered.add(revision(it)) })
            CloudTrace.event("storage.enumerated", connection.id, detail = "files=$fileCount complete=$complete streaming=true")
            complete
          },
          process = { batch -> processBatch(batch) },
          consumers = mediaConcurrency,
        )
        // Retry missing rows after the initial pass, with increasing delay; never re-enumerate.
        var retry = 0
        while (!metadataComplete.get() && retry < 2) {
          kotlinx.coroutines.delay(30_000L shl retry)
          retry++
          metadataComplete.set(true)
          CloudTrace.event("storage.metadata.retry", connection.id, detail = "attempt=$retry")
          app.gyrolet.mpvrx.domain.cloud.streamStorageMetadata<NetworkFile>(
            enumerate = { submit -> submit(cachedFilesBelow(connection.id, "/")); true },
            process = { batch -> processBatch(batch) }, consumers = mediaConcurrency,
          )
        }
        CloudTrace.event("storage.finished", connection.id, detail = "directoriesComplete=$enumerationComplete metadataComplete=${metadataComplete.get()} files=$fileCount retries=$retry")
        if (enumerationComplete && metadataComplete.get()) storageScans[connection.id]?.takeIf { it.job == currentCoroutineContext()[kotlinx.coroutines.Job] }?.completedAt = System.currentTimeMillis()
        android.util.Log.d("CloudBatch", "storage complete connection=${connection.id} files=$fileCount enumerationComplete=$enumerationComplete")
      } catch (cancelled: CancellationException) {
        CloudTrace.event("storage.cancelled", connection.id)
        throw cancelled
      } catch (error: Exception) {
        CloudTrace.event("storage.failed", connection.id, detail = "error=${error.javaClass.simpleName}")
        android.util.Log.w("CloudBatch", "Storage scan failed; retaining cache", error)
      }
    }
    storageScans[connection.id] = StorageScan(revision, job)
    job.start()
  }

  fun cancelStorage(connectionId: Long) { storageScans.remove(connectionId)?.job?.cancel() }

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

  suspend fun cachedFilesBelow(connectionId: Long, path: String): List<NetworkFile> = withContext(Dispatchers.IO) {
    dao.getFilesBelow(connectionId, NetworkPath.from(path).value).map {
      NetworkFile(it.name, it.path, it.size, it.isDirectory, it.lastModified, it.mimeType)
    }
  }

  fun observeVideos(connectionId: Long, files: List<NetworkFile>): Flow<List<NetworkFile>> {
    if (files.isEmpty()) return flowOf(emptyList())
    val paths = files.map { NetworkPath.from(it.path).value }.distinct()
    return combine(paths.chunked(400).map { dao.observeVideos(connectionId, it) }) { batches ->
      batches.flatMap { it }.associateBy { it.path }
    }.map { byPath ->
      files.map { file ->
        val entry = byPath[NetworkPath.from(file.path).value]
        if (entry != null && entry.matches(file)) file.copy(durationMs = entry.durationMs.takeIf { it > 0 } ?: file.durationMs, width = entry.width.takeIf { it > 0 } ?: file.width, height = entry.height.takeIf { it > 0 } ?: file.height) else file
      }
    }.distinctUntilChanged()
  }

  suspend fun enrichVideos(connectionId: Long, files: List<NetworkFile>): List<NetworkFile> {
    val summaries = observeFolders(connectionId).first().associateBy { it.path }
    return observeVideos(connectionId, files).first().map { file ->
      val summary = summaries[file.path]
      if (file.isDirectory && summary != null) file.copy(videoCount = summary.videoCount,
        size = summary.totalSize, durationMs = summary.totalDurationMs,
        folderScanComplete = summary.scanComplete && System.currentTimeMillis() - summary.updatedAt < 24 * 60 * 60 * 1000L)
      else file
    }
  }

  fun observeFolders(connectionId: Long) = dao.observeFolders(connectionId).distinctUntilChanged()

  suspend fun registerIndexedFiles(connectionId: Long, files: List<NetworkFile>): List<NetworkFile> = withContext(Dispatchers.IO) {
    // AList search omits modified timestamps; absence must not invalidate a listed file's version.
    val resolved = files.map { file ->
      val current = dao.getItem(connectionId, NetworkPath.from(file.path).value)
      if (current != null && current.size == file.size && file.lastModified == 0L)
        file.copy(lastModified = current.lastModified)
      else file
    }
    dao.insertItems(resolved.map { file ->
      val path = NetworkPath.from(file.path)
      val parent = NetworkPath.from(path.segments.dropLast(1).joinToString("/"))
      CloudDirectoryItemEntity(connectionId, parent.value, path.value, file.name, file.size, file.lastModified, file.isDirectory, file.mimeType)
    })
    enrichVideos(connectionId, resolved)
  }

  suspend fun scanFolders(connection: NetworkConnection, paths: List<String>, network: NetworkRepository,
    onListed: suspend (List<NetworkFile>) -> Unit = {}): Boolean {
    val scanner = app.gyrolet.mpvrx.domain.cloud.CloudFolderScanner(connection.id, { path ->
      val current = network.getConnectionById(connection.id)
      if (current == null || current.isDeleted || current.copy(lastConnected = 0, name = "", autoConnect = false) !=
        connection.copy(lastConnected = 0, name = "", autoConnect = false)) throw CancellationException("Storage settings changed")
      CloudTrace.event("directory.begin", connection.id, path)
      network.listFiles(connection, path).also { result ->
        CloudTrace.event("directory.result", connection.id, path,
          "success=${result.isSuccess} items=${result.getOrNull()?.size ?: 0} error=${result.exceptionOrNull()?.javaClass?.simpleName ?: "none"}")
        currentCoroutineContext().ensureActive()
        result.getOrNull()?.let { saveDirectory(connection.id, path, it); onListed(it) }
      }
    }, {
      dao.putScannedFolder(it)
      CloudTrace.event("folder.summary", connection.id, it.path, "videos=${it.videoCount} complete=${it.scanComplete} empty=${it.scanComplete && it.videoCount == 0}")
    })
    val complete = paths.map { scanner.scan(it).scanComplete }.all { it }
    dao.refreshFolderDurations(connection.id)
    return complete
  }

  /** yume's complete-directory entry point: preload, skip completed rows, then submit all pending items. */
  internal suspend fun cacheMissingMetadata(connection: NetworkConnection, files: List<NetworkFile>,
    priority: app.gyrolet.mpvrx.domain.cloud.MetadataRequestPriority, includeThumbnails: Boolean) = withContext(Dispatchers.IO) {
    val videos = files.filter { !it.isDirectory && (it.mimeType?.startsWith("video/") == true ||
      it.name.substringAfterLast('.', "").lowercase() in FileTypeUtils.VIDEO_EXTENSIONS) }.distinctBy { it.path }
    val cached = videos.map { NetworkPath.from(it.path).value }.chunked(400).flatMap { dao.getVideos(connection.id, it) }.associateBy { it.path }
    val thumbnails = org.koin.java.KoinJavaComponent.get<app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository>(app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository::class.java)
    val pending = videos.filter { file ->
      val entry = cached[NetworkPath.from(file.path).value]
      entry == null || !entry.matches(file) || entry.durationMs <= 0 || (includeThumbnails && !thumbnails.isNetworkThumbnailCached(connection, file))
    }
    val done = java.util.concurrent.atomic.AtomicInteger()
    val ready = java.util.concurrent.atomic.AtomicInteger()
    CloudTrace.event("batch.begin", connection.id, detail = "priority=$priority videos=${videos.size} pending=${pending.size} thumbnails=$includeThumbnails")
    android.util.Log.d("CloudBatch", "yume batch connection=${connection.id} priority=$priority total=${videos.size} needed=${pending.size}")
    if (priority == app.gyrolet.mpvrx.domain.cloud.MetadataRequestPriority.FOREGROUND) {
      metadataQueue.promote(pending.map { "${connection.id}|${it.path}" }.toSet())
    }
    metadataQueue.process(pending, key = { "${connection.id}|${it.path}" }, priority = priority) { file ->
      try {
        probe(connection, file)
        val bitmap = if (includeThumbnails) thumbnails.getThumbnailForNetworkPath(file.path, 480, 300, connection, file.size, file.mimeType, file.lastModified,
          backgroundWork = priority == app.gyrolet.mpvrx.domain.cloud.MetadataRequestPriority.BACKGROUND) else null
        val metadata = dao.getVideo(connection.id, NetworkPath.from(file.path).value)
        val durationReady = metadata?.matches(file) == true && metadata.durationMs > 0
        val success = durationReady && (!includeThumbnails || bitmap != null)
        if (success) ready.incrementAndGet()
        CloudTrace.event("metadata.result", connection.id, file.path, "durationReady=$durationReady thumbnailReady=${bitmap != null} priority=$priority")
        success
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        CloudTrace.event("metadata.failed", connection.id, file.path, "error=${error.javaClass.simpleName}")
        android.util.Log.w("CloudBatch", "Metadata item failed", error); false
      }
      finally {
        val count = done.incrementAndGet()
        if (count % 10 == 0 || count == pending.size) android.util.Log.d("CloudBatch", "yume progress connection=${connection.id} priority=$priority completed=$count/${pending.size}")
      }
    }
    CloudTrace.event("batch.end", connection.id, detail = "priority=$priority processed=${done.get()} ready=${ready.get()} pending=${pending.size}")
    ready.get() == pending.size
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
      if (cached != null && cached.matches(file) && cached.durationMs > 0) {
        if (file.width > 0 || file.height > 0) publish(connection.id, path, file.size, file.lastModified, 0, file.width, file.height, System.currentTimeMillis())
        return
      }
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
          publish(connection.id, path, file.size, file.lastModified, duration, file.width, file.height, System.currentTimeMillis())
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
    if (duration > 0) dao.refreshFolderDurations(connectionId)
  }

  private fun CloudVideoMetadataEntity.matches(file: NetworkFile): Boolean =
    size == file.size && lastModified == file.lastModified
}
