package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.database.entities.CloudFolderMetadataEntity
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A failed, cancelled, cyclic or bounded scan cannot prove that a subtree is empty. */
class CloudFolderScanner(
  private val connectionId: Long,
  private val list: suspend (String) -> Result<List<NetworkFile>>,
  private val save: suspend (CloudFolderMetadataEntity) -> Unit,
  private val maxDirectories: Int = 5000,
) {
  private val visited = mutableSetOf<String>()

  suspend fun scan(rawPath: String, depth: Int = 0): CloudFolderMetadataEntity {
    currentCoroutineContext().ensureActive()
    val path = NetworkPath.from(rawPath).value
    val empty = CloudFolderMetadataEntity(connectionId, path, 0, 0, 0, 0, false, System.currentTimeMillis())
    if (depth >= 64 || visited.size >= maxDirectories || !visited.add(path)) return empty
    val items = try { list(path).getOrThrow() } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { return empty }
    val videos = items.filter { !it.isDirectory && (it.mimeType?.startsWith("video/") == true ||
      cloudMediaExtension(it.name) in FileTypeUtils.VIDEO_EXTENSIONS) }
    val children = items.filter { it.isDirectory }.map { scan(it.path, depth + 1) }
    val summary = empty.copy(
      videoCount = videos.size + children.sumOf { it.videoCount },
      totalSize = videos.sumOf { it.size.coerceAtLeast(0) } + children.sumOf { it.totalSize },
      totalDurationMs = videos.sumOf { it.durationMs } + children.sumOf { it.totalDurationMs },
      folderCount = children.size + children.sumOf { it.folderCount },
      scanComplete = children.all { it.scanComplete },
      updatedAt = System.currentTimeMillis(),
    )
    save(summary)
    return summary
  }
}
