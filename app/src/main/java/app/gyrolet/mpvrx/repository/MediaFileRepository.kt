/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.domain.browser.FileSystemItem
import app.gyrolet.mpvrx.domain.browser.PathComponent
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.HiddenScanScope
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import app.gyrolet.mpvrx.utils.storage.FileFilterUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.FolderViewScanner
import app.gyrolet.mpvrx.utils.storage.INTERNAL_STORAGE_LABEL
import app.gyrolet.mpvrx.utils.storage.MediaScanOptions
import app.gyrolet.mpvrx.utils.storage.NoMediaPathFilter
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import app.gyrolet.mpvrx.utils.storage.TreeViewScanner
import app.gyrolet.mpvrx.utils.storage.leafStorageName
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import app.gyrolet.mpvrx.utils.storage.mediaPathKey
import app.gyrolet.mpvrx.utils.storage.storagePathKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.Locale

/**
 * Unified repository for ALL media file operations
 * Consolidates FileSystemRepository, VideoRepository functionality
 *
 * This repository handles:
 * - Video folder discovery (album view)
 * - File system browsing (tree view)
 * - Video file listing
 * - Metadata extraction
 * - Path operations
 * - Storage volume detection
 */
object MediaFileRepository : KoinComponent {
  private const val TAG = "MediaFileRepository"
  private val foldersPreferences: FoldersPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val database: MpvRxDatabase by inject()

  /** Public so a persisted listing can be namespaced by the options that produced it. */
  fun currentScanOptions(
    includeAudioOverride: Boolean? = null,
    scope: HiddenScanScope = HiddenScanScope.FOLDERS,
  ): MediaScanOptions =
    MediaScanOptions(
      // Scoped builds discover unindexed folders only through explicit user-granted SAF trees.
      includeNoMediaFolders = !BuildConfig.SCOPED_STORAGE_ONLY &&
        foldersPreferences.includeNoMediaFolders.get() && foldersPreferences.isModeScanEnabled(scope) &&
        foldersPreferences.scanMarkedFolders(scope),
      includeDotFolders = !BuildConfig.SCOPED_STORAGE_ONLY &&
        foldersPreferences.includeNoMediaFolders.get() && foldersPreferences.isModeScanEnabled(scope) &&
        foldersPreferences.scanDotFolders(scope),
      hiddenFolderMarkerNames = foldersPreferences.hiddenFolderMarkerNames.get(),
      ignoredHiddenMarkerNames = foldersPreferences.ignoredMarkers(scope),
      includeAudio = includeAudioOverride ?: browserPreferences.includeAudioBrowser.get(),
      minimumAudioDurationSeconds = browserPreferences.minimumAudioDurationSeconds.get(),
    )

  private data class TreeViewNewBadgeParams(
    val showNewLabels: Boolean,
    val thresholdDays: Int,
    val playedMediaTitles: Set<String>,
    val newLabelOverrides: Map<String, Boolean>,
  )

  private suspend fun getTreeViewNewBadgeParams(): TreeViewNewBadgeParams {
    val showNewLabels = appearancePreferences.showUnplayedOldVideoLabel.get()
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val states = if (showNewLabels) playbackStateRepository.getAllPlaybackStates() else emptyList()
    val playedMediaTitles = states.filter { it.hasBeenWatched }.mapTo(mutableSetOf()) { it.mediaTitle }
    val overrides = states.mapNotNull { state -> state.newLabelOverride?.let { state.mediaTitle to it } }.toMap()

    return TreeViewNewBadgeParams(showNewLabels, thresholdDays, playedMediaTitles, overrides)
  }

  /**
   * Clears all caches
   * Call this when media library changes are detected or when forcing a hard refresh
   */
  fun clearCache() {
    Log.d(TAG, "Clearing all caches (FolderViewScanner + TreeViewScanner)")
    FolderViewScanner.clearCache()
    TreeViewScanner.clearCache()
  }

  /** Invalidates only the album MediaStore snapshot, preserving tree and filesystem indexes. */
  fun invalidateFolderCache() {
    FolderViewScanner.clearCache()
  }

  fun invalidateTreeCache() {
    TreeViewScanner.clearCache()
  }

  // =============================================================================
  // FOLDER OPERATIONS (Album View)
  // =============================================================================

  suspend fun getScopedHiddenVideoFolders(
    context: Context,
    includeAudioOverride: Boolean? = null,
    scope: HiddenScanScope = HiddenScanScope.FOLDERS,
  ): List<VideoFolder> =
    if (BuildConfig.SCOPED_STORAGE_ONLY && foldersPreferences.hiddenScanEnabled(scope)) {
      ScopedHiddenFolderScanner.getFolders(
        context,
        foldersPreferences.scopedHiddenFolderUris.get(),
        includeAudioOverride ?: browserPreferences.includeAudioBrowser.get(),
        includeMarkedFolders = foldersPreferences.scanMarkedFolders(scope),
        includeDotFolders = foldersPreferences.scanDotFolders(scope),
        markerNames = foldersPreferences.hiddenFolderMarkerNames.get(),
        ignoredMarkerNames = foldersPreferences.ignoredMarkers(scope),
      )
    } else {
      emptyList()
    }

  /**
   * Scans all storage volumes to find all folders containing videos
   */
  suspend fun getAllVideoFolders(
    context: Context,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
    scope: HiddenScanScope = HiddenScanScope.FOLDERS,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      try {
        val mediaStoreFolders =
          FolderViewScanner.getAllVideoFolders(
            context,
            currentScanOptions(includeAudioOverride, scope),
            forceFileSystemCheck,
          )
        val indexedFolders =
          FolderViewScanner.getIndexedNoMediaFolders(
            currentScanOptions(includeAudioOverride, scope),
            database.directoryScanDao(),
          )
        val localFolders = mediaStoreFolders + indexedFolders
        val scopedFolders = ScopedHiddenFolderScanner.withoutLocalDuplicates(
          localFolders,
          getScopedHiddenVideoFolders(context, includeAudioOverride, scope),
        )
        (localFolders + scopedFolders)
          .distinctBy { if (ScopedHiddenFolderScanner.isScopedFolder(it.path)) it.path else it.path.lowercase(Locale.ROOT) }
          .sortedBy { it.name.lowercase(Locale.getDefault()) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error scanning for video folders", e)
        emptyList()
      }
    }

  /** Fast phase: MediaStore folders plus any granted Play Store folders. Other hidden folders arrive through the incremental flow below. */
  suspend fun getAllVideoFoldersFast(
    context: Context,
    onProgress: ((Int) -> Unit)? = null,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
    onSnapshot: (suspend (List<VideoFolder>) -> Unit)? = null,
    scope: HiddenScanScope = HiddenScanScope.FOLDERS,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      val options = currentScanOptions(includeAudioOverride, scope)
      val folders = FolderViewScanner
        .getAllVideoFolders(
          context = context,
          options = options,
          forceFileSystemCheck = forceFileSystemCheck,
          onSnapshot = onSnapshot,
        )
      val scopedFolders = ScopedHiddenFolderScanner.withoutLocalDuplicates(
        folders,
        getScopedHiddenVideoFolders(context, includeAudioOverride, scope),
      )
      (folders + scopedFolders)
        .distinctBy { if (ScopedHiddenFolderScanner.isScopedFolder(it.path)) it.path else it.path.lowercase(Locale.ROOT) }
        .sortedBy { it.name.lowercase(Locale.getDefault()) }
        .also { onProgress?.invoke(it.size) }
    }

  suspend fun getIndexedNoMediaFolders(): List<VideoFolder> =
    FolderViewScanner.getIndexedNoMediaFolders(currentScanOptions(), database.directoryScanDao())

  /** True when [path] is a hidden folder the scan indexed in a standard build. Play Store folders are separate. */
  suspend fun isIndexedHiddenFolder(path: String): Boolean {
    if (ScopedHiddenFolderScanner.isScopedFolder(path)) return false
    val key = storagePathKey(path) ?: return false
    return getIndexedNoMediaFolders().any { storagePathKey(it.path) == key }
  }

  /** The hidden roots currently tracked by Folder mode, independent of the Library tab. */
  suspend fun getIndexedNoMediaRootPaths(): List<String> =
    database.directoryScanDao().getNoMediaRoots(currentScanOptions().cacheKey)

  /**
   * Scans MediaStore for all audio (music) files and groups them by parent folder.
   * Powers the dedicated Music tab so audio is kept separate from the video browser.
   */
  suspend fun getAllAudioFolders(
    context: Context,
    minimumAudioDurationSeconds: Int = 0,
    onSnapshot: (suspend (List<VideoFolder>) -> Unit)? = null,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      val aggregates = linkedMapOf<String, AudioFolderAggregate>()
      val publisher =
        ProgressiveResultsPublisher(onSnapshot, snapshot = {
          aggregates.values.map { agg ->
            VideoFolder(
              bucketId = agg.path,
              name = leafName(agg.path),
              path = agg.path,
              videoCount = agg.count,
              totalSize = agg.size,
              totalDuration = agg.duration,
              lastModified = agg.lastModified,
            )
          }
        })
      try {
        val projection =
          arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
          )
        context.contentResolver
          .query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null,
          )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
              currentCoroutineContext().ensureActive()
              val path = cursor.getString(dataColumn)
              if (path == null) continue
              val file = File(path)
              // On Android 10+ the file may not be directly accessible via the file system
              // even though it exists in MediaStore. Only skip if the path is invalid.
              val parentPath = file.parent ?: continue
              if (app.gyrolet.mpvrx.domain.audiobook.AudiobookMarkerUtils.isAudiobookPath(parentPath)) continue
              val durationMs = cursor.getLong(durationColumn)
              if (minimumAudioDurationSeconds > 0 && durationMs / 1000 < minimumAudioDurationSeconds) continue
              val key = normalizeAudioFolderKey(parentPath)
              val aggregate = aggregates.getOrPut(key) { AudioFolderAggregate(path = parentPath) }
              aggregate.size += cursor.getLong(sizeColumn)
              aggregate.duration += durationMs
              aggregate.lastModified = maxOf(aggregate.lastModified, cursor.getLong(dateColumn))
              aggregate.count += 1
              publisher.publishIfNeeded()
            }
          }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error scanning for audio folders", e)
        // Best-effort callers take what was aggregated; a caller watching a scan cannot tell an
        // empty music library from a failed query, so it is told instead.
        if (onSnapshot != null) throw e
      }
      publisher.publishIfNeeded(force = true)
      val indexedFolders = aggregates.values
        .map { agg ->
          VideoFolder(
            bucketId = agg.path,
            name = leafName(agg.path),
            path = agg.path,
            videoCount = agg.count,
            totalSize = agg.size,
            totalDuration = agg.duration,
            lastModified = agg.lastModified,
          )
        }
      val selectedFolders = if (BuildConfig.SCOPED_STORAGE_ONLY && foldersPreferences.includeNoMediaFolders.get()) {
        ScopedHiddenFolderScanner.getFolders(
          context,
          foldersPreferences.scopedHiddenFolderUris.get(),
          includeAudio = true,
          audioOnly = true,
        )
      } else {
        emptyList()
      }
      (indexedFolders + ScopedHiddenFolderScanner.withoutLocalDuplicates(indexedFolders, selectedFolders))
        .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

  /**
   * Searches MediaStore audio by title/artist/album/display name for the Music tab search.
   */
  suspend fun searchAudio(
    context: Context,
    query: String,
    limit: Int = 50,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val results = mutableListOf<Video>()
      try {
        val like = "%${query.trim()}%"
        val selection =
          "${MediaStore.Audio.Media.TITLE} LIKE ? OR " +
            "${MediaStore.Audio.Media.ARTIST} LIKE ? OR " +
            "${MediaStore.Audio.Media.ALBUM} LIKE ? OR " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
        val projection =
          arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
          )
        context.contentResolver
          .query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(like, like, like, like),
            "${MediaStore.Audio.Media.TITLE} ASC",
          )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val displayColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            var guard = 0
            while (cursor.moveToNext() && guard < limit) {
              guard++
              val path = cursor.getString(dataColumn)?.takeIf { it.isNotBlank() } ?: continue
              val file = File(path)
              if (!BuildConfig.SCOPED_STORAGE_ONLY && !file.exists()) continue
              if (app.gyrolet.mpvrx.domain.audiobook.AudiobookMarkerUtils.isAudiobookPath(path)) continue
              val durationMs = cursor.getLong(durationColumn)
              val title = cursor.getString(titleColumn) ?: file.nameWithoutExtension
              val displayName = cursor.getString(displayColumn) ?: file.name
              val folderPath = file.parent ?: ""
              val folderName = file.parentFile?.name ?: ""
              results +=
                Video(
                  id = path.hashCode().toLong(),
                  title = title,
                  displayName = displayName,
                  path = path,
                  uri = if (BuildConfig.SCOPED_STORAGE_ONLY) {
                    Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn).toString())
                  } else {
                    Uri.fromFile(file)
                  },
                  duration = durationMs,
                  durationFormatted = VideoScanUtils.formatDuration(durationMs),
                  size = cursor.getLong(sizeColumn),
                  sizeFormatted = VideoScanUtils.formatFileSize(cursor.getLong(sizeColumn)),
                  dateModified = cursor.getLong(dateColumn),
                  dateAdded = cursor.getLong(dateColumn),
                  mimeType = FileTypeUtils.getMimeTypeFromExtension(file.extension.lowercase()),
                  bucketId = folderPath,
                  bucketDisplayName = folderName,
                  width = 0,
                  height = 0,
                  fps = 0f,
                  resolution = "",
                  isAudio = true,
                )
            }
          }
      } catch (e: Exception) {
        Log.e(TAG, "Error searching audio", e)
      }
      results
    }

  private data class AudioFolderAggregate(
    val path: String,
    var size: Long = 0L,
    var duration: Long = 0L,
    var lastModified: Long = 0L,
    var count: Int = 0,
  )

  private fun normalizeAudioFolderKey(path: String): String =
    path.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

  private fun leafName(path: String): String = leafStorageName(path)

  fun scanNoMediaFoldersIncrementally(
    context: Context,
    forceDiscovery: Boolean = false,
  ): Flow<List<VideoFolder>> =
    FolderViewScanner.scanNoMediaFoldersIncrementally(
      context = context,
      options = currentScanOptions(),
      dao = database.directoryScanDao(),
      forceDiscovery = forceDiscovery,
    )

  // =============================================================================
  // VIDEO FILE OPERATIONS
  // =============================================================================

  /**
   * Gets all videos in a specific folder
   * @param bucketId Folder path
   * @param onSnapshot invoked with this folder's contents as they are discovered
   */
  suspend fun getVideosInFolder(
    context: Context,
    bucketId: String,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
    onSnapshot: (suspend (List<Video>) -> Unit)? = null,
    scope: HiddenScanScope = HiddenScanScope.FOLDERS,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      try {
        if (BuildConfig.SCOPED_STORAGE_ONLY &&
          foldersPreferences.hiddenScanEnabled(scope) &&
          ScopedHiddenFolderScanner.isGrantedFolder(bucketId, foldersPreferences.scopedHiddenFolderUris.get())
        ) {
          ScopedHiddenFolderScanner.getVideos(
            context,
            bucketId,
            includeAudioOverride ?: browserPreferences.includeAudioBrowser.get(),
            foldersPreferences.scopedHiddenFolderUris.get(),
          ).filter { video ->
            // SAF exposes files invisible to MediaStore, including dot-prefixed media.
            // Apply the same per-view filename policy after reading the selected grant.
            foldersPreferences.scanDotFolders(scope) || !video.displayName.startsWith(".")
          }
        } else if (ScopedHiddenFolderScanner.isScopedFolder(bucketId)) {
          emptyList()
        } else {
          VideoScanUtils.getVideosInFolder(
            context,
            bucketId,
            currentScanOptions(includeAudioOverride, scope),
            forceFileSystemCheck,
            onSnapshot,
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error getting videos for bucket $bucketId", e)
        // A best-effort caller wants the folders that did work. A caller watching a progressive
        // scan cannot tell an empty folder from a broken one, so it is told instead.
        if (onSnapshot != null) throw e
        emptyList()
      }
    }

  /**
   * Folders scanned at once. Every bucket costs a MediaStore query plus, for hidden folders, a
   * native metadata pass per file, so they are resolved concurrently in bounded chunks rather
   * than one after another.
   */
  private const val BUCKET_SCAN_CHUNK_SIZE = 8

  /**
   * Gets videos from multiple folders
   * Shows all videos including hidden ones.
   *
   * @param onSnapshot invoked with the videos resolved so far, so callers can paint before the whole
   *   library is listed. Folders are scanned concurrently, so this arrives from several coroutines
   *   at once: the accumulator is guarded, and the callback is always invoked outside that guard
   *   because it suspends.
   */
  suspend fun getVideosForBuckets(
    context: Context,
    bucketIds: Set<String>,
    includeAudioOverride: Boolean? = null,
    onSnapshot: (suspend (List<Video>) -> Unit)? = null,
    scope: HiddenScanScope = HiddenScanScope.FOLDERS,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val result = linkedMapOf<String, Video>()
      val resultLock = Any()

      fun absorb(media: List<Video>) {
        for (item in media) {
          // A granted document and its MediaStore row are the same file; key both by real path.
          val key = if (BuildConfig.SCOPED_STORAGE_ONLY && ScopedHiddenFolderScanner.isScopedFolder(item.path)) {
            ScopedHiddenFolderScanner.localPathOf(item.uri)?.let(::mediaPathKey)
              ?: ScopedHiddenFolderScanner.documentKey(item.uri)
          } else mediaPathKey(item.path) ?: item.path
          val existing = result[key]
          if (existing == null || shouldReplaceMedia(existing, item)) {
            result[key] = item
          }
        }
      }

      for (chunk in bucketIds.chunked(BUCKET_SCAN_CHUNK_SIZE)) {
        currentCoroutineContext().ensureActive()
        val resolved =
          coroutineScope {
            chunk
              .map { id ->
                async(Dispatchers.IO) {
                  try {
                    getVideosInFolder(
                      context = context,
                      bucketId = id,
                      includeAudioOverride = includeAudioOverride,
                      scope = scope,
                      // Republish a folder's own progress as it arrives rather than only once the
                      // folder is finished, so one slow folder does not hold the list back.
                      onSnapshot =
                        if (onSnapshot == null) {
                          null
                        } else { partial ->
                          val running =
                            synchronized(resultLock) {
                              absorb(partial)
                              result.values.toList()
                            }
                          onSnapshot(running)
                        },
                    )
                  } catch (cancellation: CancellationException) {
                    // Never absorbed: swallowing this would keep a superseded scan alive.
                    throw cancellation
                  } catch (error: Exception) {
                    // Per folder, not per library. A removed SD card or a directory that lost its
                    // permission is expected noise, and one of those emptying the whole list would
                    // be far worse than a library that is missing a folder.
                    Log.w(TAG, "Unable to scan folder $id", error)
                    emptyList()
                  }
                }
              }.awaitAll()
          }
        val running =
          synchronized(resultLock) {
            absorb(resolved.flatten())
            result.values.toList()
          }
        onSnapshot?.invoke(running)
      }

      synchronized(resultLock) { result.values.toList() }
    }

  private fun shouldReplaceMedia(
    existing: Video,
    candidate: Video,
  ): Boolean {
    val extensionIsAudio = FileTypeUtils.isAudioFile(File(candidate.path))
    val existingClassificationIsCorrect = existing.isAudio == extensionIsAudio
    val candidateClassificationIsCorrect = candidate.isAudio == extensionIsAudio
    if (existingClassificationIsCorrect != candidateClassificationIsCorrect) {
      return candidateClassificationIsCorrect
    }
    if ((existing.duration > 0L) != (candidate.duration > 0L)) return candidate.duration > 0L
    if ((existing.size > 0L) != (candidate.size > 0L)) return candidate.size > 0L
    return candidate.dateModified > existing.dateModified
  }

  /**
   * Creates Video objects from a list of files
   */
  suspend fun getVideosFromFiles(
    context: Context,
    files: List<File>,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      files.mapNotNull { file ->
        try {
          val folderPath = file.parent ?: ""
          val folderName = file.parentFile?.name ?: ""
          createVideoFromFile(context, file, folderPath, folderName)
        } catch (e: Exception) {
          Log.w(TAG, "Error creating video from file: ${file.absolutePath}", e)
          null
        }
      }
    }

  /**
   * Creates a Video object from a file with full metadata extraction
   */
  private suspend fun createVideoFromFile(
    context: Context,
    file: File,
    bucketId: String,
    bucketDisplayName: String,
  ): Video {
    val path = file.absolutePath
    val displayName = file.name
    val title = file.nameWithoutExtension
    val dateModified = file.lastModified() / 1000

    val extension = file.extension.lowercase()
    val mimeType = FileTypeUtils.getMimeTypeFromExtension(extension)
    val uri = Uri.fromFile(file)

    // Extract metadata directly (no cache)
    var size = file.length()
    var duration = 0L
    var width = 0
    var height = 0
    var fps = 0f
    var hasEmbeddedSubtitles = false
    var subtitleCodec = ""

    // Extract metadata using MediaInfo
    MediaInfoOps.extractBasicMetadata(context, uri, displayName).onSuccess { metadata ->
      if (metadata.sizeBytes > 0) size = metadata.sizeBytes
      duration = metadata.durationMs
      width = metadata.width
      height = metadata.height
      fps = metadata.fps
      hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles
      subtitleCodec = metadata.subtitleCodec
    }

    return Video(
      id = path.hashCode().toLong(),
      title = title,
      displayName = displayName,
      path = path,
      uri = uri,
      duration = duration,
      durationFormatted = VideoScanUtils.formatDuration(duration),
      size = size,
      sizeFormatted = VideoScanUtils.formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateModified,
      mimeType = mimeType,
      bucketId = bucketId,
      bucketDisplayName = bucketDisplayName,
      width = width,
      height = height,
      fps = fps,
      resolution = VideoScanUtils.formatResolutionWithFps(width, height, fps),
      hasEmbeddedSubtitles = hasEmbeddedSubtitles,
      subtitleCodec = subtitleCodec,
    )
  }

  private fun createVideoFromFileWithMetadata(
    file: File,
    bucketId: String,
    bucketDisplayName: String,
    metadata: MediaInfoOps.VideoMetadata?,
  ): Video {
    val path = file.absolutePath
    val displayName = file.name
    val title = file.nameWithoutExtension
    val dateModified = file.lastModified() / 1000

    val extension = file.extension.lowercase()
    val mimeType = FileTypeUtils.getMimeTypeFromExtension(extension)
    val uri = Uri.fromFile(file)

    // Use pre-fetched metadata
    var size = file.length()
    var duration = 0L
    var width = 0
    var height = 0
    var fps = 0f

    metadata?.let {
      if (it.sizeBytes > 0) size = it.sizeBytes
      duration = it.durationMs
      width = it.width
      height = it.height
      fps = it.fps
    }
    val hasEmbeddedSubtitles = metadata?.hasEmbeddedSubtitles ?: false
    val subtitleCodec = metadata?.subtitleCodec ?: ""

    return Video(
      id = path.hashCode().toLong(),
      title = title,
      displayName = displayName,
      path = path,
      uri = uri,
      duration = duration,
      durationFormatted = VideoScanUtils.formatDuration(duration),
      size = size,
      sizeFormatted = VideoScanUtils.formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateModified,
      mimeType = mimeType,
      bucketId = bucketId,
      bucketDisplayName = bucketDisplayName,
      width = width,
      height = height,
      fps = fps,
      resolution = VideoScanUtils.formatResolutionWithFps(width, height, fps),
      hasEmbeddedSubtitles = hasEmbeddedSubtitles,
      subtitleCodec = subtitleCodec,
    )
  }


  /**
   * Every video in the library, listed folder by folder.
   *
   * Deliberately not "list the folders, then list their videos". The first phase is what decides how
   * long the user waits, because nothing can be shown until the whole folder list exists. Instead
   * each folder is scanned the moment it is discovered, so the first files appear while the folder
   * scan is still running.
   *
   * @param onSnapshot invoked with the library as it accumulates.
   */
  suspend fun getAllVideos(
    context: Context,
    includeAudioOverride: Boolean? = null,
    onSnapshot: (suspend (List<Video>) -> Unit)? = null,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val videos = linkedMapOf<String, Video>()
      val videosLock = Any()
      val scanned = hashSetOf<String>()
      val pending = ArrayDeque<String>()

      suspend fun publishRunning() {
        val running = synchronized(videosLock) { videos.values.toList() }
        onSnapshot?.invoke(running)
      }

      // Buckets still waiting for a scan slot. Drained in chunks so folders are scanned in parallel
      // the way a whole-library scan always has, while discovery stays ahead of it.
      suspend fun drain(force: Boolean) {
        while (pending.size >= BUCKET_SCAN_CHUNK_SIZE || (force && pending.isNotEmpty())) {
          val chunk = pending.take(BUCKET_SCAN_CHUNK_SIZE)
          repeat(BUCKET_SCAN_CHUNK_SIZE) { pending.removeFirst() }
          getVideosForBuckets(
            context = context,
            bucketIds = chunk.toSet(),
            includeAudioOverride = includeAudioOverride,
            scope = HiddenScanScope.LIBRARY,
            // Always wired, because this is what fills [videos]; publishing is what makes it
            // optional. What comes back covers this chunk only, so it is merged into the running
            // total before the consumer hears about it: publishing it directly would make the list
            // shrink every time a chunk finished.
            onSnapshot = { chunkVideos ->
              synchronized(videosLock) {
                chunkVideos.forEach { videos[mediaPathKey(it.path) ?: it.path] = it }
              }
              publishRunning()
            },
          )
        }
      }

      suspend fun scan(found: List<VideoFolder>) {
        for (folder in found) {
          // The MediaStore phase and the indexed hidden-folder phase report the same folder, and
          // the hidden pass is not exhaustive; scanning one twice would double its cost.
          if (scanned.add(folder.bucketId.lowercase(Locale.ROOT))) {
            pending.addLast(folder.bucketId)
          }
        }
        drain(force = false)
      }

      scan(
        getAllVideoFoldersFast(
          context = context,
          includeAudioOverride = includeAudioOverride,
          scope = HiddenScanScope.LIBRARY,
          // Scan each folder while the folder scan that found it is still running.
          onSnapshot = if (onSnapshot == null) null else { found -> scan(found) },
        ),
      )
      scan(
        FolderViewScanner.getIndexedNoMediaFolders(
          currentScanOptions(includeAudioOverride),
          database.directoryScanDao(),
        ),
      )
      drain(force = true)
      publishRunning()
      synchronized(videosLock) { videos.values.toList() }
    }

  // =============================================================================
  // FILE SYSTEM BROWSING (Tree View)
  // =============================================================================

  /**
   * Parses a path into breadcrumb components
   */
  fun getPathComponents(path: String): List<PathComponent> {
    if (path.isBlank()) return emptyList()

    val components = mutableListOf<PathComponent>()
    val normalizedPath = path.trimEnd('/')
    val parts = normalizedPath.split("/").filter { it.isNotEmpty() }

    components.add(PathComponent("Root", "/"))

    var currentPath = ""
    for (part in parts) {
      currentPath += "/$part"
      components.add(PathComponent(leafStorageName(currentPath), currentPath))
    }

    return components
  }

  /**
   * Scans a directory and returns its contents (folders and video files)
   * OPTIMIZED: Uses UnifiedMediaScanner for fast, consistent results
   * @param showAllFileTypes If true, shows all files. If false, shows only videos.
   * @param useFastCount If true, uses fast shallow counting (immediate children only). If false, uses deep recursive counting.
   * @param onSnapshot invoked with the directory's contents as they are found: the subfolders first,
   *   since those come from a cache, then each video as the scan reaches it.
   */
  suspend fun scanDirectory(
    context: Context,
    path: String,
    showAllFileTypes: Boolean = false,
    useFastCount: Boolean = false,
    forceFileSystemCheck: Boolean = false,
    onSnapshot: (suspend (List<FileSystemItem>) -> Unit)? = null,
  ): Result<List<FileSystemItem>> =
    withContext(Dispatchers.IO) {
      try {
        val scanOptions = currentScanOptions(scope = HiddenScanScope.TREE)
        val directory = File(path)
        val indexedDirectory = if (BuildConfig.SCOPED_STORAGE_ONLY) {
          TreeViewScanner.getFolderDataRecursive(context, path, scanOptions, forceFileSystemCheck)
        } else {
          null
        }

        // Validation checks
        if (!directory.exists() && indexedDirectory == null) {
          return@withContext Result.failure(Exception("Directory does not exist: $path"))
        }

        if (!BuildConfig.SCOPED_STORAGE_ONLY && !directory.canRead()) {
          return@withContext Result.failure(Exception("Cannot read directory: $path"))
        }

        if (!directory.isDirectory && indexedDirectory == null) {
          return@withContext Result.failure(Exception("Path is not a directory: $path"))
        }

        val items = mutableListOf<FileSystemItem>()

        // Fast first pass, and only when someone is watching.
        //
        // The two passes below are both slow: the tree index is a recursive walk, and the video
        // scan is a MediaStore query plus metadata. Neither is needed to know that a directory
        // contains "Movies" and "Recordings", which is what listFiles answers in one read. Without
        // this a launch into tree mode shows nothing until the whole tree has been indexed.
        //
        // Filtered exactly as the authoritative pass filters, so an entry that appears here is an
        // entry that will still be there when the real result replaces this list.
        if (onSnapshot != null) {
          val noMediaPathFilter = NoMediaPathFilter(scanOptions)
          val quick = mutableListOf<FileSystemItem>()
          for (file in directory.listFiles().orEmpty()) {
            currentCoroutineContext().ensureActive()
            when {
              file.isDirectory -> {
                if (!FileFilterUtils.shouldSkipFolder(file, scanOptions, noMediaPathFilter)) {
                  quick.add(
                    FileSystemItem.Folder(
                      name = file.name,
                      path = file.absolutePath,
                      lastModified = file.lastModified(),
                    ),
                  )
                }
              }
              file.isFile -> {
                if (FileFilterUtils.shouldSkipFile(file, scanOptions, noMediaPathFilter)) {
                  continue
                }
                if (!FileTypeUtils.isSupportedMediaFile(file, scanOptions)) continue
                val isAudio = FileTypeUtils.isAudioFile(file)
                val durationMs = if (isAudio) FileTypeUtils.getDurationMs(file) else 0L
                if (isAudio && durationMs / 1000 < scanOptions.minimumAudioDurationSeconds) continue
                quick.add(
                  toVideoItem(
                    createVideoFromFileWithMetadata(
                      file = file,
                      bucketId = directory.absolutePath,
                      bucketDisplayName = directory.name,
                      metadata = null,
                    ).copy(isAudio = isAudio),
                  ),
                )
              }
            }
          }
          if (quick.isNotEmpty()) onSnapshot(quick)
        }

        // Get folders using TreeViewScanner (instant from cache)
        val (showNewLabels, thresholdDays, playedMediaTitles, newLabelOverrides) = getTreeViewNewBadgeParams()
        val folders =
          TreeViewScanner.getFoldersInDirectory(
            context = context,
            parentPath = path,
            options = scanOptions,
            forceFileSystemCheck = forceFileSystemCheck,
            playedMediaTitles = playedMediaTitles,
            showNewLabels = showNewLabels,
            thresholdDays = thresholdDays,
            maxAutoFlattenLevels = browserPreferences.treeFlattenDepth.get().maxLevels,
            newLabelOverrides = newLabelOverrides,
            blacklistedPaths = foldersPreferences.blacklistedFolders.get(),
          )
        folders.forEach { folderData ->
          items.add(
            FileSystemItem.Folder(
              name = folderData.name,
              path = folderData.path,
              lastModified = File(folderData.path).lastModified(),
              videoCount = folderData.videoCount,
              totalSize = folderData.totalSize,
              totalDuration = folderData.totalDuration,
              hasSubfolders = folderData.hasSubfolders,
              newCount = folderData.newCount,
            ),
          )
        }
        // The subfolders are known before a single file has been read, and they are what a user
        // opening a directory is usually looking for. Publishing them here means the screen has
        // something to show while the files are still being found.
        onSnapshot?.invoke(items.toList())

        // Get videos in current directory.
        //
        // Best effort on purpose: a directory the scan cannot read fully still has folders worth
        // showing, and turning that into a failed Result would empty the screen over one bad
        // subdirectory. The scan reports its own failures to a caller watching it.
        val videos =
          try {
            VideoScanUtils.getVideosInFolder(
              context = context,
              folderPath = path,
              options = scanOptions,
              forceFileSystemCheck = forceFileSystemCheck,
              onSnapshot =
                if (onSnapshot == null) {
                  null
                } else { partial ->
                  // Published against the folder list rather than [items], which is still being
                  // built: appending here and rebuilding later would duplicate every video.
                  onSnapshot(items + partial.map(::toVideoItem))
                },
            )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            Log.e(TAG, "Error listing media in directory: $path", error)
            emptyList()
          }
        videos.forEach { items.add(toVideoItem(it)) }

        Result.success(items)
      } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
      } catch (e: SecurityException) {
        Log.e(TAG, "Security exception scanning directory: $path", e)
        Result.failure(Exception("Permission denied: ${e.message}"))
      } catch (e: Exception) {
        Log.e(TAG, "Error scanning directory: $path", e)
        Result.failure(e)
      }
    }

  /**
   * The scan already knows when a file changed, so the tree does not stat it again. One stat per
   * video is a round trip through FUSE on every file in the directory.
   */
  private fun toVideoItem(video: Video): FileSystemItem.VideoFile =
    FileSystemItem.VideoFile(
      name = video.displayName,
      path = video.path,
      lastModified = video.dateModified * 1000L,
      video = video,
    )

/**
 * Gets all storage volume roots with recursive video counts
 *
 * The volumes themselves are cheap to enumerate; the recursive counts are a walk of everything on
 * them. So the list is published before any counting starts, and again after each volume finishes.
 * A storage picker that waits for the counts shows nothing at all on a large library, and it does
 * not need the counts to let someone pick a volume.
 *
 * @param includeCounts set false by a caller that only needs the volumes.
 * @param onSnapshot invoked with the roots as they are known, starting with zero counts.
 */
suspend fun getStorageRoots(
    context: Context,
    forceFileSystemCheck: Boolean = false,
    includeCounts: Boolean = true,
    onSnapshot: (suspend (List<FileSystemItem.Folder>) -> Unit)? = null,
  ): List<FileSystemItem.Folder> =
    withContext(Dispatchers.IO) {
      val roots = mutableListOf<FileSystemItem.Folder>()

      try {
        // Primary storage (internal)
        val primaryStorage = Environment.getExternalStorageDirectory()
        if (BuildConfig.SCOPED_STORAGE_ONLY || (primaryStorage.exists() && primaryStorage.canRead())) {
          val primaryPath = primaryStorage.absolutePath

          roots.add(
            FileSystemItem.Folder(
              name = INTERNAL_STORAGE_LABEL,
              path = primaryPath,
              lastModified = primaryStorage.lastModified(),
              hasSubfolders = true,
            ),
          )
        }

        // External volumes (SD cards, USB OTG)
        val externalVolumes = StorageVolumeUtils.getExternalStorageVolumes(context)
        for (volume in externalVolumes) {
          val volumePath = StorageVolumeUtils.getVolumePath(volume)
          if (volumePath != null) {
            val volumeDir = File(volumePath)
            if (BuildConfig.SCOPED_STORAGE_ONLY || (volumeDir.exists() && volumeDir.canRead())) {
              val volumeName = volume.getDescription(context)

              roots.add(
                FileSystemItem.Folder(
                  name = volumeName,
                  path = volumeDir.absolutePath,
                  lastModified = volumeDir.lastModified(),
                  hasSubfolders = true,
                ),
              )
            }
          }
        }

        onSnapshot?.invoke(roots.toList())

        if (includeCounts) {
          val (showNewLabels, thresholdDays, playedMediaTitles, newLabelOverrides) = getTreeViewNewBadgeParams()
          for (index in roots.indices) {
            currentCoroutineContext().ensureActive()
            val root = roots[index]
            val folderData =
              TreeViewScanner.getFolderDataRecursive(
                context,
                root.path,
                currentScanOptions(scope = HiddenScanScope.TREE),
                forceFileSystemCheck,
                playedMediaTitles,
                showNewLabels,
                thresholdDays,
                newLabelOverrides,
              )
            if (folderData == null) continue
            roots[index] =
              root.copy(
                videoCount = folderData.videoCount,
                totalSize = folderData.totalSize,
                totalDuration = folderData.totalDuration,
                newCount = folderData.newCount,
              )
            onSnapshot?.invoke(roots.toList())
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error getting storage roots", e)
      }

      roots.toList()
    }
}
