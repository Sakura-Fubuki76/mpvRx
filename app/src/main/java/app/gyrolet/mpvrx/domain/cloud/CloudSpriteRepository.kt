package app.gyrolet.mpvrx.domain.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.gyrolet.mpvrx.database.dao.CloudMetadataDao
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class CloudSpriteSheet(val mediaId: String, val bitmap: Bitmap, val metadata: SpriteSheetMetadata)

/** Only adapts player identity/proxy and display; generation remains yume's implementation. */
class CloudSpriteRepository(
  private val context: Context,
  private val network: NetworkRepository,
  private val dao: CloudMetadataDao,
  private val keyframes: CloudKeyframeExtractor,
) {
  private companion object {
    const val TAG = "CloudSprites"
  }
  private val directory = File(context.filesDir, "cloud_sprites").apply { mkdirs() }
  private val thumbnailPixels = (160 * context.resources.displayMetrics.density).toInt().coerceIn(160, 480)
  private val generator = YumeSpriteSheetGenerator(SharedHttpClient.derive {}, thumbnailPixels)
  private val _current = MutableStateFlow<CloudSpriteSheet?>(null)
  val current = _current.asStateFlow()
  @Volatile private var wantedId: String? = null
  fun reset() { wantedId = null; _current.value = null }

  suspend fun prepare(item: PlaybackItem, durationMs: Long) = withContext(Dispatchers.IO) {
    if (durationMs <= 0) return@withContext
    wantedId = item.stableId
    val source = item.networkSource
    val connection = source?.let { network.getConnectionById(it.connectionId) }
    if (source != null && connection == null) {
      android.util.Log.i(TAG, "sprite.prepare skip=no-connection id=${item.stableId}")
      return@withContext
    }
    val entry = source?.let { dao.getItem(it.connectionId, it.relativePath) }
    val path = source?.relativePath ?: item.originalUri
    val local = if (source == null && !path.contains("://")) File(path)
      else if (path.startsWith("file://")) File(android.net.Uri.parse(path).path.orEmpty()) else null
    val identity = "sprite-yume-v2-$thumbnailPixels|" + cloudMediaKey(connection, path, entry?.size ?: local?.length() ?: -1, entry?.lastModified ?: local?.lastModified() ?: 0)
    val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    val proxy = NetworkStreamingProxy.getInstance()
    val streamId = "sprite_${UUID.randomUUID()}"
    val registered = source != null && connection != null
    val url = if (registered) proxy.registerStream(streamId, connection!!, source!!.relativePath, entry?.size ?: -1, item.mimeType ?: "application/octet-stream")
      else local?.path ?: item.playableUri
    // The original generator owns its background task. A cancelled waiter must keep the proxy
    // alive until generation finishes; tie cleanup to the generator's in-flight task instead.
    CloudTrace.diagnostic("sprite.prepare", key, "connection=${source?.connectionId ?: 0} durationMs=$durationMs")
    android.util.Log.i(
      TAG,
      "sprite.prepare id=${item.stableId} scheme=${url.substringBefore("://")} ext=${cloudMediaExtension(path)} " +
        "local=${local?.exists() == true} pathExt=${path.substringAfterLast('.', "").take(6)} durationMs=$durationMs",
    )
    val result = try {
      generator.generate(url, if (registered) null else item.headers, source?.let { dao.getVideo(it.connectionId, it.relativePath)?.durationMs }?.takeIf { it > 0 } ?: durationMs, directory, key, context,
        onGenerationFinished = { if (registered) proxy.unregisterStream(streamId) }, sourceExtension = cloudMediaExtension(path),
        // MediaCodec has no decoder for profiles like H.264 High 10; mpv decodes them in software.
        // It streams the file and hands back one frame at a time, so this stays cheap in memory.
        softwareFrameBatchGrabber = { framePath, positions, sizePx ->
          `is`.xyz.mpv.FastThumbnails.generateMultipleAsync(framePath, positions, sizePx, useHwDec = false)
        })
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      android.util.Log.w(TAG, "sprite.prepare failed id=${item.stableId} scheme=${url.substringBefore("://")}", error)
      null
    }
    if (result == null) {
      android.util.Log.i(TAG, "sprite.prepare result=null id=${item.stableId} key=${key.take(8)}")
      return@withContext
    }
    if (wantedId != item.stableId) { CloudTrace.diagnostic("sprite.detached", key); return@withContext }
    val bitmap = BitmapFactory.decodeFile(result.file.path) ?: run {
      android.util.Log.i(TAG, "sprite.prepare decode-failed id=${item.stableId} bytes=${result.file.length()}")
      return@withContext
    }
    val meta = result.metadata
    _current.value = CloudSpriteSheet(item.stableId, bitmap, SpriteSheetMetadata(
      columns = meta.cols, rows = meta.rows, cellWidth = meta.thumbWidth, cellHeight = meta.thumbHeight,
      timesMs = List(meta.frameCount) { (it * meta.intervalMs).toLong() }, durationMs = meta.durationMs,
      intervalMs = meta.intervalMs, validCells = meta.validCells))
    android.util.Log.i(
      TAG,
      "sprite.prepare ready id=${item.stableId} cell=${meta.thumbWidth}x${meta.thumbHeight} frames=${meta.frameCount} " +
        "valid=${meta.validCells?.size ?: -1}",
    )
    CloudTrace.event("sprite.ready", source?.connectionId ?: 0, detail = "frames=${meta.frameCount} size=${meta.thumbWidth}x${meta.thumbHeight}")
    CloudTrace.diagnostic("sprite.ready", key, "valid=${meta.validCells?.size ?: 0} frames=${meta.frameCount}")
  }
}
