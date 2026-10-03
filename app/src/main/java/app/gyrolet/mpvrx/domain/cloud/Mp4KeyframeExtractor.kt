package app.gyrolet.mpvrx.domain.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import app.gyrolet.mpvrx.domain.cloud.CloudLog as Logger

class Mp4KeyframeExtractor(
  private val okHttpClient: OkHttpClient,
) {
  private val moovLocks = ConcurrentHashMap<String, Mutex>()
  private val moovCacheLock = Any()
  private val moovCache =
    object : LinkedHashMap<String, ParsedMoov>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedMoov>?): Boolean =
        size > MAX_MOOV_CACHE_ENTRIES
    }

  suspend fun extractKeyframe(
    url: String,
    targetPercent: Float = 0.20f,
    stableKey: String? = null,
  ): Bitmap? = extractKeyframeWithMetadata(url, targetPercent, stableKey)?.bitmap

  /**
   * [stableKey] replaces the URL-derived cache identity. Callers that reach the bytes through a
   * loopback proxy (whose port/token change on every registration) pass their own durable
   * identity here so the moov/keyframe index cache stays reusable across sessions.
   */
  suspend fun extractKeyframeWithMetadata(
    url: String,
    targetPercent: Float = 0.20f,
    stableKey: String? = null,
  ): KeyframeResult? =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()

      val parsedMoov =
        loadParsedMoov(url, stableKey)
          ?: return@withContext null.also { log { "FAIL: could not find moov atom" } }

      val moovInfo =
        parsedMoov.moovInfo
          ?: return@withContext null.also { log { "FAIL: could not parse moov atom" } }

      log {
        "PARSED: codec=${moovInfo.codecType} ${moovInfo.width}x${moovInfo.height} " +
          "timescale=${moovInfo.timescale} duration=${moovInfo.duration} " +
          "keyframes=${moovInfo.keyframes.size}"
      }

      val targetKeyframe =
        selectKeyframe(moovInfo, targetPercent)
          ?: return@withContext null.also { log { "FAIL: no keyframe near ${(targetPercent * 100).toInt()}%" } }

      log {
        "SELECTED: kfIdx=${targetKeyframe.sampleIndex} timeMs=${targetKeyframe.timeMs} " +
          "offset=${targetKeyframe.byteOffset} size=${targetKeyframe.byteSize}"
      }

      val keyframeData =
        httpRange(url, targetKeyframe.byteOffset, targetKeyframe.byteSize)
          ?: return@withContext null.also { log { "FAIL: could not download keyframe data" } }

      log { "DOWNLOADED: ${keyframeData.size} bytes (${keyframeData.size / 1024}KB)" }

      val bitmap =
        decodeKeyframe(moovInfo, keyframeData)
          ?: return@withContext null.also { log { "FAIL: could not decode keyframe" } }

      val elapsed = System.currentTimeMillis() - startTime
      val totalDownloaded = parsedMoov.moovByteSize + keyframeData.size
      log {
        "SUCCESS: ${bitmap.width}x${bitmap.height} in ${elapsed}ms, downloaded ${totalDownloaded / 1024}KB total"
      }

      KeyframeResult(
        bitmap = bitmap,
        durationMs = parsedMoov.durationMs,
        width = moovInfo.width.takeIf { it > 0 },
        height = moovInfo.height.takeIf { it > 0 },
      )
    }

  suspend fun extractDurationMs(
    url: String,
    stableKey: String? = null,
  ): Long? =
    withContext(Dispatchers.IO) {
      loadParsedMoov(url, stableKey)?.durationMs
        ?: return@withContext null.also { log { "DURATION FAIL: could not parse mvhd" } }
    }

  fun extractChapters(moovBytes: ByteArray): List<ChapterEntry> = parseChpl(moovBytes)

  private fun parseChpl(data: ByteArray): List<ChapterEntry> {
    log { "CHPL: searching moov in ${data.size}B..." }
    val moovResult = findAtom(data, 0, data.size, "moov")
    if (moovResult == null) {
      log { "CHPL: moov not found" }
      return emptyList()
    }
    val (moovOff, moovSize) = moovResult
    val moovEnd = minOf(moovOff + moovSize, data.size)
    log { "CHPL: moov at off=$moovOff size=$moovSize" }

    log { "CHPL: searching udta in moov[${moovOff + 8}..$moovEnd]..." }
    val udtaResult = findAtom(data, moovOff + 8, moovEnd, "udta")
    if (udtaResult == null) {
      log { "CHPL: udta not found — no chapters in this file" }
      return emptyList()
    }
    val (udtaOff, udtaSize) = udtaResult
    val udtaEnd = minOf(udtaOff + udtaSize, moovEnd)
    log { "CHPL: udta at off=$udtaOff size=$udtaSize" }

    log { "CHPL: searching chpl in udta[${udtaOff + 8}..$udtaEnd]..." }
    val chplResult = findAtom(data, udtaOff + 8, udtaEnd, "chpl")
    if (chplResult == null) {
      log { "CHPL: chpl not found — no Apple chapters in this file" }
      return emptyList()
    }
    val (chplOff, chplSize) = chplResult
    val chplEnd = minOf(chplOff + chplSize, data.size)
    log { "CHPL: chpl at off=$chplOff size=$chplSize" }

    if (chplOff + 17 > chplEnd) {
      log { "CHPL: too small (chplEnd=$chplEnd)" }
      return emptyList()
    }

    val version = data[chplOff + 8].toInt() and 0xFF
    if (version != 1) {
      log { "CHPL: unsupported version=$version" }
      return emptyList()
    }

    var pos = chplOff + 12
    val count = readInt32BE(data, pos)
    pos += 4

    val actualCount =
      if (count in 1..1000) {
        log { "CHPL: 4-byte count=$count" }
        count
      } else {
        pos = chplOff + 12
        val c = (data[pos].toInt() and 0xFF)
        pos++
        log { "CHPL: 1-byte count=$c (4-byte was $count)" }
        c
      }

    if (actualCount <= 0) {
      log { "CHPL: zero count" }
      return emptyList()
    }

    return buildList {
      val entries = mutableListOf<Pair<Long, String>>()
      for (i in 0 until actualCount) {
        if (pos + 9 > chplEnd) {
          log { "CHPL: entry[$i] truncated at pos=$pos" }
          break
        }
        val timestamp100ns = readInt64BE(data, pos)
        val startTimeMs = timestamp100ns / 10000L
        pos += 8
        val titleLen = data[pos].toInt() and 0xFF
        pos += 1
        if (titleLen <= 0 || pos + titleLen > chplEnd) {
          log { "CHPL: entry[$i] titleLen=$titleLen invalid" }
          break
        }
        val title = String(data, pos, titleLen, Charsets.UTF_8).trimEnd('\u0000')
        pos += titleLen
        log { "CHPL: entry[$i] time=${formatTime(startTimeMs)} title=$title" }
        entries.add(startTimeMs to title)
      }

      for (i in entries.indices) {
        val (start, title) = entries[i]
        val end = if (i + 1 < entries.size) entries[i + 1].first else Long.MAX_VALUE
        add(ChapterEntry(startTimeMs = start, endTimeMs = end, title = title))
      }

      log { "CHPL: SUCCESS parsed ${entries.size} chapters" }
    }
  }

  private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
  }

  data class MoovInfo(
    val timescale: Int,
    val duration: Long,
    val codecType: String,
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
    val codecConfigNalUnits: List<ByteArray>,
    val nalLengthSize: Int,
    val keyframes: List<KeyframeEntry>,
  )

  data class KeyframeEntry(
    val sampleIndex: Int,
    val timeMs: Long,
    val byteOffset: Long,
    val byteSize: Int,
  )

  data class KeyframeResult(
    val bitmap: Bitmap,
    val durationMs: Long?,
    val width: Int? = null,
    val height: Int? = null,
  )

  suspend fun loadParsedMoov(
    url: String,
    stableKey: String? = null,
  ): ParsedMoov? {
    val cacheKey = stableKey?.let { moovCacheKey(it) } ?: moovCacheKey(url)
    getCachedMoov(cacheKey)?.let {
      log { "MOOV cache hit: bytes=${it.moovByteSize} duration=${it.durationMs ?: 0}" }
      return it
    }

    val lock = moovLocks.computeIfAbsent(cacheKey) { Mutex() }
    return try {
      lock.withLock {
        getCachedMoov(cacheKey)?.let {
          log { "MOOV cache hit after wait: bytes=${it.moovByteSize} duration=${it.durationMs ?: 0}" }
          return@withLock it
        }

        val contentLength = httpHead(url)
        Logger.d(
          "BUG4_HttpExtractor",
          "loadParsedMoov: HEAD contentLength=$contentLength url=${cacheKey.take(80)}",
        )
        log { "HEAD contentLength=$contentLength url=$cacheKey" }
        if (contentLength == null || contentLength < 1024) {
          Logger.d("BUG4_HttpExtractor", "loadParsedMoov FAIL: no Content-Length for ${cacheKey.take(80)}")
          log { "FAIL: HEAD returned no valid Content-Length" }
          return@withLock null
        }

        val moovData =
          downloadMoovAtom(url, contentLength)
            ?: return@withLock null
        val moovInfo = parseMoov(moovData)
        val durationMs = parseMoovDurationMs(moovData) ?: moovInfo?.durationMs()
        val parsed =
          ParsedMoov(
            contentLength = contentLength,
            moovByteSize = moovData.bytes.size,
            moovInfo = moovInfo,
            durationMs = durationMs,
          )
        putCachedMoov(cacheKey, parsed)

        if (moovInfo != null && moovInfo.keyframes.isNotEmpty()) {
          val chapters = extractChapters(moovData.bytes)
          log { "MOOV extracted ${chapters.size} chapters for caching" }
          MoovIndexCache.put(
            cacheKey,
            MoovIndexCache.Entry(
              keyframes = moovInfo.keyframes,
              contentLength = contentLength,
              durationMs = durationMs,
              chapters = chapters,
            ),
          )
        }
        log { "MOOV cache store: bytes=${parsed.moovByteSize} duration=${parsed.durationMs ?: 0}" }
        parsed
      }
    } finally {
      moovLocks.remove(cacheKey, lock)
    }
  }

  internal fun loadParsedMoovFromFile(filePath: String): ParsedMoov? {
    val file = File(filePath)
    val fileSize = file.length()
    if (fileSize < 1024) {
      log { "MOOV file: too small ($fileSize bytes)" }
      return null
    }

    getCachedMoov(filePath)?.let {
      log { "MOOV file cache hit: bytes=${it.moovByteSize}" }
      return it
    }

    val moovData = downloadMoovAtomFromFile(filePath, fileSize) ?: return null
    val moovInfo = parseMoov(moovData)
    val durationMs = parseMoovDurationMs(moovData) ?: moovInfo?.durationMs()
    val parsed =
      ParsedMoov(
        contentLength = fileSize,
        moovByteSize = moovData.bytes.size,
        moovInfo = moovInfo,
        durationMs = durationMs,
      )
    putCachedMoov(filePath, parsed)
    if (moovInfo != null && moovInfo.keyframes.isNotEmpty()) {
      val chapters = extractChapters(moovData.bytes)
      log { "MOOV file extracted ${chapters.size} chapters for caching" }
      MoovIndexCache.put(
        filePath,
        MoovIndexCache.Entry(
          keyframes = moovInfo.keyframes,
          contentLength = fileSize,
          durationMs = durationMs,
          chapters = chapters,
        ),
      )
    }
    log { "MOOV file cache store: bytes=${parsed.moovByteSize} duration=${parsed.durationMs ?: 0}" }
    return parsed
  }

  internal fun readFileRange(
    filePath: String,
    start: Long,
    size: Int,
  ): ByteArray? =
    try {
      RandomAccessFile(filePath, "r").use { raf ->
        raf.seek(start)
        val buf = ByteArray(size)
        raf.readFully(buf)
        buf
      }
    } catch (e: Exception) {
      log { "readFileRange error: ${e.message}" }
      null
    }

  private fun downloadMoovAtomFromFile(
    filePath: String,
    fileSize: Long,
  ): MoovData? {
    val probeSizes = listOf(64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024)
    for (probeSize in probeSizes) {
      val start = maxOf(0L, fileSize - probeSize)
      val size = (fileSize - start).toInt()
      val data = readFileRange(filePath, start, size) ?: continue

      val moovResult = findAtom(data, 0, data.size, "moov", isTailData = true)
      if (moovResult != null) {
        val (moovOffsetInData, moovSize) = moovResult
        val actualOffset = start + moovOffsetInData

        if (moovOffsetInData + moovSize <= data.size) {
          val moovBytes = data.copyOfRange(moovOffsetInData, moovOffsetInData + moovSize)
          log { "MOOV file found: offset=$actualOffset size=$moovSize" }
          return MoovData(bytes = moovBytes, fileOffset = actualOffset)
        }

        val fullMoov = readFileRange(filePath, actualOffset, moovSize)
        if (fullMoov != null && fullMoov.size == moovSize) {
          return MoovData(bytes = fullMoov, fileOffset = actualOffset)
        }
      }
    }
    return null
  }

  private fun moovCacheKey(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    return parsed
      .newBuilder()
      .username("")
      .password("")
      .build()
      .toString()
  }

  private fun getCachedMoov(url: String): ParsedMoov? = synchronized(moovCacheLock) { moovCache[url] }

  private fun putCachedMoov(
    url: String,
    parsedMoov: ParsedMoov,
  ) {
    synchronized(moovCacheLock) {
      moovCache[url] = parsedMoov
    }
  }

  private fun httpHead(url: String): Long? {
    ContentLengthCache.get(url)?.let { return it }

    return try {
      val request =
        videoMetadataRequest(url)
          .head()
          .header("Accept", "*/*")
          .build()
      Logger.d("BUG4_HttpExtractor", "httpHead: url=${url.take(100)}")
      okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          Logger.d("BUG4_HttpExtractor", "httpHead FAIL: code=${response.code} url=${url.take(80)}")
          return null
        }
        val contentLength = response.header("Content-Length")?.toLongOrNull()
        Logger.d("BUG4_HttpExtractor", "httpHead OK: contentLength=$contentLength url=${url.take(80)}")
        if (contentLength != null && contentLength > 0) {
          ContentLengthCache.put(url, contentLength)
        }
        contentLength
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Logger.d("BUG4_HttpExtractor", "httpHead EXCEPTION: ${e.message} url=${url.take(80)}")
      log { "HEAD error: ${e.message}" }
      null
    }
  }

  internal fun httpRange(
    url: String,
    start: Long,
    size: Int,
  ): ByteArray? {
    return try {
      val end = start + size - 1
      val request =
        videoMetadataRequest(url)
          .header("Range", "bytes=$start-$end")
          .header("Accept", "*/*")
          .header("Accept-Encoding", "identity")
          .build()
      Logger.d("BUG4_HttpExtractor", "httpRange: url=${url.take(100)} range=$start-$end")
      okHttpClient.newCall(request).execute().use { response ->
        val body = response.body ?: return null
        if (!response.isSuccessful) {
          Logger.d("BUG4_HttpExtractor", "httpRange FAIL: code=${response.code} url=${url.take(80)}")
          log { "Range $start-$end failed: ${response.code}" }
          return null
        }
        response.readContainerRange(start, size)
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Logger.d("BUG4_HttpExtractor", "httpRange EXCEPTION: ${e.message} url=${url.take(80)}")
      log { "Range error: ${e.message}" }
      null
    }
  }

  private fun downloadMoovAtom(
    url: String,
    fileSize: Long,
  ): MoovData? {
    val probeSizes = listOf(64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024)
    Logger.d("BUG4_HttpExtractor", "downloadMoovAtom: url=${url.take(100)} fileSize=$fileSize")
    for (probeSize in probeSizes) {
      val start = maxOf(0L, fileSize - probeSize)
      val range =
        when {
          start == 0L -> "0-${fileSize - 1}"
          else -> "$start-${fileSize - 1}"
        }
      log { "MOOV probe: tail $probeSize bytes (range=$range)" }
      val data = httpHeadWithRange(url, range) ?: continue

      val moovResult = findAtom(data, 0, data.size, "moov", isTailData = true)
      if (moovResult != null) {
        val (moovOffsetInData, moovSize) = moovResult
        val actualOffset = start + moovOffsetInData

        if (moovOffsetInData + moovSize <= data.size) {
          Logger.d(
            "BUG4_HttpExtractor",
            "downloadMoovAtom: MOOV found at offset=$actualOffset size=$moovSize probeSize=$probeSize",
          )
          log { "MOOV found: offset=$actualOffset size=$moovSize" }
          val moovBytes = data.copyOfRange(moovOffsetInData, moovOffsetInData + moovSize)
          return MoovData(bytes = moovBytes, fileOffset = actualOffset)
        }

        Logger.d("BUG4_HttpExtractor", "downloadMoovAtom: MOOV truncated, re-fetching size=$moovSize")
        log {
          "MOOV found but truncated (size=$moovSize > available=${data.size - moovOffsetInData}), re-fetching"
        }
        val fullMoov = httpRange(url, actualOffset, moovSize)
        if (fullMoov != null && fullMoov.size == moovSize) {
          return MoovData(bytes = fullMoov, fileOffset = actualOffset)
        }
      }
    }
    Logger.w(
      "BUG4_HttpExtractor",
      "downloadMoovAtom FAIL: no moov found after ${probeSizes.size} probes url=${url.take(100)}",
    )
    return null
  }

  private fun httpHeadWithRange(
    url: String,
    range: String,
  ): ByteArray? {
    return try {
      val request =
        videoMetadataRequest(url)
          .header("Range", "bytes=$range")
          .header("Accept", "*/*")
          .build()
      val requestedSize =
        range
          .substringAfter('-')
          .toLongOrNull()
          ?.let { end -> range.substringBefore('-').toLongOrNull()?.let { start -> end - start + 1 } }
          ?.coerceAtMost(Int.MAX_VALUE.toLong())
          ?.toInt()
      Logger.d("BUG4_HttpExtractor", "httpHeadWithRange: url=${url.take(100)} range=$range")
      okHttpClient.newCall(request).execute().use { response ->
        val body = response.body
        val code = response.code
        Logger.d(
          "BUG4_HttpExtractor",
          "httpHeadWithRange response: code=$code bodySize=${body?.contentLength()} url=${url.take(80)}",
        )
        if (body == null) {
          Logger.d("BUG4_HttpExtractor", "httpHeadWithRange FAIL: null body")
          return null
        }
        if (!response.isSuccessful || requestedSize == null) {
          Logger.d("BUG4_HttpExtractor", "httpHeadWithRange FAIL: not successful or not safe range")
          return null
        }
        response.readContainerRange(range.substringBefore('-').toLongOrNull() ?: return null, requestedSize)
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Logger.d("BUG4_HttpExtractor", "httpHeadWithRange EXCEPTION: ${e.message} url=${url.take(80)}")
      log { "Tail request error: ${e.message}" }
      null
    }
  }

  private data class MoovData(
    val bytes: ByteArray,
    val fileOffset: Long,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as MoovData

      if (fileOffset != other.fileOffset) return false
      if (!bytes.contentEquals(other.bytes)) return false

      return true
    }

    override fun hashCode(): Int {
      var result = fileOffset.hashCode()
      result = 31 * result + bytes.contentHashCode()
      return result
    }
  }

  data class ParsedMoov(
    val contentLength: Long,
    val moovByteSize: Int,
    val moovInfo: MoovInfo?,
    val durationMs: Long?,
    val videoTrackNumber: Long = 0,
  )

  private fun findAtom(
    data: ByteArray,
    startOffset: Int,
    endOffset: Int,
    type: String,
    isTailData: Boolean = false,
  ): Pair<Int, Int>? {
    require(type.length == 4) { "Atom type must be exactly 4 chars: $type" }
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val boundedEnd = minOf(endOffset, data.size)

    if (isTailData) {
      return findAtomBySignature(data, startOffset, boundedEnd, typeBytes)
    }

    var offset = startOffset
    while (offset + 8 <= endOffset && offset + 8 <= data.size) {
      if (offset + 8 > data.size) break
      val atomSize = readInt32BE(data, offset)
      if (atomSize < 8) break

      val atomType =
        try {
          String(data, offset + 4, 4, Charsets.US_ASCII)
        } catch (_: Exception) {
          break
        }

      if (atomType == type) {
        val size =
          when (val s = atomSize.toLong()) {
            0L -> data.size - offset
            1L -> {
              if (offset + 16 > data.size) return null
              readInt64BE(data, offset + 8).toInt()
            }
            else -> s.toInt()
          }
        return offset to size
      }

      offset += atomSize.toLong().coerceAtLeast(8).toInt()
    }
    return null
  }

  private fun findAtomBySignature(
    data: ByteArray,
    startOffset: Int,
    endOffset: Int,
    typeBytes: ByteArray,
  ): Pair<Int, Int>? {
    val firstTypeOffset = maxOf(startOffset + 4, 4)
    val lastTypeOffset = endOffset - 4
    if (firstTypeOffset > lastTypeOffset) return null

    for (typeOffset in firstTypeOffset..lastTypeOffset) {
      if (
        data[typeOffset] != typeBytes[0] ||
        data[typeOffset + 1] != typeBytes[1] ||
        data[typeOffset + 2] != typeBytes[2] ||
        data[typeOffset + 3] != typeBytes[3]
      ) {
        continue
      }

      val atomOffset = typeOffset - 4
      val size32 = readUInt32BE(data, atomOffset)
      val atomSize =
        if (size32 == 0L) {
          (endOffset - atomOffset).toLong()
        } else if (size32 == 1L) {
          if (atomOffset + 16 > endOffset) continue
          readInt64BE(data, atomOffset + 8)
        } else {
          size32
        }
      if (
        atomSize >= 8L &&
        atomSize <= Int.MAX_VALUE &&
        atomOffset.toLong() + atomSize <= endOffset.toLong()
      ) {
        return atomOffset to atomSize.toInt()
      }
    }
    return null
  }

  private fun parseTkhdRotation(
    data: ByteArray,
    tkhdOff: Int,
  ): Int {
    if (tkhdOff + 12 > data.size) return 0
    val version = data[tkhdOff + 8].toInt() and 0xFF
    val matrixOff = tkhdOff + if (version == 1) 60 else 48
    if (matrixOff + 20 > data.size) return 0

    val a = readInt32BE(data, matrixOff)
    val b = readInt32BE(data, matrixOff + 4)
    val c = readInt32BE(data, matrixOff + 12)
    val d = readInt32BE(data, matrixOff + 16)

    val one = 0x00010000
    val negOne = -0x00010000

    return when {
      a == 0 && b == one && c == negOne && d == 0 -> 90
      a == negOne && b == 0 && c == 0 && d == negOne -> 180
      a == 0 && b == negOne && c == one && d == 0 -> 270
      else -> 0
    }
  }

  private fun parseMoov(moovData: MoovData): MoovInfo? {
    val data = moovData.bytes
    if (data.size < 16) return null
    val moovSize = readUInt32BE(data, 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    var offset = 8
    val moovEnd = minOf(moovSize, data.size)

    var mvhdTimescale = 0
    var mvhdDuration = 0L

    val mvhdResult = findAtom(data, offset, moovEnd, "mvhd")
    if (mvhdResult != null) {
      val (mvhdOff, _) = mvhdResult
      val ver = data[mvhdOff + 8].toInt() and 0xFF
      val timescaleOffset = if (ver == 1) mvhdOff + 28 else mvhdOff + 20
      val durationOffset = if (ver == 1) mvhdOff + 32 else mvhdOff + 24
      mvhdTimescale = if (timescaleOffset + 4 <= data.size) readInt32BE(data, timescaleOffset) else 0
      mvhdDuration =
        if (ver == 1 && durationOffset + 8 <= data.size) {
          readInt64BE(data, durationOffset)
        } else if (durationOffset + 4 <= data.size) {
          readUInt32BE(data, durationOffset)
        } else {
          0L
        }
      log { "  mvhd: ver=$ver timescale=$mvhdTimescale duration=$mvhdDuration" }
    }

    offset = 8
    while (offset + 8 <= moovEnd) {
      val trakResult = findAtom(data, offset, moovEnd, "trak") ?: break
      val (trakOff, trakSize) = trakResult
      val trakEnd = minOf(trakOff + trakSize, moovEnd)
      val trakDataStart = trakOff + 8

      val mdiaResult =
        findAtom(data, trakDataStart, trakEnd, "mdia") ?: run {
          log { "  trak: skipped (no mdia)" }
          offset = trakEnd
          continue
        }
      val (mdiaOff, mdiaSize) = mdiaResult
      val mdiaEnd = minOf(mdiaOff + mdiaSize, trakEnd)
      val mdiaDataStart = mdiaOff + 8

      val hdlrResult = findAtom(data, mdiaDataStart, mdiaEnd, "hdlr")
      val handlerType =
        hdlrResult
          ?.first
          ?.let { hdlrOff ->
            if (hdlrOff + 20 <= data.size) String(data, hdlrOff + 16, 4, Charsets.US_ASCII) else ""
          }.orEmpty()

      if (handlerType != "vide") {
        log { "  trak: skipped handler=$handlerType" }
        offset = trakEnd
        continue
      }

      log { "  trak: found video track" }

      var rotation = 0
      val tkhdResult = findAtom(data, trakDataStart, trakEnd, "tkhd")
      if (tkhdResult != null) {
        rotation = parseTkhdRotation(data, tkhdResult.first)
        if (rotation != 0) {
          log { "  tkhd: rotation=$rotation°" }
        }
      }

      val mdhdResult = findAtom(data, mdiaDataStart, mdiaEnd, "mdhd")
      val mdhdTimescale =
        if (mdhdResult != null) {
          val (mdhdOff, _) = mdhdResult
          val mdhdVersion = data[mdhdOff + 8].toInt() and 0xFF
          val mdhdTimescaleOffset = if (mdhdVersion == 1) mdhdOff + 28 else mdhdOff + 20
          if (mdhdTimescaleOffset + 4 <= data.size) readInt32BE(data, mdhdTimescaleOffset) else 0
        } else {
          mvhdTimescale
        }

      val minfResult =
        findAtom(data, mdiaDataStart, mdiaEnd, "minf") ?: run {
          offset = trakEnd
          continue
        }
      val (minfOff, minfSize) = minfResult
      val minfEnd = minOf(minfOff + minfSize, mdiaEnd)
      val minfDataStart = minfOff + 8

      val stblResult =
        findAtom(data, minfDataStart, minfEnd, "stbl") ?: run {
          offset = trakEnd
          continue
        }
      val (stblOff, stblSize) = stblResult
      val stblEnd = minOf(stblOff + stblSize, minfEnd)
      val stblDataStart = stblOff + 8

      val stsdResult = findAtom(data, stblDataStart, stblEnd, "stsd")
      if (stsdResult == null) {
        offset = trakEnd
        continue
      }
      val (stsdOff, _) = stsdResult

      val entryCount = readInt32BE(data, stsdOff + 12)
      if (entryCount < 1) {
        offset = trakEnd
        continue
      }
      val entryOff = stsdOff + 16
      if (entryOff + 86 > data.size) {
        offset = trakEnd
        continue
      }

      val entrySize = readInt32BE(data, entryOff)
      if (entrySize < 86 || entryOff + entrySize > data.size) {
        log { "  stsd: invalid entrySize=$entrySize" }
        offset = trakEnd
        continue
      }
      val format = String(data, entryOff + 4, 4, Charsets.US_ASCII)
      val width = ((data[entryOff + 32].toInt() and 0xFF) shl 8) or (data[entryOff + 33].toInt() and 0xFF)
      val height = ((data[entryOff + 34].toInt() and 0xFF) shl 8) or (data[entryOff + 35].toInt() and 0xFF)
      log { "  stsd: format=$format ${width}x$height entrySize=$entrySize" }

      var codecConfig: CodecConfig? = null
      val boxStart = entryOff + 86
      val boxEnd = entryOff + entrySize
      var boxPos = boxStart
      while (boxPos + 8 <= boxEnd && boxPos + 8 <= data.size) {
        val boxSize = readInt32BE(data, boxPos)
        if (boxSize < 8 || boxPos + boxSize > boxEnd || boxPos + boxSize > data.size) break
        val boxType = String(data, boxPos + 4, 4, Charsets.US_ASCII)
        when (boxType) {
          "avcC" -> {
            log { "  avcC: boxSize=$boxSize" }
            codecConfig = parseAvcC(data, boxPos + 8, boxPos + boxSize)
          }
          "hvcC" -> {
            log { "  hvcC: boxSize=$boxSize" }
            codecConfig = parseHvcC(data, boxPos + 8, boxPos + boxSize)
          }
          else -> {}
        }
        boxPos += boxSize.coerceAtLeast(8)
      }

      val codec = codecConfig
      if (codec == null || codec.nalUnits.isEmpty()) {
        log { "  FAIL: no codec config data found" }
        offset = trakEnd
        continue
      }

      val keyframeIndices = mutableListOf<Int>()
      val stssRes = findAtom(data, stblDataStart, stblEnd, "stss")
      if (stssRes != null) {
        val (stssOff, _) = stssRes
        val stssCount = readInt32BE(data, stssOff + 12)
        for (i in 0 until stssCount) {
          keyframeIndices.add(readInt32BE(data, stssOff + 16 + i * 4))
        }
        log { "  stss: ${keyframeIndices.size} keyframes" }
      } else {
        log { "  stss: not found (all frames are keyframes)" }
      }

      data class SttsEntry(
        val sampleCount: Int,
        val sampleDelta: Int,
      )
      val sttsEntries = mutableListOf<SttsEntry>()
      val sttsRes = findAtom(data, stblDataStart, stblEnd, "stts")
      if (sttsRes != null) {
        val (sttsOff, _) = sttsRes
        val sttsCount = readInt32BE(data, sttsOff + 12)
        for (i in 0 until sttsCount) {
          sttsEntries.add(
            SttsEntry(
              sampleCount = readInt32BE(data, sttsOff + 16 + i * 8),
              sampleDelta = readInt32BE(data, sttsOff + 20 + i * 8),
            ),
          )
        }
      }
      if (sttsEntries.isEmpty()) {
        log { "  FAIL: no stts entries" }
        offset = trakEnd
        continue
      }

      data class StscEntry(
        val firstChunk: Int,
        val samplesPerChunk: Int,
        val sampleDescIndex: Int,
      )
      val stscEntries = mutableListOf<StscEntry>()
      val stscRes = findAtom(data, stblDataStart, stblEnd, "stsc")
      if (stscRes != null) {
        val (stscOff, _) = stscRes
        val stscCount = readInt32BE(data, stscOff + 12)
        for (i in 0 until stscCount) {
          stscEntries.add(
            StscEntry(
              firstChunk = readInt32BE(data, stscOff + 16 + i * 12),
              samplesPerChunk = readInt32BE(data, stscOff + 20 + i * 12),
              sampleDescIndex = readInt32BE(data, stscOff + 24 + i * 12),
            ),
          )
        }
      }
      if (stscEntries.isEmpty()) {
        log { "  FAIL: no stsc entries" }
        offset = trakEnd
        continue
      }

      val stszRes = findAtom(data, stblDataStart, stblEnd, "stsz")
      if (stszRes == null) {
        offset = trakEnd
        continue
      }
      val (stszOff, _) = stszRes
      val defaultSampleSize = readInt32BE(data, stszOff + 12)
      val sampleCount = readInt32BE(data, stszOff + 16)
      if (sampleCount <= 0) {
        log { "  FAIL: no samples" }
        offset = trakEnd
        continue
      }
      val sampleSizes =
        if (defaultSampleSize != 0) {
          IntArray(sampleCount) { defaultSampleSize }
        } else {
          IntArray(sampleCount) { i -> readInt32BE(data, stszOff + 20 + i * 4) }
        }

      val co64Result = findAtom(data, stblDataStart, stblEnd, "co64")
      val stcoResult = findAtom(data, stblDataStart, stblEnd, "stco")
      val coResult = co64Result ?: stcoResult
      if (coResult == null) {
        offset = trakEnd
        continue
      }
      val coOff = coResult.first
      val coType = if (co64Result != null) "co64" else "stco"
      val chunkCount = readInt32BE(data, coOff + 12)
      val chunkOffsets =
        LongArray(chunkCount) { i ->
          if (coType == "co64") {
            readInt64BE(data, coOff + 16 + i * 8)
          } else {
            readUInt32BE(data, coOff + 16 + i * 4)
          }
        }
      log { "  $coType: $chunkCount chunks" }

      val sampleToChunk = IntArray(sampleCount) { -1 }
      var currentSample = 0
      for (scIdx in stscEntries.indices) {
        val entry = stscEntries[scIdx]
        val nextFirstChunk =
          if (scIdx + 1 <
            stscEntries.size
          ) {
            stscEntries[scIdx + 1].firstChunk
          } else {
            chunkCount + 1
          }
        for (chunk in entry.firstChunk until nextFirstChunk) {
          for (s in 0 until entry.samplesPerChunk) {
            if (currentSample < sampleCount) {
              sampleToChunk[currentSample] = chunk - 1
              currentSample++
            }
          }
        }
      }

      val sampleOffsets = LongArray(sampleCount)
      val chunkAccumulatedOffset = LongArray(chunkCount)
      for (i in 0 until sampleCount) {
        val chunk = sampleToChunk[i]
        if (chunk !in 0 until chunkCount) continue
        sampleOffsets[i] = chunkOffsets[chunk] + chunkAccumulatedOffset[chunk]
        chunkAccumulatedOffset[chunk] += sampleSizes[i].toLong()
      }

      val sampleTimes = LongArray(sampleCount)
      val timescale = if (mdhdTimescale > 0) mdhdTimescale else mvhdTimescale
      var sampleIdx = 0
      var mediaTime = 0L
      for (entry in sttsEntries) {
        for (i in 0 until entry.sampleCount) {
          if (sampleIdx < sampleCount) {
            sampleTimes[sampleIdx] = mediaTime
            mediaTime += entry.sampleDelta.toLong()
            sampleIdx++
          }
        }
        if (sampleIdx >= sampleCount) break
      }

      val keyframes =
        if (keyframeIndices.isNotEmpty()) {
          keyframeIndices.mapNotNull { idx ->
            val si = idx - 1
            if (si in 0 until sampleCount) {
              KeyframeEntry(
                sampleIndex = idx,
                timeMs = if (timescale > 0) sampleTimes[si] * 1000L / timescale else 0L,
                byteOffset = sampleOffsets[si],
                byteSize = sampleSizes[si],
              )
            } else {
              null
            }
          }
        } else {
          sampleTimes.indices.mapNotNull { si ->
            KeyframeEntry(
              sampleIndex = si + 1,
              timeMs = if (timescale > 0) sampleTimes[si] * 1000L / timescale else 0L,
              byteOffset = sampleOffsets[si],
              byteSize = sampleSizes[si],
            )
          }
        }

      return MoovInfo(
        timescale = timescale,
        duration =
          if (mediaTime > 0) {
            mediaTime
          } else if (mvhdTimescale == timescale) {
            mvhdDuration
          } else {
            0L
          },
        codecType = codec.mime,
        width = width,
        height = height,
        rotation = rotation,
        codecConfigNalUnits = codec.nalUnits,
        nalLengthSize = codec.nalLengthSize,
        keyframes = keyframes,
      )
    }

    return null
  }

  data class CodecConfig(
    val mime: String,
    val nalLengthSize: Int,
    val nalUnits: List<ByteArray>,
  )

  private fun parseAvcC(
    data: ByteArray,
    start: Int,
    end: Int,
  ): CodecConfig? {
    if (start + 7 > end || end > data.size) return null
    val nalLengthSize = ((data[start + 4].toInt() and 0x03) + 1).coerceIn(1, 4)
    val nalUnits = mutableListOf<ByteArray>()
    val numSps = data[start + 5].toInt() and 0x1F
    var pos = start + 6
    for (i in 0 until numSps) {
      if (pos + 2 > end) break
      val length = readUInt16BE(data, pos)
      pos += 2
      if (length <= 0 || pos + length > end) break
      nalUnits.add(data.copyOfRange(pos, pos + length))
      log { "  SPS[$i]: $length bytes" }
      pos += length
    }
    if (pos >= end) return CodecConfig("video/avc", nalLengthSize, nalUnits)
    val numPps = data[pos].toInt() and 0xFF
    pos++
    for (i in 0 until numPps) {
      if (pos + 2 > end) break
      val length = readUInt16BE(data, pos)
      pos += 2
      if (length <= 0 || pos + length > end) break
      nalUnits.add(data.copyOfRange(pos, pos + length))
      log { "  PPS[$i]: $length bytes" }
      pos += length
    }
    return CodecConfig("video/avc", nalLengthSize, nalUnits)
  }

  private fun parseHvcC(
    data: ByteArray,
    start: Int,
    end: Int,
  ): CodecConfig? {
    if (start + 23 > end || end > data.size) return null
    val nalLengthSize = ((data[start + 21].toInt() and 0x03) + 1).coerceIn(1, 4)
    val nalUnits = mutableListOf<ByteArray>()
    val numArrays = data[start + 22].toInt() and 0xFF
    var pos = start + 23
    for (arrayIndex in 0 until numArrays) {
      if (pos + 3 > end) break
      val nalType = data[pos].toInt() and 0x3F
      pos++
      val numNalus = readUInt16BE(data, pos)
      pos += 2
      for (nalIndex in 0 until numNalus) {
        if (pos + 2 > end) break
        val length = readUInt16BE(data, pos)
        pos += 2
        if (length <= 0 || pos + length > end) break
        nalUnits.add(data.copyOfRange(pos, pos + length))
        log { "  HEVC array=$arrayIndex type=$nalType nal=$nalIndex: $length bytes" }
        pos += length
      }
    }
    return CodecConfig("video/hevc", nalLengthSize, nalUnits)
  }

  private fun parseMoovDurationMs(moovData: MoovData): Long? {
    val data = moovData.bytes
    if (data.size < 16) return null
    val moovSize = readUInt32BE(data, 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val moovEnd = minOf(moovSize, data.size)
    val mvhdResult = findAtom(data, 8, moovEnd, "mvhd") ?: return null
    val mvhdOff = mvhdResult.first
    if (mvhdOff + 32 > data.size) return null

    val version = data[mvhdOff + 8].toInt() and 0xFF
    val timescaleOffset: Int
    val durationOffset: Int
    if (version == 1) {
      timescaleOffset = mvhdOff + 28
      durationOffset = mvhdOff + 32
      if (durationOffset + 8 > data.size) return null
    } else {
      timescaleOffset = mvhdOff + 20
      durationOffset = mvhdOff + 24
      if (durationOffset + 4 > data.size) return null
    }

    val timescale = readInt32BE(data, timescaleOffset)
    val duration =
      if (version == 1) {
        readInt64BE(data, durationOffset)
      } else {
        readUInt32BE(data, durationOffset)
      }
    return if (timescale > 0 && duration > 0L) {
      (duration * 1000L / timescale).takeIf { it > 0L }
    } else {
      null
    }
  }

  private fun selectKeyframe(
    moovInfo: MoovInfo,
    targetPercent: Float,
  ): KeyframeEntry? {
    if (moovInfo.keyframes.isEmpty()) return null

    val durationMs = moovInfo.durationMs() ?: moovInfo.keyframes.lastOrNull()?.timeMs ?: 0L

    val targetTimeMs = (durationMs * targetPercent).toLong()

    return moovInfo.keyframes.minByOrNull { kotlin.math.abs(it.timeMs - targetTimeMs) }
  }

  private fun MoovInfo.durationMs(): Long? =
    if (timescale > 0 && duration > 0L) {
      (duration * 1000L / timescale).takeIf { it > 0L }
    } else {
      null
    }

  internal suspend fun decodeKeyframe(
    moovInfo: MoovInfo,
    keyframeData: ByteArray,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      try {
        val mime = moovInfo.codecType
        val codecName =
          findBestDecoderForMime(mime)
            ?: return@withContext null.also { log { "DECODE: no codec found for $mime" } }

        log { "DECODE: using codec=$codecName for $mime" }

        val format = MediaFormat.createVideoFormat(mime, moovInfo.width, moovInfo.height)
        configureCodecSpecificData(format, moovInfo)

        val inputData =
          prepareDecoderInput(moovInfo, keyframeData)
            ?: return@withContext null.also { log { "DECODE: failed to prepare input data" } }

        val codec = MediaCodec.createByCodecName(codecName)
        codec.configure(format, null, null, 0)
        codec.start()

        try {
          decodeOneFrameInternal(codec, inputData, moovInfo.width, moovInfo.height, codecName)
        } finally {
          codec.stop()
          codec.release()
        }
      } catch (e: Exception) {
        log { "DECODE: exception: ${e.message}" }
        null
      }
    }

  internal fun decodeKeyframes(
    moovInfo: MoovInfo,
    keyframeDataList: List<ByteArray>,
  ): List<Bitmap?> {
    val mime = moovInfo.codecType
    val codecName = findBestDecoderForMime(mime)
    if (codecName == null) {
      log { "DECODE_BATCH: no codec for $mime" }
      return keyframeDataList.map { null }
    }

    val format = MediaFormat.createVideoFormat(mime, moovInfo.width, moovInfo.height)
    configureCodecSpecificData(format, moovInfo)

    val codec = MediaCodec.createByCodecName(codecName)
    codec.configure(format, null, null, 0)
    codec.start()

    try {
      return keyframeDataList.map { data ->
        val inputData = prepareDecoderInput(moovInfo, data)
        if (inputData == null) {
          null
        } else {
          codec.flush()
          decodeOneFrameInternal(codec, inputData, moovInfo.width, moovInfo.height, codecName)
        }
      }
    } finally {
      codec.stop()
      codec.release()
    }
  }

  internal fun decodeKeyframesRawImage(
    moovInfo: MoovInfo,
    keyframeDataList: List<ByteArray>,
    onFrame: (image: Image, outputFormat: MediaFormat, codecName: String, frameIndex: Int) -> Unit,
  ) {
    val mime = moovInfo.codecType
    val codecName = findBestDecoderForMime(mime) ?: return
    val format = MediaFormat.createVideoFormat(mime, moovInfo.width, moovInfo.height)
    configureCodecSpecificData(format, moovInfo)

    val codec = MediaCodec.createByCodecName(codecName)
    codec.configure(format, null, null, 0)
    codec.start()

    try {
      keyframeDataList.forEachIndexed { index, data ->
        val inputData = prepareDecoderInput(moovInfo, data) ?: return@forEachIndexed
        codec.flush()
        decodeOneFrameToImage(codec, inputData) { image, fmt ->
          onFrame(image, fmt, codecName, index)
        }
      }
    } finally {
      codec.stop()
      codec.release()
    }
  }

  private fun decodeOneFrameToImage(
    codec: MediaCodec,
    inputData: ByteArray,
    onImage: (image: Image, outputFormat: MediaFormat) -> Unit,
  ) {
    val inputIndex = codec.dequeueInputBuffer(500_000)
    if (inputIndex < 0) {
      log { "DECODE_IMAGE: dequeueInputBuffer timeout" }
      return
    }
    val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
    inputBuffer.clear()
    inputBuffer.put(inputData)
    codec.queueInputBuffer(inputIndex, 0, inputData.size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

    var outputFormat: MediaFormat? = null
    val deadlineMs = System.currentTimeMillis() + 2000
    while (true) {
      if (System.currentTimeMillis() > deadlineMs) {
        log { "DECODE_IMAGE: timeout waiting for output" }
        return
      }
      val info = MediaCodec.BufferInfo()
      val outputIndex = codec.dequeueOutputBuffer(info, 500_000)
      when {
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          outputFormat = codec.outputFormat
        }
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
        outputIndex < 0 -> {
          log { "DECODE_IMAGE: unexpected dequeue: $outputIndex" }
          return
        }
        else -> {
          val outputImage = runCatching { codec.getOutputImage(outputIndex) }.getOrNull()
          outputImage?.use { img ->
            onImage(img, outputFormat ?: codec.outputFormat)
          }
          codec.releaseOutputBuffer(outputIndex, false)
          return
        }
      }
    }
  }

  private fun decodeOneFrameInternal(
    codec: MediaCodec,
    inputData: ByteArray,
    fallbackWidth: Int,
    fallbackHeight: Int,
    codecName: String,
  ): Bitmap? {
    val inputIndex = codec.dequeueInputBuffer(500_000)
    if (inputIndex < 0) {
      log { "DECODE: dequeueInputBuffer timeout" }
      return null
    }
    val inputBuffer = codec.getInputBuffer(inputIndex) ?: return null
    inputBuffer.clear()
    inputBuffer.put(inputData)
    codec.queueInputBuffer(inputIndex, 0, inputData.size, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

    val deadlineMs = System.currentTimeMillis() + 2000L
    var outputIndex: Int
    var outputFormat: MediaFormat? = codec.outputFormat
    val info = MediaCodec.BufferInfo()
    while (true) {
      outputIndex = codec.dequeueOutputBuffer(info, 100_000)
      when {
        outputIndex >= 0 -> {
          if (info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            codec.releaseOutputBuffer(outputIndex, false)
            continue
          }
          break
        }
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          outputFormat = codec.outputFormat
          log { "DECODE: INFO_OUTPUT_FORMAT_CHANGED" }
        }
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (System.currentTimeMillis() > deadlineMs) {
            log { "DECODE: timeout waiting for output" }
            return null
          }
        }
        else -> {
          log { "DECODE: unexpected dequeue: $outputIndex" }
          return null
        }
      }
    }

    val outputImage = runCatching { codec.getOutputImage(outputIndex) }.getOrNull()
    val bitmap =
      if (outputImage != null) {
        log { "DECODE: Image path (YUV planes) -> libyuv" }
        outputImage.use { img ->
          toBitmap(img, outputFormat ?: codec.outputFormat, codecName)
        }
      } else {
        log { "DECODE: ByteBuffer path -> libyuv" }
        val outputBuffer = codec.getOutputBuffer(outputIndex)
        if (outputBuffer != null) {
          toBitmap(
            outputBuffer,
            info,
            outputFormat ?: codec.outputFormat,
            fallbackWidth,
            fallbackHeight,
            codecName,
          )
        } else {
          null
        }
      }
    codec.releaseOutputBuffer(outputIndex, false)
    return bitmap
  }

  private fun prepareDecoderInput(
    moovInfo: MoovInfo,
    keyframeData: ByteArray,
  ): ByteArray? {
    if (moovInfo.codecType != "video/avc" && moovInfo.codecType != "video/hevc") {
      return keyframeData
    }
    val annexB = ByteArrayOutputStream()
    for (nal in moovInfo.codecConfigNalUnits) {
      writeAnnexBNal(annexB, nal)
    }
    var pos = 0
    var frameNalCount = 0
    val lengthSize = moovInfo.nalLengthSize.coerceIn(1, 4)
    while (pos + lengthSize <= keyframeData.size) {
      val nalLen = readNalLength(keyframeData, pos, lengthSize)
      pos += lengthSize
      if (nalLen <= 0 || pos + nalLen > keyframeData.size) break
      writeAnnexBNal(annexB, keyframeData, pos, nalLen)
      frameNalCount++
      pos += nalLen
    }
    if (frameNalCount == 0) {
      log { "DECODE: no NAL units found in keyframe sample" }
      return null
    }
    return annexB.toByteArray()
  }

  private fun configureCodecSpecificData(
    format: MediaFormat,
    moovInfo: MoovInfo,
  ) {
    val nalUnits = moovInfo.codecConfigNalUnits
    if (nalUnits.isEmpty()) return

    if (moovInfo.codecType == "video/avc") {
      val sps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
      val pps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
      if (sps != null) {
        format.setByteBuffer("csd-0", ByteBuffer.wrap(buildAnnexB(listOf(sps))))
      }
      if (pps != null) {
        format.setByteBuffer("csd-1", ByteBuffer.wrap(buildAnnexB(listOf(pps))))
      }
    } else if (moovInfo.codecType == "video/hevc") {
      format.setByteBuffer("csd-0", ByteBuffer.wrap(buildAnnexB(nalUnits)))
    }
  }

  private fun buildAnnexB(nalUnits: List<ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    for (nal in nalUnits) {
      writeAnnexBNal(out, nal)
    }
    return out.toByteArray()
  }

  private fun writeAnnexBNal(
    out: ByteArrayOutputStream,
    nal: ByteArray,
  ) {
    writeAnnexBNal(out, nal, 0, nal.size)
  }

  private fun writeAnnexBNal(
    out: ByteArrayOutputStream,
    data: ByteArray,
    offset: Int,
    length: Int,
  ) {
    out.write(byteArrayOf(0, 0, 0, 1))
    out.write(data, offset, length)
  }

  private fun readNalLength(
    data: ByteArray,
    offset: Int,
    lengthSize: Int,
  ): Int {
    var value = 0
    for (i in 0 until lengthSize) {
      value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
    }
    return value
  }

  private val decoderNameCache =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, String>(MAX_DECODER_NAME_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
          size > MAX_DECODER_NAME_CACHE_ENTRIES
      },
    )

  private fun findBestDecoderForMime(mime: String): String? {
    decoderNameCache[mime]?.let { return it }
    val result =
      MediaCodecList(MediaCodecList.ALL_CODECS)
        .codecInfos
        .asSequence()
        .filter { codecInfo ->
          !codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }.sortedWith(
          compareBy<MediaCodecInfo> {
            it.name.startsWith("OMX.google.", ignoreCase = true) ||
              it.name.startsWith("c2.android.", ignoreCase = true)
          }.thenBy { it.name },
        ).firstOrNull()
        ?.name ?: return null
    decoderNameCache[mime] = result
    return result
  }

  private fun toBitmap(
    image: Image,
    outputFormat: MediaFormat,
    codecName: String,
  ): Bitmap? {
    if (image.planes.size < 3) {
      log { "YUV image: unsupported format=${image.format} planes=${image.planes.size}" }
      return null
    }

    val crop = image.cropRect ?: Rect(0, 0, image.width, image.height)
    val width = crop.width() and 1.inv()
    val height = crop.height() and 1.inv()
    if (width <= 0 || height <= 0) return null

    val planeY = image.planes[0]
    val planeU = image.planes[1]
    val planeV = image.planes[2]

    val colorStandard = outputFormat.getIntegerOrDefault(MediaFormat.KEY_COLOR_STANDARD, 1)
    val colorRange = outputFormat.getIntegerOrDefault(MediaFormat.KEY_COLOR_RANGE, 2)

    val forceNV21 = codecName.startsWith("OMX.qcom.", ignoreCase = true)

    return YuvBitmapConverter.imageToBitmap(
      yBuf = planeY.buffer,
      yRowStride = planeY.rowStride,
      yPixelStride = planeY.pixelStride,
      uBuf = planeU.buffer,
      uRowStride = planeU.rowStride,
      uPixelStride = planeU.pixelStride,
      vBuf = planeV.buffer,
      vRowStride = planeV.rowStride,
      vPixelStride = planeV.pixelStride,
      cropLeft = crop.left,
      cropTop = crop.top,
      cropWidth = width,
      cropHeight = height,
      colorStandard = colorStandard,
      colorRange = colorRange,
      forceNV21 = forceNV21,
    )
  }

  private fun toBitmap(
    outputBuffer: ByteBuffer,
    info: MediaCodec.BufferInfo,
    outputFormat: MediaFormat,
    fallbackWidth: Int,
    fallbackHeight: Int,
    codecName: String,
  ): Bitmap? {
    if (info.size <= 0) return null

    val data = ByteArray(info.size)
    outputBuffer.position(info.offset)
    outputBuffer.get(data, 0, info.size)
    val jpegBitmap = tryDecodeJpeg(data)
    if (jpegBitmap != null) return jpegBitmap

    val width = outputFormat.getIntegerOrDefault(MediaFormat.KEY_WIDTH, fallbackWidth)
    val height = outputFormat.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, fallbackHeight)
    val cropLeft = outputFormat.getIntegerOrDefault("crop-left", 0)
    val cropTop = outputFormat.getIntegerOrDefault("crop-top", 0)
    val cropRight = outputFormat.getIntegerOrDefault("crop-right", width - 1)
    val cropBottom = outputFormat.getIntegerOrDefault("crop-bottom", height - 1)
    val outputWidth = (cropRight - cropLeft + 1).coerceAtLeast(1)
    val outputHeight = (cropBottom - cropTop + 1).coerceAtLeast(1)
    val stride = outputFormat.getIntegerOrDefault("stride", width).coerceAtLeast(width)
    val sliceHeight = outputFormat.getIntegerOrDefault("slice-height", height).coerceAtLeast(height)
    val colorFormat = outputFormat.getIntegerOrDefault(MediaFormat.KEY_COLOR_FORMAT, 0)
    val colorStandard = outputFormat.getIntegerOrDefault(MediaFormat.KEY_COLOR_STANDARD, 1)
    val colorRange = outputFormat.getIntegerOrDefault(MediaFormat.KEY_COLOR_RANGE, 2)
    val forceNV21 = codecName.startsWith("OMX.qcom.", ignoreCase = true)

    return try {
      YuvBitmapConverter.bufferToBitmap(
        yuvBuffer = outputBuffer,
        offset = info.offset,
        colorFormat = colorFormat,
        stride = stride,
        sliceHeight = sliceHeight,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropWidth = outputWidth,
        cropHeight = outputHeight,
        colorStandard = colorStandard,
        colorRange = colorRange,
        forceNV21 = forceNV21,
      )
    } catch (e: Exception) {
      log { "YUV decode error: ${e.message}" }
      null
    }
  }

  private fun tryDecodeJpeg(data: ByteArray): Bitmap? =
    runCatching {
      BitmapFactory.decodeByteArray(data, 0, data.size)
    }.getOrNull()

  private fun MediaFormat.getIntegerOrDefault(
    key: String,
    defaultValue: Int,
  ): Int =
    if (containsKey(key)) {
      runCatching { getInteger(key) }.getOrDefault(defaultValue)
    } else {
      defaultValue
    }

  private fun readInt32BE(
    data: ByteArray,
    offset: Int,
  ): Int =
    ((data[offset].toInt() and 0xFF) shl 24) or
      ((data[offset + 1].toInt() and 0xFF) shl 16) or
      ((data[offset + 2].toInt() and 0xFF) shl 8) or
      (data[offset + 3].toInt() and 0xFF)

  private fun readUInt16BE(
    data: ByteArray,
    offset: Int,
  ): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or
      (data[offset + 1].toInt() and 0xFF)

  private fun readUInt32BE(
    data: ByteArray,
    offset: Int,
  ): Long = readInt32BE(data, offset).toLong() and 0xFFFFFFFFL

  private fun readInt64BE(
    data: ByteArray,
    offset: Int,
  ): Long =
    ((data[offset].toLong() and 0xFF) shl 56) or
      ((data[offset + 1].toLong() and 0xFF) shl 48) or
      ((data[offset + 2].toLong() and 0xFF) shl 40) or
      ((data[offset + 3].toLong() and 0xFF) shl 32) or
      ((data[offset + 4].toLong() and 0xFF) shl 24) or
      ((data[offset + 5].toLong() and 0xFF) shl 16) or
      ((data[offset + 6].toLong() and 0xFF) shl 8) or
      (data[offset + 7].toLong() and 0xFF)

  @Suppress("NOTHING_TO_INLINE")
  private inline fun log(msg: () -> String) {
    Logger.d("BUG4_Chapters", msg())
  }

  companion object {
    private const val MAX_MOOV_CACHE_ENTRIES = 32
    private const val MAX_DECODER_NAME_CACHE_ENTRIES = 16
  }
}
