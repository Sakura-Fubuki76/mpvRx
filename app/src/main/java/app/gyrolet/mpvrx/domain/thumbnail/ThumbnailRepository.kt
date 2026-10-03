/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.thumbnail

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.LruCache
import app.gyrolet.mpvrx.data.network.client.NetworkMimeTypes
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.cloud.CloudKeyframeExtractor
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.preferences.ThumbnailMode
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.java.KoinJavaComponent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.roundToInt

private const val NETWORK_THUMBNAIL_FAILURE_RETRY_MS = 30_000L

/** How long a local thumbnail failure is left alone before it is attempted again. */
private const val LOCAL_THUMBNAIL_FAILURE_RETRY_MS = 60_000L

class ThumbnailRepository(
  private val context: Context,
) {
  private val appearancePreferences by lazy {
    KoinJavaComponent.get<app.gyrolet.mpvrx.preferences.AppearancePreferences>(
      app.gyrolet.mpvrx.preferences.AppearancePreferences::class.java,
    )
  }
  private val browserPreferences by lazy {
    KoinJavaComponent.get<app.gyrolet.mpvrx.preferences.BrowserPreferences>(
      app.gyrolet.mpvrx.preferences.BrowserPreferences::class.java,
    )
  }
  private val networkRepository by lazy {
    KoinJavaComponent.get<NetworkRepository>(NetworkRepository::class.java)
  }

  /** Index-based MP4/MKV keyframe extraction for remote sources; resolved lazily through Koin. */
  private val cloudKeyframes by lazy {
    KoinJavaComponent.get<CloudKeyframeExtractor>(CloudKeyframeExtractor::class.java)
  }

  private val memoryCache: LruCache<String, Bitmap>
  private val localDiskDir = File(context.filesDir, "thumbnails/local").apply { mkdirs() }
  private val networkDiskDir = File(context.filesDir, "thumbnails/network").apply { mkdirs() }
  private val diskCacheLock = ReentrantReadWriteLock()
  private val ongoingOperations = ConcurrentHashMap<String, Deferred<Bitmap?>>()
  private val diskVideoBaseKeyCache = ConcurrentHashMap<String, String>()
  // Lets composition reuse keys already computed on worker threads.
  private val resolvedThumbnailKeys = LruCache<String, String>(4096)
  private data class ResolvedMetadata(
    val size: Long,
    val dateModified: Long,
    val duration: Long,
  )
  private val localMetadataCache = ConcurrentHashMap<String, ResolvedMetadata>()
  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val maxConcurrentFolders = 3
  private val localGenerationParallelism = resolveLocalGenerationParallelism()
  private val localGenerationSemaphore = Semaphore(localGenerationParallelism)
  private val networkGenerationSemaphore = Semaphore(1)
  private val maxFolderBatchSize = 48

  // Per-batch progress lives in [completedFolderVideoKeys], keyed per video, so it survives both a
  // cancel and a different visible window.
  private val folderJobs = ConcurrentHashMap<String, Job>()

  // Throttles local failures so a corrupt or unsupported file is not handed to
  // MediaMetadataRetriever again on every scroll settle, while still being retried later in case
  // the cause was transient (storage remounted, permission granted, decoder was busy).
  private val localThumbnailFailedAt = ConcurrentHashMap<String, Long>()

  // Decoded frames straight from disk, kept at their stored (size-independent) resolution so that
  // re-entering a card after it scrolled away is a memory hit instead of a fresh file read,
  // JPEG decode and scale. Bounded by bytes, and only ever holds frames that are already
  // available on disk, so entries can be dropped at any time without losing anything.
  private val decodedDiskCache: LruCache<String, Bitmap>

  // Index from a size-independent video identity to the full size-specific memory-cache keys
  // produced for it. Lets composition seed a card with an already-decoded frame for that video
  // even when its own size variant was never computed.
  private val thumbnailKeysByVideo =
    object : LinkedHashMap<String, LinkedHashSet<String>>(512) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashSet<String>>?): Boolean =
        size > 1024
    }

  // Same idea for network sources, keyed by [networkThumbnailIdentity].
  private val networkKeysByIdentity =
    object : LinkedHashMap<String, LinkedHashSet<String>>(512) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashSet<String>>?): Boolean =
        size > 1024
    }

  // Throttle transient failures while still allowing remote files to recover during this process.
  private val networkThumbnailFailedAt = ConcurrentHashMap<String, Long>()

  private val _thumbnailReadyKeys =
    MutableSharedFlow<String>(
      extraBufferCapacity = 256,
    )
  val thumbnailReadyKeys: SharedFlow<String> = _thumbnailReadyKeys.asSharedFlow()

  init {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    val cacheSizeKb = maxMemoryKb / 6
    memoryCache =
      object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(
          key: String,
          value: Bitmap,
        ): Int = value.byteCount / 1024
      }

    // Sized in KB like memoryCache. Deliberately smaller than it: this cache exists only to
    // spare repeated disk decodes, and the scaled copies still live in memoryCache.
    val decodedCacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(2 * 1024)
    decodedDiskCache =
      object : LruCache<String, Bitmap>(decodedCacheSizeKb) {
        override fun sizeOf(
          key: String,
          value: Bitmap,
        ): Int = value.byteCount / 1024
      }
  }

  suspend fun getThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      val key = thumbnailKey(video, widthPx, heightPx)

      if (isNetworkUrl(video.path) && !isNetworkThumbnailAllowed(video.path)) {
        return@withContext null
      }

      synchronized(memoryCache) {
        memoryCache.get(key)
      }?.let { return@withContext it }

      ongoingOperations[key]?.let { return@withContext it.await() }

      val candidate =
        async(start = CoroutineStart.LAZY) {
          getCachedThumbnail(video, widthPx, heightPx)?.let { cached ->
            synchronized(memoryCache) {
              memoryCache.put(key, cached)
            }
            _thumbnailReadyKeys.tryEmit(key)
            return@async cached
          }

          val bitmap =
            when {
              isHttpUrl(video.path) ->
                networkGenerationSemaphore.withPermit {
                  getOrCreateNetworkVideoThumbnail(video, widthPx, heightPx)
                }
              isNetworkUrl(video.path) -> null
              else ->
                localGenerationSemaphore.withPermit {
                  generateLocalThumbnail(video, widthPx, heightPx)
                }
            } ?: return@async null

          currentCoroutineContext().ensureActive()
          synchronized(memoryCache) {
            memoryCache.put(key, bitmap)
          }
          val diskKey = diskCacheKey(video)
          writeBitmapToDisk(diskKey, bitmap, isNetworkUrl(video.path))
          // The freshly written frame is now the disk representation, so a card that scrolls
          // back into view can be served from memory instead of re-reading that file.
          synchronized(decodedDiskCache) { decodedDiskCache.put(diskKey, bitmap) }
          _thumbnailReadyKeys.tryEmit(key)
          bitmap
        }

      val operation =
        ongoingOperations.putIfAbsent(key, candidate)?.also {
          candidate.cancel()
        } ?: candidate.also { owned ->
          owned.invokeOnCompletion {
            ongoingOperations.remove(key, owned)
          }
          owned.start()
        }

      operation.await()
    }

  suspend fun getCachedThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      if (isNetworkUrl(video.path) && !isNetworkThumbnailAllowed(video.path)) {
        return@withContext null
      }

      val key = thumbnailKey(video, widthPx, heightPx)
      synchronized(memoryCache) {
        memoryCache.get(key)
      }?.let { return@withContext it }

      val diskKey = diskCacheKey(video)
      val decoded =
        synchronized(decodedDiskCache) { decodedDiskCache.get(diskKey) }
          ?: readBitmapFromDisk(diskKey, isNetworkUrl(video.path))?.also { bitmap ->
            synchronized(decodedDiskCache) { decodedDiskCache.put(diskKey, bitmap) }
          }
          ?: return@withContext null
      val scaled = scaleBitmap(decoded, widthPx, heightPx)
      synchronized(memoryCache) {
        memoryCache.put(key, scaled)
      }
      return@withContext scaled
    }

  /**
   * Memory-only lookup for an already-decoded frame of this video, at its own size or any other.
   *
   * Delegates to [peekThumbnailFromMemory] so callers on the main thread never touch the
   * filesystem, which the key building in [thumbnailKey] would otherwise do.
   */
  fun getThumbnailFromMemory(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    if (isNetworkUrl(video.path) && !isNetworkThumbnailAllowed(video.path)) {
      return null
    }

    return peekThumbnailFromMemory(video, widthPx, heightPx)
  }

  fun clearThumbnailCache() {
    folderJobs.values.forEach { it.cancel() }
    folderJobs.clear()
    ongoingOperations.values.forEach { it.cancel() }
    ongoingOperations.clear()
    diskVideoBaseKeyCache.clear()
    localMetadataCache.clear()
    networkThumbnailFailedAt.clear()
    localThumbnailFailedAt.clear()
    resolvedThumbnailKeys.evictAll()
    synchronized(thumbnailKeysByVideo) { thumbnailKeysByVideo.clear() }
    synchronized(networkKeysByIdentity) { networkKeysByIdentity.clear() }
    synchronized(diskKeysByVideoGroup) { diskKeysByVideoGroup.evictAll() }
    completedFolderVideoKeys.clear()

    synchronized(memoryCache) {
      memoryCache.evictAll()
    }
    synchronized(decodedDiskCache) {
      decodedDiskCache.evictAll()
    }
    folderThumbnailMemory.clear()

    diskCacheLock.write {
      listOf(
        File(context.cacheDir, "thumbnails"),
        File(context.filesDir, "thumbnails"),
        File(context.cacheDir, "remote_images"),
        File(context.cacheDir, "network_images"),
      ).forEach(::deleteCacheDirectory)
      check(localDiskDir.mkdirs() || localDiskDir.isDirectory) {
        "Unable to recreate thumbnail cache directory: ${localDiskDir.absolutePath}"
      }
      check(networkDiskDir.mkdirs() || networkDiskDir.isDirectory) {
        "Unable to recreate thumbnail cache directory: ${networkDiskDir.absolutePath}"
      }
      // NetworkImageRepository owns this directory but is a Koin singleton, so its mkdirs() only
      // ever ran once at construction. Recreate it here or every network image load silently fails
      // until the process restarts.
      File(context.cacheDir, "network_images").mkdirs()
    }
  }

  private fun deleteCacheDirectory(directory: File) {
    if (directory.exists() && !directory.deleteRecursively()) {
      error("Unable to clear cache directory: ${directory.absolutePath}")
    }
  }

  fun startFolderThumbnailGeneration(
    folderId: String,
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ) {
    val videoSequence = videos.asSequence()
    val filteredVideos =
      (
        if (appearancePreferences.showNetworkThumbnails.get()) {
          videoSequence
        } else {
          videoSequence.filterNot { isNetworkUrl(it.path) }
        }
      ).take(maxFolderBatchSize)
        .toList()

    if (filteredVideos.isEmpty()) {
      return
    }

    folderJobs.entries.removeAll { !it.value.isActive }

    if (folderJobs.size >= maxConcurrentFolders && !folderJobs.containsKey(folderId)) {
      folderJobs.entries.firstOrNull()?.let { (oldestId, job) ->
        job.cancel()
        folderJobs.remove(oldestId)
      }
    }

    // Signature covers only the render settings. Folding the visible window into it made every
    // scroll settle look like a different batch, because the window changes as you scroll.
    val signature = folderSignature(widthPx, heightPx)
    val settingsChanged = completedFolderVideoKeys.selectBatch(folderId, signature)

    // Different render settings invalidate every previous mark, and any batch still running under
    // the old settings has to go: its sizes no longer match what the cards are asking for.
    val completed = completedFolderVideoKeys.marksFor(folderId)
    if (settingsChanged) {
      folderJobs.remove(folderId)?.cancel()
    }

    // Marks survive a cancel, so a resumed batch only revisits videos that still need work
    // instead of walking the whole window again. A window that is fully marked, or whose only
    // unfinished videos failed very recently, ends up with an empty pending list and no work.
    if (folderJobs[folderId]?.isActive != true) {
      val pending =
        filteredVideos.filter { video ->
          val group = videoGroupKey(video)
          group !in completed && !hasRecentLocalThumbnailFailure(group)
        }
      if (pending.isEmpty()) {
        return
      }
      folderJobs[folderId] =
        repositoryScope.launch {
          var i = 0
          while (i < pending.size) {
            val batchEnd = (i + localGenerationParallelism).coerceAtMost(pending.size)
            coroutineScope {
              (i until batchEnd)
                .map { index ->
                  async {
                    val video = pending[index]
                    // Only a frame already decoded in memory can be skipped outright. Re-decoding
                    // the disk JPEG on every scroll settle was the visible cost of scrolling a
                    // long folder; decodedDiskCache is what makes the decode itself a one-off.
                    val needsWork = needsThumbnailWork(video, widthPx, heightPx)
                    val bitmap = if (needsWork) getThumbnail(video, widthPx, heightPx) else null
                    // A failure stays unmarked so a later visit retries it, which is what lets a
                    // network share or an unreadable file recover.
                    if (!needsWork || bitmap != null) {
                      completed.add(videoGroupKey(video))
                    } else {
                      localThumbnailFailedAt[videoGroupKey(video)] = SystemClock.elapsedRealtime()
                    }
                  }
                }.awaitAll()
            }
            i = batchEnd
            yield()
          }
        }
    }
  }

  fun cancelFolderThumbnailGeneration(folderId: String) {
    // Pause, do not forget: dropping the state here made every scroll restart the batch from the
    // first item, so each scroll stop re-walked (and re-decoded) the whole visible window.
    folderJobs.remove(folderId)?.cancel()
  }

  fun thumbnailKey(
    video: Video,
    width: Int,
    height: Int,
  ): String =
    "${videoBaseKey(video)}|$width|$height|${thumbnailModeKey()}|${thumbnailQualityKey()}".also { key ->
      resolvedThumbnailKeys.put(peekIdentity(video, width, height), key)
      val group = videoGroupKey(video)
      synchronized(thumbnailKeysByVideo) {
        thumbnailKeysByVideo.getOrPut(group) { LinkedHashSet() }.add(key)
      }
    }

  /**
   * Size-independent identity of a video's thumbnail.
   *
   * Built from [Video]'s own fields only, never from [videoBaseKey], so it stays safe to evaluate
   * during composition where filesystem and MediaStore access are not allowed.
   */
  private fun videoGroupKey(video: Video): String =
    "${video.path}|${video.uri}|${video.size}|${video.dateModified}|${video.duration}" +
      "|${thumbnailModeKey()}|${thumbnailQualityKey()}"

  /**
   * Non-blocking peek for composition, safe to call from the main thread.
   *
   * Tries the card's own size first, then any size this video was decoded at, then the frame
   * decoded from disk. Building keys through [thumbnailKey] would hit the filesystem, so only
   * previously recorded identities are consulted.
   */
  fun peekThumbnailFromMemory(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    val sizedKey = resolvedThumbnailKeys.get(peekIdentity(video, widthPx, heightPx))
    if (sizedKey != null) {
      synchronized(memoryCache) { memoryCache.get(sizedKey) }?.let { return it }
    }
    // This video's frame may already be decoded at another size (folder prefetch, grid/list
    // switch), or decoded from disk but not yet scaled for this card. Showing either beats
    // flashing the placeholder.
    val group = videoGroupKey(video)
    val candidates = synchronized(thumbnailKeysByVideo) { thumbnailKeysByVideo[group]?.toList() }
    if (candidates != null) {
      synchronized(memoryCache) { candidates.firstNotNullOfOrNull { memoryCache.get(it) } }
        ?.let { return it }
    }
    val diskKey = synchronized(diskKeysByVideoGroup) { diskKeysByVideoGroup[group] } ?: return null
    return synchronized(decodedDiskCache) { decodedDiskCache.get(diskKey) }
  }

  private fun peekIdentity(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): String =
    "${video.path}|${video.uri}|${video.size}|${video.dateModified}|${video.duration}" +
      "|$widthPx|$heightPx|${thumbnailModeKey()}|${thumbnailQualityKey()}"

  /**
   * Folder prefetch and a visible card may request different sizes for the same source.
   * The disk entry is size-independent, so either completion can wake the card and let it
   * decode the cached bitmap at its own target dimensions.
   */
  fun isThumbnailKeyForVideo(
    key: String,
    video: Video,
  ): Boolean =
    key.startsWith("${videoBaseKey(video)}|") &&
      key.endsWith("|${thumbnailModeKey()}|${thumbnailQualityKey()}")

  // Keep extraction-quality changes from reusing smaller legacy images that were
  // cached without their requested dimensions in the key.
  fun diskCacheKey(video: Video): String =
    "video-thumb-v2|${diskVideoBaseKey(video)}|${thumbnailModeKey()}|${thumbnailQualityKey()}".also { key ->
      val group = videoGroupKey(video)
      // LruCache exposes put(), not a set operator.
      synchronized(diskKeysByVideoGroup) { diskKeysByVideoGroup.put(group, key) }
    }

  private fun canonicalLocalPath(video: Video): String {
    val raw = video.path.ifBlank { video.uri.toString() }
    if (isNetworkUrl(raw)) return raw
    if (ZipArchiveMedia.isPlaybackUri(raw)) return raw

    val decoded = runCatching { Uri.decode(raw) }.getOrNull() ?: raw

    if (decoded.startsWith("file://", ignoreCase = true)) {
      val parsed = runCatching { Uri.parse(decoded).path }.getOrNull()
      if (!parsed.isNullOrBlank()) return parsed
      return decoded.removePrefix("file://")
    }

    if (decoded.startsWith("content://", ignoreCase = true) || video.uri.scheme.equals("content", ignoreCase = true)) {
      val targetUri =
        if (decoded.startsWith("content://", ignoreCase = true)) {
          runCatching { Uri.parse(decoded) }.getOrNull() ?: video.uri
        } else {
          video.uri
        }
      val resolved = runCatching { targetUri.resolveLocalPath(context) }.getOrNull()
      if (!resolved.isNullOrBlank()) return resolved
    }

    if (video.uri.scheme.equals("file", ignoreCase = true)) {
      val p = video.uri.path
      if (!p.isNullOrBlank()) return p
    }

    return decoded
  }

  private fun resolveLocalMetadata(video: Video, source: String): ResolvedMetadata {
    if (video.size > 0L && video.dateModified > 0L && video.duration > 0L) {
      return ResolvedMetadata(video.size, video.dateModified, video.duration)
    }

    localMetadataCache[source]?.let { return it }

    var size = video.size
    var dateModified = video.dateModified
    var duration = video.duration

    val file = if (!source.contains("://")) File(source) else null
    if (file != null && file.exists()) {
      if (size <= 0L) size = file.length()
      if (dateModified <= 0L) dateModified = file.lastModified() / 1000L
    }

    // Query MediaStore which is indexed by Android
    runCatching {
      val projection =
        arrayOf(
          MediaStore.Video.Media.DURATION,
          MediaStore.Video.Media.SIZE,
          MediaStore.Video.Media.DATE_MODIFIED,
        )
      val cursor =
        when {
          video.uri.scheme == "content" &&
            video.uri.toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()) -> {
            context.contentResolver.query(video.uri, projection, null, null, null)
          }
          file != null -> {
            context.contentResolver.query(
              MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
              projection,
              "${MediaStore.Video.Media.DATA} = ?",
              arrayOf(source),
              null,
            )
          }
          else -> null
        }
      cursor?.use { c ->
        if (c.moveToFirst()) {
          val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
          val sizeCol = c.getColumnIndex(MediaStore.Video.Media.SIZE)
          val dateCol = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
          if (durCol >= 0 && duration <= 0L) duration = c.getLong(durCol)
          if (sizeCol >= 0 && size <= 0L) size = c.getLong(sizeCol)
          if (dateCol >= 0 && dateModified <= 0L) dateModified = c.getLong(dateCol)
        }
      }
    }

    val resolved = ResolvedMetadata(size, dateModified, duration)
    localMetadataCache[source] = resolved
    return resolved
  }

  private fun extractDurationFallback(video: Video): Long =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        setLocalDataSource(retriever, video)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrDefault(0L)

  private fun videoBaseKey(video: Video): String {
    if (isNetworkUrl(video.path)) {
      val base = video.path.ifBlank { video.uri.toString() }
      return "$base|network"
    }

    val source = canonicalLocalPath(video)
    val meta = resolveLocalMetadata(video, source)
    return "$source|${meta.size}|${meta.dateModified}|${meta.duration}"
  }

  /** Sidecar artwork probing is disk I/O, so keep it out of keys evaluated during composition. */
  private fun diskVideoBaseKey(video: Video): String {
    val baseKey = videoBaseKey(video)
    if (isNetworkUrl(video.path)) return baseKey
    diskVideoBaseKeyCache[baseKey]?.let { return it }

    val canonicalPath = canonicalLocalPath(video)
    val artworkSignature =
      EmbeddedArtworkCandidates
        .forVideoPath(canonicalPath)
        .asSequence()
        .map(::File)
        .firstOrNull { it.isFile && it.canRead() }
        ?.let { artwork -> "|art:${artwork.name}:${artwork.length()}:${artwork.lastModified()}" }
        .orEmpty()
    val resolvedKey = "$baseKey$artworkSignature"
    return diskVideoBaseKeyCache.putIfAbsent(baseKey, resolvedKey) ?: resolvedKey
  }

  private suspend fun generateLocalThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    if (ZipArchiveMedia.isPlaybackUri(video.uri.toString())) return null
    val mode = browserPreferences.thumbnailMode.get()
    val dimension = maxOf(widthPx, heightPx, MAX_THUMBNAIL_SIZE).coerceAtMost(thumbnailMaxSize())

    if (video.isAudio || mode == ThumbnailMode.Smart || mode == ThumbnailMode.EmbeddedThumbnail) {
      generateEmbeddedArtwork(video)?.let { return scaleBitmap(it, widthPx, heightPx) }
      if (video.isAudio) return null
    }

    generateWithFastThumbnails(video, mode, dimension)?.let {
      return scaleBitmap(it, widthPx, heightPx)
    }

    return extractLocalVideoFrame(video, widthPx, heightPx)
  }

  private fun generateEmbeddedArtwork(video: Video): Bitmap? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        val canonicalPath = canonicalLocalPath(video)
        setLocalDataSource(retriever, video)
        EmbeddedArtworkResolver.decodeEmbeddedArtwork(canonicalPath, retriever)?.scaleToThumbnailMax(thumbnailMaxSize())
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private suspend fun generateWithFastThumbnails(
    video: Video,
    mode: ThumbnailMode,
    dimension: Int,
  ): Bitmap? {
    val canonicalPath = canonicalLocalPath(video)
    if (video.isAudio || canonicalPath.isBlank()) return null
    val targetVideo = if (video.path != canonicalPath) video.copy(path = canonicalPath) else video

    val resolvedDuration =
      if (targetVideo.duration > 0L) {
        targetVideo.duration
      } else {
        val metaDur = resolveLocalMetadata(targetVideo, canonicalPath).duration
        if (metaDur > 0L) metaDur else extractDurationFallback(targetVideo)
      }
    val durationSeconds = resolvedDuration.coerceAtLeast(0L) / 1000.0
    val requestedPosition =
      when (mode) {
        ThumbnailMode.FirstFrame, ThumbnailMode.EmbeddedThumbnail -> 0.0
        ThumbnailMode.FrameAtPosition ->
          durationSeconds * (browserPreferences.thumbnailFramePosition.get() / 100.0).coerceIn(0.0, 1.0)
        ThumbnailMode.Smart -> durationSeconds * 0.33
      }
    val lastSafePosition = (durationSeconds - 0.1).coerceAtLeast(0.0)
    val positions =
      when (mode) {
        ThumbnailMode.Smart -> listOf(requestedPosition, 10.0, 20.0, 30.0)
        else -> listOf(requestedPosition)
      }.map { position ->
        if (durationSeconds > 0.0) position.coerceIn(0.0, lastSafePosition) else position.coerceAtLeast(0.0)
      }.distinct()

    var lastSolidBitmap: Bitmap? = null
    for (position in positions) {
      val bitmap =
        try {
          FastThumbnails.generateAsync(
            targetVideo.path,
            position,
            dimension,
            useHwDec = false,
          )
        } catch (cancellation: CancellationException) {
          lastSolidBitmap?.takeUnless { it.isRecycled }?.recycle()
          throw cancellation
        } catch (_: Exception) {
          continue
        } ?: continue

      if (mode == ThumbnailMode.Smart && isMostlySolidThumbnail(bitmap)) {
        lastSolidBitmap?.takeUnless { it.isRecycled }?.recycle()
        lastSolidBitmap = bitmap
        continue
      }

      lastSolidBitmap?.takeUnless { it.isRecycled }?.recycle()
      return rotateNativeThumbnail(video, bitmap)
    }

    return lastSolidBitmap?.let { rotateNativeThumbnail(video, it) }
  }

  private suspend fun rotateNativeThumbnail(
    video: Video,
    bitmap: Bitmap,
  ): Bitmap {
    val rotation =
      try {
        app.gyrolet.mpvrx.utils.media.MediaInfoOps
          .getRotation(context, video.uri, video.displayName)
      } catch (cancellation: CancellationException) {
        bitmap.takeUnless { it.isRecycled }?.recycle()
        throw cancellation
      } catch (_: Exception) {
        0
      }
    if (rotation == 0) return bitmap

    val rotated =
      Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        Matrix().apply { postRotate(rotation.toFloat()) },
        true,
      )
    if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return rotated
  }

  private fun extractLocalVideoFrame(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        setLocalDataSource(retriever, video)
        extractFrameWithStrategy(
          retriever = retriever,
          strategy =
            browserPreferences.thumbnailMode.get().toThumbnailStrategy(
              browserPreferences.thumbnailFramePosition.get(),
            ),
          targetWidth = widthPx.takeIf { it > 0 },
          targetHeight = heightPx.takeIf { it > 0 },
          videoPath = canonicalLocalPath(video),
        )
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private fun setLocalDataSource(
    retriever: MediaMetadataRetriever,
    video: Video,
  ) {
    val canonicalPath = canonicalLocalPath(video)
    when {
      canonicalPath.isNotBlank() && !canonicalPath.contains("://") && File(canonicalPath).exists() ->
        retriever.setDataSource(canonicalPath)
      video.path.isNotBlank() && !video.path.contains("://") -> retriever.setDataSource(video.path)
      else -> retriever.setDataSource(context, video.uri)
    }
  }

  private fun readBitmapFromDisk(
    key: String,
    network: Boolean,
  ): Bitmap? =
    diskCacheLock.read {
      val file = File(if (network) networkDiskDir else localDiskDir, keyToFileName(key))
      if (!file.isFile) return@read null

      runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        BitmapFactory.decodeFile(
          file.absolutePath,
          BitmapFactory.Options().apply {
            inSampleSize = calculateThumbnailSampleSize(bounds.outWidth, bounds.outHeight, thumbnailMaxSize())
            inPreferredConfig = Bitmap.Config.RGB_565
          },
        )
      }.getOrNull()
    }

  private fun writeBitmapToDisk(
    key: String,
    bitmap: Bitmap,
    network: Boolean,
  ) {
    val file = File(if (network) networkDiskDir else localDiskDir, keyToFileName(key))
    val encoded =
      runCatching {
        ByteArrayOutputStream().use { buffer ->
          if (bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, buffer)) {
            buffer.toByteArray()
          } else {
            null
          }
        }
      }.getOrNull() ?: return

    diskCacheLock.write {
      runCatching {
        FileOutputStream(file).use { output ->
          output.write(encoded)
        }
      }
    }
  }

  private fun keyToFileName(key: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(key.toByteArray())
      .joinToString("") { byte -> "%02x".format(byte) } + ".jpg"

  private fun scaleBitmap(
    bitmap: Bitmap,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap {
    if (widthPx <= 0 || heightPx <= 0 || bitmap.isRecycled) {
      return bitmap
    }

    val scale = max(widthPx / bitmap.width.toFloat(), heightPx / bitmap.height.toFloat())
    if (scale >= 1f && bitmap.width <= widthPx * 2 && bitmap.height <= heightPx * 2) {
      return bitmap
    }

    // The source bitmap is deliberately not recycled: it can still be referenced by
    // decodedDiskCache or memoryCache, and a card holding it would crash on a recycled bitmap.
    val scaledWidth = max(1, (bitmap.width * scale).roundToInt())
    val scaledHeight = max(1, (bitmap.height * scale).roundToInt())
    return try {
      Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    } catch (_: IllegalArgumentException) {
      // Bitmap was recycled between the check and the scale call
      bitmap
    }
  }

  private fun isNetworkUrl(path: String): Boolean =
    path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true) ||
      path.startsWith("rtmp://", ignoreCase = true) ||
      path.startsWith("rtsp://", ignoreCase = true) ||
      path.startsWith("ftp://", ignoreCase = true) ||
      path.startsWith("sftp://", ignoreCase = true) ||
      path.startsWith("smb://", ignoreCase = true)

  private fun isHttpUrl(path: String): Boolean =
    path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true)

  private suspend fun getOrCreateNetworkVideoThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    val jellyfinImageUrls = extractJellyfinImageUrls(video.path, maxOf(widthPx, heightPx, 400))
    for (url in jellyfinImageUrls) {
      val jellyfinBitmap = fetchHttpImage(url)
      if (jellyfinBitmap != null) {
        return scaleBitmap(jellyfinBitmap, widthPx, heightPx)
      }
    }

    if (!appearancePreferences.showNetworkThumbnails.get()) {
      return null
    }

    val strategy =
      browserPreferences.thumbnailMode.get().toThumbnailStrategy(
        browserPreferences.thumbnailFramePosition.get(),
      )

    val rotated =
      extractNetworkVideoFrame(
        url = video.path,
        strategy = strategy,
        targetWidth = widthPx.takeIf { it > 0 },
        targetHeight = heightPx.takeIf { it > 0 },
      ) ?: generateFastNetworkThumbnail(video.path, widthPx, heightPx) ?: return null

    return scaleBitmap(rotated, widthPx, heightPx)
  }

  private fun isNetworkThumbnailAllowed(path: String): Boolean =
    extractJellyfinImageUrls(path).isNotEmpty() || appearancePreferences.showNetworkThumbnails.get()

  private fun extractJellyfinImageUrls(url: String, maxWidth: Int = 400): List<String> =
    runCatching {
      val uri = Uri.parse(url)
      val pathSegments = uri.pathSegments
      val videosIndex = pathSegments.indexOf("Videos")
      val itemsIndex = pathSegments.indexOf("Items")
      val audioIndex = pathSegments.indexOf("Audio")
      val targetIndex =
        when {
          videosIndex != -1 -> videosIndex
          itemsIndex != -1 -> itemsIndex
          audioIndex != -1 -> audioIndex
          else -> -1
        }
      if (targetIndex == -1 || targetIndex + 1 >= pathSegments.size) return emptyList()
      val itemId = pathSegments[targetIndex + 1]
      val apiKey = uri.getQueryParameter("api_key") ?: uri.getQueryParameter("ApiKey")
      val scheme = uri.scheme ?: "http"
      val authority = uri.encodedAuthority ?: return emptyList()
      val subPathSegments = pathSegments.subList(0, targetIndex)
      val base =
        if (subPathSegments.isEmpty()) {
          "$scheme://$authority"
        } else {
          "$scheme://$authority/" + subPathSegments.joinToString("/")
        }
      val tokenParam = if (!apiKey.isNullOrBlank()) "&api_key=$apiKey" else ""
      listOf(
        "$base/Items/$itemId/Images/Primary?maxWidth=$maxWidth&quality=80$tokenParam",
        "$base/Items/$itemId/Images/Primary?fallback=true&maxWidth=$maxWidth&quality=80$tokenParam",
        "$base/Items/$itemId/Images/Thumb?maxWidth=$maxWidth&quality=80$tokenParam",
        "$base/Items/$itemId/Images/Backdrop/0?maxWidth=$maxWidth&quality=80$tokenParam",
      )
    }.getOrDefault(emptyList())

  private suspend fun fetchHttpImage(url: String): Bitmap? =
    withContext(Dispatchers.IO) {
      runCatching {
        val connection =
          (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "mpvRx/1.0")
          }
        if (connection.responseCode in 200..299) {
          connection.inputStream.use { stream ->
            BitmapFactory.decodeStream(stream)
          }
        } else {
          null
        }
      }.getOrNull()
    }

  private fun extractNetworkVideoFrame(
    url: String,
    strategy: ThumbnailStrategy,
    targetWidth: Int?,
    targetHeight: Int?,
  ): Bitmap? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(url, networkVideoHeaders())

        extractFrameWithStrategy(retriever, strategy, targetWidth, targetHeight)
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private fun extractFrameWithStrategy(
    retriever: MediaMetadataRetriever,
    strategy: ThumbnailStrategy,
    targetWidth: Int?,
    targetHeight: Int?,
    videoPath: String? = null,
  ): Bitmap? {
    val embeddedPicture =
      if (strategy.prefersEmbeddedPicture()) {
        EmbeddedArtworkResolver.decodeEmbeddedArtwork(videoPath, retriever)
      } else {
        null
      }
    val timeUs =
      when (strategy) {
        ThumbnailStrategy.FirstFrame -> 0L
        is ThumbnailStrategy.FrameAtPercentage -> frameTimeMicros(retriever, strategy.percentage)
        is ThumbnailStrategy.Hybrid, is ThumbnailStrategy.EmbeddedOrHybrid -> 0L
        ThumbnailStrategy.EmbeddedOrFirstFrame -> 0L
      }

    var shouldRotate = true
    val raw =
      when (strategy) {
        ThumbnailStrategy.EmbeddedOrFirstFrame ->
          embeddedPicture?.also { shouldRotate = false }
            ?: getFrameAt(retriever, timeUs, targetWidth, targetHeight)
        ThumbnailStrategy.FirstFrame -> getFrameAt(retriever, 0L, targetWidth, targetHeight)
        is ThumbnailStrategy.FrameAtPercentage -> getFrameAt(retriever, timeUs, targetWidth, targetHeight)
        is ThumbnailStrategy.Hybrid -> decodeHybridFrame(retriever, strategy.percentage, targetWidth, targetHeight)
        is ThumbnailStrategy.EmbeddedOrHybrid ->
          embeddedPicture?.also { shouldRotate = false }
            ?: decodeHybridFrame(retriever, strategy.percentage, targetWidth, targetHeight)
      } ?: return null

    return if (shouldRotate) rotateBitmapIfNeeded(retriever, raw) else raw
  }

  private fun decodeHybridFrame(
    retriever: MediaMetadataRetriever,
    percentage: Float,
    targetWidth: Int?,
    targetHeight: Int?,
  ): Bitmap? {
    val first = getFrameAt(retriever, 0L, targetWidth, targetHeight) ?: return null
    if (!isMostlySolidThumbnail(first)) return first
    first.recycle()
    return getFrameAt(
      retriever,
      frameTimeMicros(retriever, percentage),
      targetWidth,
      targetHeight,
    )
  }

  private fun getFrameAt(
    retriever: MediaMetadataRetriever,
    timeUs: Long,
    targetWidth: Int?,
    targetHeight: Int?,
  ): Bitmap? {
    val w = targetWidth ?: return retriever.getFrameAtTime(timeUs)
    val h = targetHeight ?: return retriever.getFrameAtTime(timeUs)
    if (w <= 0 || h <= 0) {
      return retriever.getFrameAtTime(timeUs)
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      runCatching {
        retriever.getScaledFrameAtTime(
          timeUs,
          MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
          w,
          h,
        )
      }.getOrNull()
        ?: retriever.getFrameAtTime(timeUs)
    } else {
      retriever.getFrameAtTime(timeUs)
    }
  }

  private fun frameTimeMicros(
    retriever: MediaMetadataRetriever,
    percentage: Float,
  ): Long {
    val durationMs =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    return (durationMs * percentage.coerceIn(0f, 1f) * 1000).toLong()
  }

  private fun rotateBitmapIfNeeded(
    retriever: MediaMetadataRetriever,
    bitmap: Bitmap,
  ): Bitmap {
    val rotation =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        ?: return bitmap
    if (rotation == 0) {
      return bitmap
    }

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap) {
      bitmap.recycle()
    }
    return rotated
  }

  private fun networkVideoHeaders(): Map<String, String> =
    mapOf(
      // Some servers refuse requests without a UA. MediaMetadataRetriever handles the rest.
      "User-Agent" to "Mozilla/5.0 (Android) mpvRx",
      "Accept" to "*/*",
    )

  /**
   * Retrieve a thumbnail for a raw network file path (for use from [NetworkVideoCard]).
   * For HTTP/HTTPS URLs, uses [MediaMetadataRetriever]'s built-in HTTP streaming.
   * For other protocols (SMB, FTP, WebDAV), uses [NetworkStreamingProxy] to create
   * a local HTTP stream and then extracts the frame.
   * Respects the [showNetworkThumbnails] preference gate.
   */
  suspend fun getThumbnailForNetworkPath(
    path: String,
    widthPx: Int,
    heightPx: Int,
    connection: NetworkConnection? = null,
    fileSize: Long = -1L,
    mimeType: String? = null,
    lastModified: Long = 0L,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      if (!appearancePreferences.showNetworkThumbnails.get()) return@withContext null

      val identity = networkThumbnailIdentity(path, connection, fileSize, lastModified)
      val memKey = networkThumbnailMemoryKey(identity, widthPx, heightPx)
      synchronized(memoryCache) { memoryCache.get(memKey) }?.let { return@withContext it }
      ongoingOperations[memKey]?.let { return@withContext it.await() }

      val candidate =
        async(start = CoroutineStart.LAZY) {
          if (!isHttpUrl(path)) {
            return@async getNonHttpNetworkThumbnail(
              path = path,
              connection = connection,
              widthPx = widthPx,
              heightPx = heightPx,
              identity = identity,
              fileSize = fileSize,
              mimeType = mimeType,
              lastModified = lastModified,
            )
          }

          if (hasRecentNetworkThumbnailFailure(identity)) {
            android.util.Log.d("ThumbnailRepository", "Skipping network thumbnail (previously failed): $path")
            return@async null
          }

          val diskKey = networkThumbnailDiskKey(identity)
          readBitmapFromDisk(diskKey, network = true)?.let { bitmap ->
            val scaled = scaleBitmap(bitmap, widthPx, heightPx)
            synchronized(memoryCache) { memoryCache.put(memKey, scaled) }
            return@async scaled
          }

          val strategy =
            browserPreferences.thumbnailMode.get().toThumbnailStrategy(
              browserPreferences.thumbnailFramePosition.get(),
            )
          val bitmap =
            networkGenerationSemaphore.withPermit {
              (
                extractKeyframeByIndex(path, path, connection, strategy, fileSize, lastModified)
                  ?: extractNetworkVideoFrame(
                    url = path,
                    strategy = strategy,
                    targetWidth = widthPx.takeIf { it > 0 },
                    targetHeight = heightPx.takeIf { it > 0 },
                  )
                  ?: generateFastNetworkThumbnail(path, widthPx, heightPx)
              )?.let { scaleBitmap(it, widthPx, heightPx) }
            }

          if (bitmap == null) {
            android.util.Log.w("ThumbnailRepository", "All strategies failed for network stream $path")
            networkThumbnailFailedAt[identity] = SystemClock.elapsedRealtime()
            return@async null
          }

          networkThumbnailFailedAt.remove(identity)
          writeBitmapToDisk(diskKey, bitmap, network = true)
          synchronized(memoryCache) { memoryCache.put(memKey, bitmap) }
          _thumbnailReadyKeys.tryEmit(memKey)
          bitmap
        }

      val operation =
        ongoingOperations.putIfAbsent(memKey, candidate)?.also {
          candidate.cancel()
        } ?: candidate.also { owned ->
          owned.invokeOnCompletion { ongoingOperations.remove(memKey, owned) }
          owned.start()
        }
      operation.await()
    }

  suspend fun getThumbnailForNetworkSource(
    connectionId: Long,
    path: String,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    // Tombstones included: the cache key needs only this connection's identity, and an entry whose
    // share was deleted must keep showing the frame that is already on disk.
    val connection = networkRepository.getConnectionIncludingDeleted(connectionId) ?: return null
    return getThumbnailForNetworkPath(
      path = path,
      widthPx = widthPx,
      heightPx = heightPx,
      connection = connection,
    )
  }

  private suspend fun getNonHttpNetworkThumbnail(
    path: String,
    connection: NetworkConnection?,
    widthPx: Int,
    heightPx: Int,
    identity: String,
    fileSize: Long,
    mimeType: String?,
    lastModified: Long,
  ): Bitmap? {
    if (hasRecentNetworkThumbnailFailure(identity)) {
      android.util.Log.d("ThumbnailRepository", "Skipping network thumbnail (previously failed): $path")
      return null
    }

    val memKey = networkThumbnailMemoryKey(identity, widthPx, heightPx)
    val diskKey = networkThumbnailDiskKey(identity)

    // Memory cache hit
    synchronized(memoryCache) { memoryCache.get(memKey) }?.let { return it }

    // Disk cache hit
    readBitmapFromDisk(diskKey, network = true)?.let { bitmap ->
      val scaled = scaleBitmap(bitmap, widthPx, heightPx)
      synchronized(memoryCache) { memoryCache.put(memKey, scaled) }
      return scaled
    }

    val strategy =
      browserPreferences.thumbnailMode.get().toThumbnailStrategy(
        browserPreferences.thumbnailFramePosition.get(),
      )

    val bitmap =
      networkGenerationSemaphore.withPermit {
        (
          if (connection != null) {
            extractNetworkVideoFrameViaProxy(
              path = path,
              connection = connection,
              strategy = strategy,
              targetWidth = widthPx,
              targetHeight = heightPx,
              fileSize = fileSize,
              mimeType = mimeType,
              lastModified = lastModified,
            )
          } else {
            generateFastNetworkThumbnail(path, widthPx, heightPx)
          }
        )?.let { scaleBitmap(it, widthPx, heightPx) }
      }

    if (bitmap == null) {
      android.util.Log.w("ThumbnailRepository", "All strategies failed for network path $path")
      networkThumbnailFailedAt[identity] = SystemClock.elapsedRealtime()
      return null
    }

    networkThumbnailFailedAt.remove(identity)

    // Write to disk cache
    writeBitmapToDisk(diskKey, bitmap, network = true)

    synchronized(memoryCache) { memoryCache.put(memKey, bitmap) }
    _thumbnailReadyKeys.tryEmit(memKey)
    return bitmap
  }

  private suspend fun extractNetworkVideoFrameViaProxy(
    path: String,
    connection: NetworkConnection,
    strategy: ThumbnailStrategy,
    targetWidth: Int,
    targetHeight: Int,
    fileSize: Long,
    mimeType: String?,
    lastModified: Long,
  ): Bitmap? {
    val proxy = NetworkStreamingProxy.getInstance()
    val streamId = "thumb_${path.hashCode()}_${System.nanoTime()}"

    return try {
      val localUrl =
        proxy.registerStream(
          streamId = streamId,
          connection = connection,
          filePath = path,
          fileSize = fileSize.coerceAtLeast(-1L),
          mimeType = mimeType ?: NetworkMimeTypes.forFileName(path) ?: "application/octet-stream",
        )

      // Index-based extraction downloads only the container index and one frame, while the
      // streaming retriever below has to pull far more of the file — so try it first and keep the
      // existing chain as the fallback for every container we cannot index.
      extractKeyframeByIndex(localUrl, path, connection, strategy, fileSize, lastModified)
        ?: extractNetworkVideoFrame(
          url = localUrl,
          strategy = strategy,
          targetWidth = targetWidth.takeIf { it > 0 },
          targetHeight = targetHeight.takeIf { it > 0 },
        )
        ?: generateFastNetworkThumbnail(localUrl, targetWidth, targetHeight)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    } finally {
      proxy.unregisterStream(streamId)
    }
  }

  /**
   * Tries the keyframe index path for containers we can parse (MP4/MOV/M4V, MKV/WebM).
   *
   * [stableKey] is a durable identity rather than the loopback URL, whose port and token change on
   * every registration, so the parsed index stays reusable across sessions.
   */
  private suspend fun extractKeyframeByIndex(
    streamUrl: String,
    path: String,
    connection: NetworkConnection?,
    strategy: ThumbnailStrategy,
    fileSize: Long,
    lastModified: Long,
  ): Bitmap? {
    val extension = app.gyrolet.mpvrx.domain.cloud.cloudMediaExtension(path)
    if (!cloudKeyframes.supports(extension)) return null

    val (targetPercent, solidFallback) = keyframeTargets(strategy)
    return try {
      val result = cloudKeyframes.extract(
        streamUrl = streamUrl,
        stableKey = app.gyrolet.mpvrx.domain.cloud.cloudMediaKey(connection, path, fileSize, lastModified),
        extension = extension,
        targetPercent = targetPercent,
        solidFallbackPercent = solidFallback,
      )
      if (result != null && connection != null) {
        KoinJavaComponent.get<app.gyrolet.mpvrx.repository.CloudMetadataRepository>(app.gyrolet.mpvrx.repository.CloudMetadataRepository::class.java)
          .publish(connection.id, path, fileSize, lastModified, result.durationMs ?: 0L, result.width ?: 0, result.height ?: 0)
      }
      result?.bitmap
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Maps the configured strategy onto an extractor position, plus the retry position used when the
   * first frame is nearly a single flat colour (a black lead-in, most commonly).
   */
  private fun keyframeTargets(strategy: ThumbnailStrategy): Pair<Float, Float?> =
    when (strategy) {
      is ThumbnailStrategy.FirstFrame -> 0f to null
      is ThumbnailStrategy.FrameAtPercentage -> strategy.percentage to null
      is ThumbnailStrategy.Hybrid -> 0f to strategy.percentage
      is ThumbnailStrategy.EmbeddedOrHybrid -> 0f to strategy.percentage
      is ThumbnailStrategy.EmbeddedOrFirstFrame -> 0f to null
    }

  /** The memory-cache key used by [getThumbnailForNetworkPath]. */
  fun thumbnailKeyForNetworkPath(
    path: String,
    widthPx: Int,
    heightPx: Int,
    connection: NetworkConnection? = null,
    fileSize: Long = -1L,
    lastModified: Long = 0L,
  ): String =
    networkThumbnailMemoryKey(
      networkThumbnailIdentity(path, connection, fileSize, lastModified),
      widthPx,
      heightPx,
    )

  /**
   * Non-blocking, composition-safe peek for a network frame at any decoded size.
   *
   * Keys are looked up through the identity index instead of scanning the cache, so this stays
   * cheap enough to call while composing every visible card.
   */
  fun peekNetworkThumbnail(
    path: String,
    widthPx: Int,
    heightPx: Int,
    connection: NetworkConnection?,
    fileSize: Long = -1L,
    lastModified: Long = 0L,
  ): Bitmap? {
    val identity = networkThumbnailIdentity(path, connection, fileSize, lastModified)
    synchronized(memoryCache) { memoryCache.get(networkThumbnailKey(identity, widthPx, heightPx)) }
      ?.let { return it }
    val candidates =
      synchronized(networkKeysByIdentity) { networkKeysByIdentity[identity]?.toList() } ?: return null
    return synchronized(memoryCache) {
      candidates.firstNotNullOfOrNull { memoryCache.get(it) }
    }
  }

  private fun networkThumbnailIdentity(
    path: String,
    connection: NetworkConnection?,
    fileSize: Long,
    lastModified: Long,
  ): String = app.gyrolet.mpvrx.domain.cloud.cloudMediaKey(connection, path, fileSize, lastModified)

  private fun networkThumbnailKey(
    identity: String,
    widthPx: Int,
    heightPx: Int,
  ): String = "$identity|network|$widthPx|$heightPx|${thumbnailModeKey()}|${thumbnailQualityKey()}"

  /**
   * Builds the same key as [networkThumbnailKey] but records it against [identity] so
   * [peekNetworkThumbnail] can find the frame at another size.
   */
  private fun networkThumbnailMemoryKey(
    identity: String,
    widthPx: Int,
    heightPx: Int,
  ): String = networkThumbnailKey(identity, widthPx, heightPx).also { key ->
    synchronized(networkKeysByIdentity) {
      networkKeysByIdentity.getOrPut(identity) { LinkedHashSet() }.add(key)
    }
  }

  private fun networkThumbnailDiskKey(identity: String): String =
    "video-thumb-v3|$identity|network|${thumbnailModeKey()}|${thumbnailQualityKey()}"

  private fun hasRecentNetworkThumbnailFailure(identity: String): Boolean {
    val failedAt = networkThumbnailFailedAt[identity] ?: return false
    if (SystemClock.elapsedRealtime() - failedAt < NETWORK_THUMBNAIL_FAILURE_RETRY_MS) return true
    networkThumbnailFailedAt.remove(identity, failedAt)
    return false
  }

  /**
   * Get a thumbnail for a folder using the first video in the folder.
   * Returns null if the folder has no videos or thumbnail generation fails.
   */
  suspend fun getFolderThumbnail(
    folderId: String,
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      if (videos.isEmpty()) return@withContext null

      // Filter out network videos if network thumbnails are disabled
      val filteredVideos =
        if (appearancePreferences.showNetworkThumbnails.get()) {
          videos
        } else {
          videos.filterNot { isNetworkUrl(it.path) }
        }

      if (filteredVideos.isEmpty()) return@withContext null

      // Use the first video as the folder thumbnail
      getThumbnail(filteredVideos.first(), widthPx, heightPx)
        ?.also { frame -> folderThumbnailMemory.put(folderId, frame) }
    }

  /** Non-blocking peek of the last frame shown for [folderId]; safe to call during composition. */
  fun peekFolderThumbnail(folderId: String): Bitmap? = folderThumbnailMemory.get(folderId)

  /**
   * Last resolved frame for a folder card, so scrolling back to it paints immediately instead of
   * flashing the placeholder while the thumbnail is fetched and decoded again.
   */
  private val folderThumbnailMemory = FolderThumbnailMemory()

  private class FolderThumbnailMemory {
    private val frames = LinkedHashMap<String, Bitmap>()
    private val lock = Any()

    fun get(folderId: String): Bitmap? =
      synchronized(lock) { frames[folderId] }

    fun put(
      folderId: String,
      bitmap: Bitmap,
    ) {
      synchronized(lock) {
        frames.remove(folderId)
        frames[folderId] = bitmap
        while (frames.size > MAX_TRACKED_FOLDER_FRAMES) {
          val oldest = frames.keys.firstOrNull() ?: break
          frames.remove(oldest)
        }
      }
    }

    fun clear() {
      synchronized(lock) { frames.clear() }
    }

    private companion object {
      const val MAX_TRACKED_FOLDER_FRAMES = 32
    }
  }

  /**
   * Identity of a batch's render settings, independent of which videos happen to be visible.
   *
   * Progress is tracked per video (see [completedFolderVideoKeys]) rather than by list index, so
   * a different scroll position no longer looks like a brand new batch.
   */
  private fun folderSignature(
    widthPx: Int,
    heightPx: Int,
  ): String = "$widthPx|$heightPx|${thumbnailModeKey()}|${thumbnailQualityKey()}"

  /**
   * Per-batch state for folder thumbnail generation: the render settings the marks belong to, and
   * the per-video completion marks themselves, keyed as in [videoGroupKey]. The map is bounded to
   * the most recently used folders, so a long browsing session stays bounded.
   *
   * Keeping the signature next to the marks is what stops the two from drifting apart: a signature
   * change can then never leave marks recorded under settings nobody is using any more.
   */
  private val completedFolderVideoKeys = FolderCompletionTracker()

  private class FolderCompletionTracker {
    private class Batch(
      val signature: String,
      val marks: MutableSet<String>,
    )

    private val batches = LinkedHashMap<String, Batch>()
    private val lock = Any()

    /**
     * Selects [folderId]'s batch for [signature], creating it or resetting it when the render
     * settings changed. Returns true when the settings changed, i.e. the previous marks are void.
     */
    fun selectBatch(
      folderId: String,
      signature: String,
    ): Boolean =
      synchronized(lock) {
        // Re-insert first so the bounded map evicts the least recently used folder.
        val existing = batches.remove(folderId)
        val settingsChanged = existing == null || existing.signature != signature
        batches[folderId] =
          if (settingsChanged) {
            Batch(signature, Collections.synchronizedSet(HashSet<String>()))
          } else {
            existing!!
          }
        while (batches.size > MAX_TRACKED_FOLDER_BATCHES) {
          val oldest = batches.keys.firstOrNull() ?: break
          batches.remove(oldest)
        }
        settingsChanged
      }

    /** The mark set for [folderId]; empty when the folder has no batch. Call after [selectBatch]. */
    fun marksFor(folderId: String): MutableSet<String> =
      synchronized(lock) { batches[folderId]?.marks ?: Collections.synchronizedSet(HashSet<String>()) }

    fun clear() {
      synchronized(lock) { batches.clear() }
    }

    private companion object {
      const val MAX_TRACKED_FOLDER_BATCHES = 8
    }
  }

  // Disk-cache key per size-independent video identity. Recording it on a worker thread lets
  // composition reach [decodedDiskCache] without the filesystem access [diskCacheKey] needs.
  private val diskKeysByVideoGroup = LruCache<String, String>(2048)

  /**
   * True when this video failed to produce a frame recently enough that another attempt would
   * just repeat the same decoder work. The mark is dropped once it expires, so a transient cause
   * (storage remounted, permission granted, decoder contention) still recovers on its own.
   */
  private fun hasRecentLocalThumbnailFailure(groupKey: String): Boolean {
    val failedAt = localThumbnailFailedAt[groupKey] ?: return false
    if (SystemClock.elapsedRealtime() - failedAt < LOCAL_THUMBNAIL_FAILURE_RETRY_MS) return true
    localThumbnailFailedAt.remove(groupKey, failedAt)
    return false
  }

  /**
   * True when this video has no decoded frame in memory yet, i.e. there is still work to do.
   *
   * A frame that only exists on disk still counts as pending: [getCachedThumbnail] performs the
   * disk read, and [decodedDiskCache] makes that a one-off per process rather than a cost paid on
   * every scroll settle. Treating "on disk" as finished would leave the list with placeholders,
   * because cards only read memory and never decode by themselves.
   */
  private fun needsThumbnailWork(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Boolean {
    val sizedKey = resolvedThumbnailKeys.get(peekIdentity(video, widthPx, heightPx))
    if (sizedKey != null && synchronized(memoryCache) { memoryCache.get(sizedKey) } != null) {
      return false
    }
    val groupKeys = synchronized(thumbnailKeysByVideo) { thumbnailKeysByVideo[videoGroupKey(video)]?.toList() }
    return groupKeys == null || synchronized(memoryCache) { groupKeys.none { memoryCache.get(it) != null } }
  }

  private fun thumbnailModeKey(): String =
    browserPreferences.thumbnailMode.get().thumbnailModeCacheKey(browserPreferences.thumbnailFramePosition.get())

  private fun thumbnailQualityKey(): String = browserPreferences.thumbnailQuality.get().name

  private fun thumbnailMaxSize(): Int = browserPreferences.thumbnailQuality.get().maxSizePx

  private fun resolveLocalGenerationParallelism(): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return when {
      activityManager?.isLowRamDevice == true || processorCount <= 2 -> 1
      processorCount <= 4 -> 2
      processorCount <= 6 -> 3
      else -> 4
    }
  }

  private suspend fun generateFastNetworkThumbnail(
    path: String,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    try {
      FastThumbnails.generateAsync(
        path,
        10.0,
        maxOf(widthPx, heightPx, MAX_THUMBNAIL_SIZE).coerceAtMost(thumbnailMaxSize()),
        useHwDec = false,
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    }
}
