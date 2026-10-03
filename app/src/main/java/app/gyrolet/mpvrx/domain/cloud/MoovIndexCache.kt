package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.LinkedHashMap
import app.gyrolet.mpvrx.domain.cloud.CloudLog as Logger

/**
 * moov / Matroska 关键帧索引的内存 + 磁盘缓存。
 *
 * 内存层：LRU（[MAX_MEMORY_ENTRIES] 条），进程内去重。
 * 磁盘层：进程重启后首次播放/拖动无需重新 HEAD + Range 下载 moov，
 *        直接复用上次解析出的关键帧表、时长与章节。
 *
 * key 身份与内存缓存一致：去掉 userinfo / query（sign）后的稳定 URL。
 * 磁盘文件名 = 清洗后的视频名 + 稳定 URL 的 12 位短哈希（视频名仅供可读，
 * 唯一性完全由哈希保证，避免同名文件碰撞互相污染关键帧表）。
 *
 * 注意：`get` / `getChapters` / `findNearestKeyframe` 保持纯内存（可能被
 * 主线程调用，不做磁盘 IO）。磁盘读通过 [ensureLoadedFromDisk] 由 IO 调用方
 * （如 `resolveChaptersForMediaItem` / `prefetchScrubKeyframe`）先触发一次，
 * 命中后回填内存，后续走内存。
 */
object MoovIndexCache {
  private const val TAG = "BUG4_Chapters"
  private const val MAX_MEMORY_ENTRIES = 32
  private const val MOOV_DISK_DIR_NAME = "moov_index"
  private const val MOOV_DISK_TTL_MS = 30L * 24 * 60 * 60 * 1000L
  private const val MOOV_DISK_MAX_FILES = 256
  private const val MOOV_HASH_LENGTH = 12
  private const val MOOV_BASENAME_LENGTH = 80

  data class Entry(
    val keyframes: List<Mp4KeyframeExtractor.KeyframeEntry>,
    val contentLength: Long,
    val durationMs: Long?,
    val chapters: List<ChapterEntry> = emptyList(),
    val parsed: Mp4KeyframeExtractor.ParsedMoov? = null,
  )

  @Serializable
  private data class DiskKeyframe(
    val sampleIndex: Int,
    val timeMs: Long,
    val byteOffset: Long,
    val byteSize: Int,
  )

  @Serializable
  private data class DiskEntry(
    val keyframes: List<DiskKeyframe>,
    val contentLength: Long,
    val durationMs: Long?,
    val chapters: List<ChapterEntry> = emptyList(),
    val parsed: Mp4KeyframeExtractor.ParsedMoov? = null,
  )

  private val json = Json { ignoreUnknownKeys = true }

  private val lock = Any()
  private val entries =
    object : LinkedHashMap<String, Entry>(MAX_MEMORY_ENTRIES, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
        size > MAX_MEMORY_ENTRIES
    }

  @Volatile
  private var cacheDir: File? = null

  fun init(dir: File) {
    cacheDir = File(dir, MOOV_DISK_DIR_NAME).also { it.mkdirs() }
  }

  private fun cacheKey(url: String): String = stableWebDavUrl(url)

  /** 纯内存读取（可安全地在主线程调用）。 */
  fun get(url: String): Entry? =
    synchronized(lock) {
      entries[cacheKey(url)]
    }

  /**
   * 由 IO 调用方在需要关键帧/章节前调用一次：内存 miss 时尝试磁盘读并回填。
   * 返回是否已可命中。
   */
  suspend fun ensureLoadedFromDisk(url: String): Boolean =
    withContext(Dispatchers.IO) {
      val key = cacheKey(url)
      synchronized(lock) {
        if (entries[key] != null) {
          true
        } else {
          readFromDisk(key)?.also { entries[key] = it } != null
        }
      }
    }

  fun put(
    url: String,
    entry: Entry,
  ) {
    val key = cacheKey(url)
    synchronized(lock) {
      entries[key] = entry
      writeToDisk(key, entry)
    }
    if (entry.chapters.isNotEmpty()) {
      Logger.d(TAG, "MoovIndexCache PUT: ${entry.chapters.size} chapters for key=${key.take(80)}")
    }
  }

  fun findNearestKeyframe(
    url: String,
    targetTimeMs: Long,
  ): Mp4KeyframeExtractor.KeyframeEntry? {
    val entry = get(url) ?: return null
    if (entry.keyframes.isEmpty()) return null
    return entry.keyframes.minByOrNull { kotlin.math.abs(it.timeMs - targetTimeMs) }
  }

  fun getChapters(url: String): List<ChapterEntry> {
    val key = cacheKey(url)
    val result = get(url)?.chapters ?: emptyList()
    Logger.d(
      TAG,
      "MoovIndexCache GET chapters: ${result.size} entries for key=${key.take(80)} (url=${url.take(60)})",
    )
    return result
  }

  // ---- 磁盘层 ----

  private fun diskFile(key: String): File? {
    val dir = cacheDir ?: return null
    return File(dir, diskFileName(key))
  }

  private fun diskFileName(key: String): String {
    val videoName =
      key
        .substringAfterLast('/')
        .substringBefore('?')
        .take(MOOV_BASENAME_LENGTH)
        .replace(Regex("""[\\/:*?"<>|\s]"""), "_")
        .trim(' ', '.')
        .ifBlank { "moov" }
    val hash =
      java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(MOOV_HASH_LENGTH)
    return "${videoName}_$hash.json"
  }

  private fun writeToDisk(
    key: String,
    entry: Entry,
  ) {
    val file = diskFile(key) ?: return
    runCatching {
      val diskEntry =
        DiskEntry(
          keyframes =
            entry.keyframes.map {
              DiskKeyframe(
                sampleIndex = it.sampleIndex,
                timeMs = it.timeMs,
                byteOffset = it.byteOffset,
                byteSize = it.byteSize,
              )
            },
          contentLength = entry.contentLength,
          durationMs = entry.durationMs,
          chapters = entry.chapters,
          parsed = entry.parsed,
        )
      val temporary = File(file.parentFile, file.name + ".tmp")
      temporary.writeText(json.encodeToString(DiskEntry.serializer(), diskEntry))
      java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }.onFailure { error ->
      Logger.w(TAG, "Failed to write moov index to disk", error)
    }
    trimDiskFiles()
  }

  private fun readFromDisk(key: String): Entry? {
    val file = diskFile(key) ?: return null
    if (!file.exists()) return null
    if (System.currentTimeMillis() - file.lastModified() > MOOV_DISK_TTL_MS) {
      file.delete()
      return null
    }
    return runCatching {
      val diskEntry = json.decodeFromString(DiskEntry.serializer(), file.readText())
      Entry(
        keyframes =
          diskEntry.keyframes.map {
            Mp4KeyframeExtractor.KeyframeEntry(
              sampleIndex = it.sampleIndex,
              timeMs = it.timeMs,
              byteOffset = it.byteOffset,
              byteSize = it.byteSize,
            )
          },
        contentLength = diskEntry.contentLength,
        durationMs = diskEntry.durationMs,
        chapters = diskEntry.chapters,
        parsed = diskEntry.parsed,
      )
    }.onFailure { error ->
      Logger.w(TAG, "Failed to read moov index from disk", error)
    }.getOrNull()
  }

  private fun trimDiskFiles() {
    val dir = cacheDir ?: return
    val files = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
    if (files.size <= MOOV_DISK_MAX_FILES) return
    files
      .sortedBy { it.lastModified() }
      .take(files.size - MOOV_DISK_MAX_FILES)
      .forEach { runCatching { it.delete() } }
  }
}
