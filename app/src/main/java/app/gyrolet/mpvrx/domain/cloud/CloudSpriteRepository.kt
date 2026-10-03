package app.gyrolet.mpvrx.domain.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import app.gyrolet.mpvrx.database.dao.CloudMetadataDao
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CloudSpriteSheet(val mediaId: String, val bitmap: Bitmap, val metadata: SpriteSheetMetadata)

/** Persistent sheets contain only preview pixels and timestamps, never stream URLs or credentials. */
class CloudSpriteRepository(
  private val context: Context,
  private val network: NetworkRepository,
  private val dao: CloudMetadataDao,
  private val keyframes: CloudKeyframeExtractor,
) {
  private val directory = File(context.filesDir, "cloud_sprites").apply { mkdirs() }
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Mutex()
  private val _current = MutableStateFlow<CloudSpriteSheet?>(null)
  val current = _current.asStateFlow()

  fun reset() { _current.value = null }

  suspend fun prepare(item: PlaybackItem, durationMs: Long) = withContext(Dispatchers.IO) {
    if (durationMs <= 0) return@withContext
    lock.withLock {
      val source = item.networkSource
      val connection = source?.let { network.getConnectionById(it.connectionId) }
      if (source != null && connection == null) return@withLock
      val entry = source?.let { dao.getItem(it.connectionId, it.relativePath) }
      val path = source?.relativePath ?: item.originalUri
      val localFile = if (source == null && !path.contains("://")) File(path) else if (path.startsWith("file://")) File(android.net.Uri.parse(path).path.orEmpty()) else null
      val identity = "sprite-yuv-batch-v3|" + cloudMediaKey(connection, path, entry?.size ?: localFile?.length() ?: -1, entry?.lastModified ?: localFile?.lastModified() ?: 0)
      val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
      val imageFile = File(directory, "$key.webp")
      val metaFile = File(directory, "$key.json")
      load(item.stableId, imageFile, metaFile, durationMs)?.let { _current.value = it; return@withLock }
      val proxy = NetworkStreamingProxy.getInstance()
      val streamId = "sprite_${UUID.randomUUID()}"
      var registered = false
      var retriever: MediaMetadataRetriever? = null
      var sheet: Bitmap? = null
      val decodedBatch = mutableMapOf<Long, Bitmap>()
      try {
        val url = if (source != null && connection != null) {
          registered = true
          proxy.registerStream(streamId, connection, source.relativePath, entry?.size ?: -1,
            item.mimeType ?: "application/octet-stream")
        } else item.playableUri
        val extension = cloudMediaExtension(path)
        val extractor = if (item.headers.isEmpty() || registered) keyframes else {
          val origin = url.toHttpUrlOrNull()?.host
          CloudKeyframeExtractor(SharedHttpClient.derive {
            addInterceptor { chain ->
              val request = chain.request().newBuilder()
              if (chain.request().url.host == origin) item.headers.forEach { (name, value) -> request.header(name, value) }
              chain.proceed(request.build())
            }
          })
        }
        val times = if (extractor.supports(extension)) extractor.frameTimes(url, identity, extension, durationMs) else emptyList()
        val targets = times.ifEmpty { (0 until 100).map { durationMs * it / 100 } }
        val canvasBitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        sheet = canvasBitmap
        val canvas = Canvas(canvasBitmap).apply { drawColor(Color.BLACK) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val writtenTimes = mutableListOf<Long>()
        for ((targetIndex, time) in targets.withIndex()) {
          if (targetIndex % 8 == 0 && times.isNotEmpty()) {
            decodedBatch.values.forEach { it.recycle() }
            decodedBatch.clear()
            decodedBatch.putAll(extractor.spriteBatch(url, identity, extension, targets.drop(targetIndex).take(8)))
          }
          currentCoroutineContext().ensureActive()
          var frame: Bitmap? = null
          try {
            frame = decodedBatch.remove(time)
            if (frame == null) frame = if (times.isNotEmpty()) extractor.extract(url, identity, extension, time.toFloat() / durationMs)?.bitmap else null
            if (frame == null) {
              if (retriever == null) retriever = MediaMetadataRetriever().also { decoder ->
                if (url.startsWith("content://")) decoder.setDataSource(context, android.net.Uri.parse(url))
                else if (!url.startsWith("http://") && !url.startsWith("https://")) decoder.setDataSource(android.net.Uri.parse(url).path ?: url)
                else decoder.setDataSource(url, if (registered) emptyMap() else item.headers)
              }
              frame = if (android.os.Build.VERSION.SDK_INT >= 27)
                retriever!!.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
              else retriever!!.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            val bitmap = frame ?: continue
            CloudTrace.event("sprite.frame", source?.connectionId ?: 0, detail = "index=$targetIndex size=${bitmap.width}x${bitmap.height} config=${bitmap.config}")
            val index = writtenTimes.size
            val left = (index % 10) * 160
            val top = (index / 10) * 90
            val cell = if (YuvToBitmapBridge.available && bitmap.config == Bitmap.Config.ARGB_8888)
              YuvToBitmapBridge.argbScale(bitmap, 160, 90, FilterMode.BOX)
            else null
            try {
              if (cell == null || !YuvToBitmapBridge.compositeToSheet(cell, canvasBitmap,
                  index % 10, index / 10, 160, 90, 10)) {
                canvas.drawBitmap(bitmap, null, Rect(left, top, left + 160, top + 90), paint)
              }
            } finally { cell?.recycle() }
            writtenTimes += time
            // Publish immutable snapshots early; a 100-frame remote sheet can take minutes.
            if (writtenTimes.size == 1 || writtenTimes.size % 10 == 0) {
              _current.value = CloudSpriteSheet(item.stableId,
                canvasBitmap.copy(Bitmap.Config.ARGB_8888, false),
                SpriteSheetMetadata(timesMs = writtenTimes.toList(), durationMs = durationMs))
              CloudTrace.event("sprite.progress", source?.connectionId ?: 0,
                detail = "frames=${writtenTimes.size} native=${YuvToBitmapBridge.available}")
            }
          } finally { frame?.recycle() }
        }
        if (writtenTimes.isEmpty()) return@withLock
        val metadata = SpriteSheetMetadata(timesMs = writtenTimes, durationMs = durationMs)
        val pendingImage = File(directory, "$key.webp.tmp")
        val pendingMeta = File(directory, "$key.json.tmp")
        pendingImage.outputStream().use { check(canvasBitmap.compress(
          if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 90, it)) }
        val verifiedMetadata = metadata.copy(imageHash = hashFile(pendingImage))
        pendingMeta.writeText(json.encodeToString(SpriteSheetMetadata.serializer(), verifiedMetadata))
        currentCoroutineContext().ensureActive()
        java.nio.file.Files.move(pendingImage.toPath(), imageFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        java.nio.file.Files.move(pendingMeta.toPath(), metaFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        _current.value = CloudSpriteSheet(item.stableId, canvasBitmap, verifiedMetadata)
        sheet = null // StateFlow now owns the immutable sheet; UI references remain valid across eviction.
        trim()
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { CloudTrace.event("sprite.failed", detail = "error=${error.javaClass.simpleName}") }
      finally {
        decodedBatch.values.forEach { it.recycle() }
        sheet?.recycle()
        retriever?.release()
        if (registered) proxy.unregisterStream(streamId)
        File(directory, "$key.webp.tmp").delete()
        File(directory, "$key.json.tmp").delete()
      }
    }
  }

  private fun load(id: String, image: File, metadata: File, duration: Long): CloudSpriteSheet? = runCatching {
    if (!image.exists() || !metadata.exists()) return null
    if (metadata.length() > 128 * 1024 || image.length() > 16 * 1024 * 1024) return null
    val meta = json.decodeFromString(SpriteSheetMetadata.serializer(), metadata.readText())
    if (!meta.isValid() || kotlin.math.abs(meta.durationMs - duration) > 1000 || meta.imageHash != hashFile(image)) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(image.path, bounds)
    if (bounds.outWidth != meta.columns * meta.cellWidth || bounds.outHeight != meta.rows * meta.cellHeight) return null
    val bitmap = BitmapFactory.decodeFile(image.path) ?: return null
    if (bitmap.width != meta.columns * meta.cellWidth || bitmap.height != meta.rows * meta.cellHeight) { bitmap.recycle(); return null }
    image.setLastModified(System.currentTimeMillis())
    CloudSpriteSheet(id, bitmap, meta)
  }.getOrNull()

  private fun hashFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
      val buffer = ByteArray(64 * 1024)
      while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun trim() {
    val images = directory.listFiles()?.filter { it.extension == "webp" }?.sortedBy { it.lastModified() }.orEmpty()
    var bytes = images.sumOf { it.length() }
    for (image in images) {
      if (bytes <= 50L * 1024 * 1024) break
      val size = image.length()
      if (image.delete()) { bytes -= size; File(directory, "${image.nameWithoutExtension}.json").delete() }
    }
  }
}
