package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.RandomAccessFile
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import app.gyrolet.mpvrx.domain.cloud.CloudLog as Logger

class MkvKeyframeExtractor(
  private val okHttpClient: OkHttpClient,
) {
  private val cache =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, Mp4KeyframeExtractor.ParsedMoov>(MAX_PARSED_MKV_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<String, Mp4KeyframeExtractor.ParsedMoov>?,
        ): Boolean = size > MAX_PARSED_MKV_CACHE_ENTRIES
      },
    )
  private val keyMutexes = ConcurrentHashMap<String, Mutex>()

  suspend fun loadParsedMkv(url: String, stableKey: String? = null): Mp4KeyframeExtractor.ParsedMoov? =
    withContext(Dispatchers.IO) {
      val cacheKey = stableKey ?: mkvCacheKey(url)

      cache[cacheKey]?.let { return@withContext it }

      MoovIndexCache.ensureLoadedFromDisk(cacheKey)
      MoovIndexCache.get(cacheKey)?.parsed?.let { return@withContext it }

      val lock = keyMutexes.computeIfAbsent(cacheKey) { Mutex() }
      try {
        lock.withLock {
          cache[cacheKey]?.let { return@withLock it }

          val parsed = doLoadParsedMkv(url, cacheKey)
          if (parsed != null) {
            cache[cacheKey] = parsed
          }
          parsed
        }
      } finally {
        keyMutexes.remove(cacheKey, lock)
      }
    }

  private suspend fun doLoadParsedMkv(
    url: String,
    cacheKey: String,
  ): Mp4KeyframeExtractor.ParsedMoov? {
    val contentLength = httpHead(url)
    Logger.d(BUG4_TAG, "MKV httpHead: $contentLength for ${url.take(80)}")
    if (contentLength == null || contentLength < 4096) return null

    val headData = httpRange(url, 0, minOf(HEAD_SIZE.toLong(), contentLength).toInt())
    if (headData == null || headData.size < 32) return null
    Logger.d(BUG4_TAG, "MKV head: ${headData.size} bytes")

    val (segOff, segSizeLen) =
      findSegmentStart(headData)
        ?: return null.also { Logger.w(BUG4_TAG, "MKV: cannot find Segment in head") }
    val segmentDataOff = segOff + 4 + segSizeLen
    Logger.d(BUG4_TAG, "MKV Segment: offset=$segOff dataStart=$segmentDataOff sizeLen=$segSizeLen")

    var seekPositions: Map<Long, Long>? = null
    val headSeekHead = findEbmlElement(headData, segmentDataOff.toInt(), headData.size, SEEK_HEAD_ID)
    if (headSeekHead != null) {
      val (shOff, shSize) = headSeekHead
      if (shOff + shSize <= headData.size) {
        seekPositions = parseSeekHead(headData, shOff.toInt(), shSize.toInt())
        Logger.d(
          BUG4_TAG,
          "MKV SeekHead found in head: ${seekPositions.size} entries, hasChapters=${seekPositions.containsKey(
            SEEK_CHAPTERS,
          )}",
        )
      }
    }

    if (seekPositions == null) {
      val tailSize = minOf(SEARCH_TAIL_SIZE.toLong(), contentLength).toInt()
      val tailData = httpRange(url, contentLength - tailSize, tailSize)
      if (tailData != null) {
        Logger.d(BUG4_TAG, "MKV tail: ${tailData.size} bytes, searching for SeekHead...")
        val sh = findEbmlElementByScan(tailData, 0, tailData.size, SEEK_HEAD_ID)
        if (sh != null) {
          val (shOff, shSize) = sh
          if (shOff + shSize <= tailData.size) {
            seekPositions = parseSeekHead(tailData, shOff.toInt(), shSize.toInt())
            Logger.d(BUG4_TAG, "MKV SeekHead found in tail: ${seekPositions.size} entries")
          }
        }
      }
    }

    if (seekPositions == null) {
      Logger.w(BUG4_TAG, "MKV: no SeekHead, trying direct Cues search...")
      return parseFromTailDirect(url, contentLength, segmentDataOff)
    }

    val cuesPos = seekPositions[SEEK_CUES]
    val tracksPos = seekPositions[SEEK_TRACKS]
    val infoPos = seekPositions[SEEK_INFO]

    if (cuesPos == null || tracksPos == null) {
      Logger.w(BUG4_TAG, "MKV: SeekHead missing Cues or Tracks. cues=$cuesPos tracks=$tracksPos")
      return parseFromTailDirect(url, contentLength, segmentDataOff)
    }

    val tracksFileOff = segmentDataOff + tracksPos
    val tracksData = downloadRangeForOffset(url, contentLength, tracksFileOff, 65536) ?: return null
    val tracksBodyOff = skipElementHeader(tracksData, 0)
    val (mime, width, height, codecPrivate, trackNumber) =
      parseTracks(
        tracksData,
        tracksBodyOff,
      ) ?: return null
    Logger.d(
      BUG4_TAG,
      "MKV Tracks: $mime ${width}x$height codecPrivate=${codecPrivate?.size ?: 0} track=$trackNumber",
    )

    var timecodeScaleNs = 1_000_000L
    var durationMs: Long? = null
    if (infoPos != null) {
      val infoFileOff = segmentDataOff + infoPos
      val infoData = downloadRangeForOffset(url, contentLength, infoFileOff, 4096)
      if (infoData != null) {
        val (_, infoIdLen) = readElementId(infoData, 0)
        val (infoBodySize, infoSzLen) = readVint(infoData, infoIdLen)
        val infoBodyOff = infoIdLen + infoSzLen
        val infoBodyEnd = minOf(infoData.size, infoBodyOff + maxOf(infoBodySize, 1L).toInt())
        val (ts, dur) = parseInfo(infoData, infoBodyOff, timecodeScaleNs, infoBodyEnd)
        if (ts != null) timecodeScaleNs = ts
        if (dur != null) durationMs = dur
        Logger.d(BUG4_TAG, "MKV Info: timecodeScale=${timecodeScaleNs}ns duration=$durationMs")
      }
    }

    val cuesFileOff = segmentDataOff + cuesPos
    val cuesSize =
      minOf(
        512 * 1024L,
        (contentLength - cuesFileOff).coerceAtLeast(65536),
      ).toInt()
    val cuesData = downloadRangeForOffset(url, contentLength, cuesFileOff, cuesSize) ?: return null
    val cuesBodyOff = skipElementHeader(cuesData, 0)
    val keyframes = parseCues(cuesData, cuesBodyOff, segmentDataOff, timecodeScaleNs)
    Logger.d(BUG4_TAG, "MKV Cues: ${keyframes.size} keyframes from ${cuesData.size}B")

    if (keyframes.isEmpty()) return null

    var chapters: List<ChapterEntry> = emptyList()
    val chaptersPos = seekPositions?.get(SEEK_CHAPTERS)
    if (chaptersPos != null) {
      val chaptersFileOff = segmentDataOff + chaptersPos
      val chaptersData = downloadRangeForOffset(url, contentLength, chaptersFileOff, 65536)
      if (chaptersData != null) {
        val chaptersElem = findEbmlElementByScan(chaptersData, 0, chaptersData.size, CHAPTERS_ID)
        if (chaptersElem != null) {
          val (chaptersOff, _) = chaptersElem
          val chaptersBodyOff = skipElementHeader(chaptersData, chaptersOff.toInt())
          chapters = parseChapters(chaptersData, chaptersBodyOff, timecodeScaleNs)
          Logger.d(
            BUG4_TAG,
            "MKV Chapters: ${chapters.size} entries (found at off=$chaptersOff in ${chaptersData.size}B data)",
          )
        } else {
          Logger.w(BUG4_TAG, "MKV Chapters: CHAPTERS_ID not found in downloaded data")
        }
      }
    } else {
      Logger.d(BUG4_TAG, "MKV Chapters: not in SeekHead, skipping")
    }

    val codecConfig =
      if (codecPrivate != null && codecPrivate.isNotEmpty()) {
        parseCodecPrivate(mime, codecPrivate)
      } else {
        val nalSize = if (mime == "video/hevc") 4 else 4
        Mp4KeyframeExtractor.CodecConfig(mime, nalSize, emptyList())
      }
    if (codecConfig == null) return null

    val moovInfo =
      Mp4KeyframeExtractor.MoovInfo(
        timescale = (timecodeScaleNs / 1_000_000).toInt().coerceAtLeast(1),
        duration = durationMs ?: 0L,
        codecType = mime,
        width = width,
        height = height,
        rotation = 0,
        codecConfigNalUnits = codecConfig.nalUnits,
        nalLengthSize = codecConfig.nalLengthSize,
        keyframes = keyframes,
      )

    val parsed =
      Mp4KeyframeExtractor.ParsedMoov(
        contentLength = contentLength,
        moovByteSize = 0,
        moovInfo = moovInfo,
        videoTrackNumber = trackNumber,
        durationMs =
          durationMs ?: if (moovInfo.timescale > 0) moovInfo.duration * 1000 / moovInfo.timescale else null,
      )

    MoovIndexCache.put(
      cacheKey,
      MoovIndexCache.Entry(
        keyframes = keyframes,
        contentLength = contentLength,
        durationMs = parsed.durationMs,
        chapters = chapters,
        parsed = parsed,
      ),
    )
    Logger.d(BUG4_TAG, "MKV MoovIndexCache stored: ${keyframes.size} keyframes, ${chapters.size} chapters")

    return parsed
  }

  suspend fun downloadMkvKeyframe(
    url: String,
    clusterPos: Long,
    estimatedSize: Int,
    trackNumber: Long,
  ): ByteArray? =
    withContext(Dispatchers.IO) {
      if (estimatedSize <= 0) return@withContext null
      val clusterData = httpRange(url, clusterPos, estimatedSize)
      if (clusterData == null || clusterData.size < 16) return@withContext null

      extractKeyframeFromCluster(clusterData, trackNumber)
    }

  fun readMkvKeyframeLocal(
    filePath: String,
    clusterPos: Long,
    estimatedSize: Int,
    trackNumber: Long,
  ): ByteArray? {
    if (estimatedSize <= 0) return null
    return try {
      RandomAccessFile(filePath, "r").use { raf ->
        raf.seek(clusterPos)
        val buf = ByteArray(estimatedSize)
        val read = raf.read(buf)
        if (read < 16) return null
        extractKeyframeFromCluster(buf.copyOf(read), trackNumber)
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun readElementId(
    data: ByteArray,
    offset: Int,
  ): Pair<Long, Int> {
    val first = data[offset].toInt() and 0xFF
    val len =
      when {
        (first and 0x80) != 0 -> 1
        (first and 0x40) != 0 -> 2
        (first and 0x20) != 0 -> 3
        (first and 0x10) != 0 -> 4
        else -> return (first.toLong()) to 1
      }
    if (offset + len > data.size) return (first.toLong()) to 1

    var value = first.toLong() and 0xFF
    for (i in 1 until len) {
      value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
    }
    return value to len
  }

  private fun readVint(
    data: ByteArray,
    offset: Int,
  ): Pair<Long, Int> {
    if (offset >= data.size) return 0L to 0
    val first = data[offset].toInt() and 0xFF
    var len = 0
    var mask = 0x80
    while (mask != 0 && (first and mask) == 0) {
      len++
      mask = mask shr 1
    }
    len++
    if (len > 8 || offset + len > data.size) {
      return 0L to 1
    }

    val valueMask = (1L shl (8 - len)) - 1
    var value = (first.toLong() and valueMask)
    for (i in 1 until len) {
      value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
    }

    val allOnes = (1L shl (7 * len)) - 1
    return if (len > 1 && value == allOnes) {
      0L to len
    } else {
      value to len
    }
  }

  private fun findEbmlElement(
    data: ByteArray,
    start: Int,
    end: Int,
    elementId: Long,
  ): Pair<Long, Long>? {
    var off = start
    val searchEnd = minOf(data.size, end)
    while (off + 4 <= searchEnd) {
      val (id, idLen) = readElementId(data, off)
      if (id == elementId) {
        val (size, sizeLen) = readVint(data, off + idLen)
        val totalSize = size

        val effectiveSize =
          when {
            size == 0L -> minOf((searchEnd - off).toLong(), Int.MAX_VALUE.toLong())
            else -> size + idLen + sizeLen
          }
        return off.toLong() to effectiveSize
      }

      val (size, sizeLen) = readVint(data, off + idLen)
      val skip =
        when {
          size == 0L -> searchEnd - off
          size < 2L -> 4
          else -> (idLen + sizeLen + size).toInt().coerceAtLeast(idLen + sizeLen + 1)
        }
      off += skip
    }
    return null
  }

  private fun findEbmlElementByScan(
    data: ByteArray,
    start: Int,
    end: Int,
    elementId: Long,
  ): Pair<Long, Long>? {
    val idBytes = ebmlIdToBytes(elementId) ?: return null
    val searchEnd = minOf(data.size, end)

    var i = start
    while (i + idBytes.size + 1 <= searchEnd) {
      var match = true
      for (j in idBytes.indices) {
        if (data[i + j] != idBytes[j]) {
          match = false
          break
        }
      }
      if (match) {
        val (size, sizeLen) = readVint(data, i + idBytes.size)

        if (sizeLen in 1..8) {
          val totalSize =
            when {
              size == 0L -> minOf((searchEnd - i).toLong(), Int.MAX_VALUE.toLong())
              else -> size + idBytes.size + sizeLen
            }
          return i.toLong() to totalSize
        }
      }
      i++
    }
    return null
  }

  private fun ebmlIdToBytes(elementId: Long): ByteArray? =
    when {
      elementId <= 0xFFL -> byteArrayOf(elementId.toByte())
      elementId <= 0xFFFFL ->
        byteArrayOf(
          ((elementId shr 8) and 0xFF).toByte(),
          (elementId and 0xFF).toByte(),
        )
      elementId <= 0xFFFFFFL ->
        byteArrayOf(
          ((elementId shr 16) and 0xFF).toByte(),
          ((elementId shr 8) and 0xFF).toByte(),
          (elementId and 0xFF).toByte(),
        )
      elementId <= 0xFFFFFFFFL ->
        byteArrayOf(
          ((elementId shr 24) and 0xFF).toByte(),
          ((elementId shr 16) and 0xFF).toByte(),
          ((elementId shr 8) and 0xFF).toByte(),
          (elementId and 0xFF).toByte(),
        )
      else -> null
    }

  private fun findSegmentStart(headData: ByteArray): Pair<Long, Int>? {
    var off = 0
    while (off + 4 <= headData.size) {
      val (id, idLen) = readElementId(headData, off)
      if (id == 0x1A45DFA3L) {
        val (size, sizeLen) = readVint(headData, off + idLen)
        off += idLen + sizeLen + maxOf(size, 1).toInt()
        continue
      }
      if (id == SEGMENT_ID) {
        val (_, sizeLen) = readVint(headData, off + idLen)
        return off.toLong() to sizeLen
      }

      val (size, sizeLen) = readVint(headData, off + idLen)
      off += idLen + sizeLen + maxOf(size, 1L).toInt()
    }
    return null
  }

  private fun skipElementHeader(
    data: ByteArray,
    offset: Int,
  ): Int {
    val (_, idLen) = readElementId(data, offset)
    val (_, sizeLen) = readVint(data, offset + idLen)
    return offset + idLen + sizeLen
  }

  private fun parseSeekHead(
    data: ByteArray,
    offset: Int,
    elementSize: Int,
  ): Map<Long, Long> {
    val positions = mutableMapOf<Long, Long>()
    val end = minOf(data.size, offset + elementSize)
    var off = skipElementHeader(data, offset)
    while (off + 6 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val dataStart = off + idLen + sizeLen

      if (id == SEEK_ID) {
        var seekId: Long? = null
        var seekPos: Long? = null
        var innerOff = dataStart
        val innerEnd = minOf(data.size, dataStart + maxOf(size, 1L).toInt())
        while (innerOff + 3 <= innerEnd) {
          val (innerId, innerIdLen) = readElementId(data, innerOff)
          val (innerSize, innerSizeLen) = readVint(data, innerOff + innerIdLen)
          val valueOff = innerOff + innerIdLen + innerSizeLen
          val valueLen = maxOf(innerSize, 1L).toInt()
          when (innerId) {
            SEEK_ID_ID -> {
              val (v, _) = readElementId(data, valueOff)
              seekId = v
            }
            SEEK_POS_ID -> {
              seekPos = readUint(data, valueOff, minOf(valueLen, 8))
            }
          }
          innerOff += innerIdLen + innerSizeLen + valueLen
        }
        if (seekId != null && seekPos != null) {
          positions[seekId] = seekPos
        }
      }

      off += idLen + sizeLen + maxOf(size, 1L).toInt()
    }
    return positions
  }

  private fun parseInfo(
    data: ByteArray,
    offset: Int,
    defaultTimecodeScaleNs: Long,
    end: Int = data.size,
  ): Pair<Long?, Long?> {
    var timecodeScale: Long? = null
    var duration: Double? = null
    var off = offset
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val valueOff = off + idLen + sizeLen
      val valueLen = minOf(maxOf(size, 1L).toInt(), data.size - valueOff)
      val uintVal = if (valueLen > 0) readUint(data, valueOff, valueLen) else 0L
      when (id) {
        TIMECODE_SCALE_ID -> timecodeScale = uintVal
        DURATION_ID -> duration = readFloat(data, valueOff, valueLen)
      }
      off += idLen + sizeLen + maxOf(valueLen, 1)
    }
    val scale = timecodeScale ?: defaultTimecodeScaleNs
    val durMs = duration?.let { (it * scale / 1_000_000.0).toLong() }
    return scale to durMs
  }

  private fun parseTracks(
    data: ByteArray,
    offset: Int,
  ): Quintet? {
    var off = offset
    val end = data.size
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val dataStart = off + idLen + sizeLen

      if (id == TRACK_ENTRY_ID) {
        val result = parseTrackEntry(data, dataStart, minOf(data.size, dataStart + maxOf(size, 1L).toInt()))
        if (result != null) return result
      }

      off += idLen + sizeLen + maxOf(size, 1L).toInt()
    }
    return null
  }

  private fun parseTrackEntry(
    data: ByteArray,
    start: Int,
    end: Int,
  ): Quintet? {
    var trackNumber = 0L
    var trackType = 0L
    var codecId: String? = null
    var pixelWidth = 0
    var pixelHeight = 0
    var codecPrivate: ByteArray? = null

    var off = start
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val valueOff = off + idLen + sizeLen
      val valueLen = minOf(maxOf(size, 1L).toInt(), data.size - valueOff)

      when (id) {
        TRACK_NUMBER_ID -> trackNumber = readUint(data, valueOff, valueLen)
        TRACK_TYPE_ID -> trackType = readUint(data, valueOff, valueLen)
        CODEC_ID_ID -> codecId = String(data, valueOff, valueLen, Charsets.US_ASCII).trimEnd('\u0000')
        CODEC_PRIVATE_ID -> codecPrivate = data.copyOfRange(valueOff, valueOff + valueLen)
        VIDEO_ID -> {
          val (w, h) = parseVideo(data, valueOff, minOf(data.size, valueOff + valueLen))
          pixelWidth = w
          pixelHeight = h
        }
      }

      off += idLen + sizeLen + maxOf(valueLen, 1)
    }

    if (trackType != 1L || codecId.isNullOrBlank() || pixelWidth <= 0 || pixelHeight <= 0) return null
    val mime = codecIdToMime(codecId) ?: return null
    return Quintet(mime, pixelWidth, pixelHeight, codecPrivate, trackNumber)
  }

  private fun parseVideo(
    data: ByteArray,
    start: Int,
    end: Int,
  ): Pair<Int, Int> {
    var w = 0
    var h = 0
    var off = start
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val valueOff = off + idLen + sizeLen
      val valueLen = minOf(maxOf(size, 1L).toInt(), data.size - valueOff)
      when (id) {
        PIXEL_WIDTH_ID -> w = readUint(data, valueOff, valueLen).toInt()
        PIXEL_HEIGHT_ID -> h = readUint(data, valueOff, valueLen).toInt()
      }
      off += idLen + sizeLen + maxOf(valueLen, 1)
    }
    return w to h
  }

  private fun parseCues(
    data: ByteArray,
    offset: Int,
    segmentDataOff: Long,
    timecodeScaleNs: Long,
  ): List<Mp4KeyframeExtractor.KeyframeEntry> {
    val entries = mutableListOf<ClusterEntry>()

    var off = offset
    val end = minOf(data.size, offset + data.size.coerceAtMost(512 * 1024))
    while (off + 6 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val dataStart = off + idLen + sizeLen
      val dataEnd = minOf(data.size, dataStart + maxOf(size, 1L).toInt())

      if (id == CUE_POINT_ID && dataEnd > dataStart) {
        parseCuePoint(data, dataStart, dataEnd)?.let { entries.add(it) }
      }

      off += idLen + sizeLen + maxOf(size, 1L).toInt()
    }

    if (entries.isEmpty()) return emptyList()

    entries.sortBy { it.clusterPosition }

    return entries.mapIndexed { i, entry ->
      val absPos = segmentDataOff + entry.clusterPosition
      val estimatedSize =
        if (i + 1 < entries.size) {
          ((segmentDataOff + entries[i + 1].clusterPosition) - absPos)
            .toInt()
            .coerceIn(MIN_CLUSTER_SIZE, MAX_CLUSTER_SIZE)
        } else {
          MAX_CLUSTER_SIZE
        }
      Mp4KeyframeExtractor.KeyframeEntry(
        sampleIndex = i + 1,
        timeMs = (entry.cueTime * timecodeScaleNs / 1_000_000L),
        byteOffset = absPos,
        byteSize = estimatedSize,
      )
    }
  }

  private fun parseChapters(
    data: ByteArray,
    offset: Int,
    timecodeScaleNs: Long,
  ): List<ChapterEntry> {
    val chapters = mutableListOf<Pair<Long, String>>()
    var off = offset
    val end = data.size
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val dataStart = off + idLen + sizeLen
      val dataEnd = minOf(data.size, dataStart + maxOf(size, 1L).toInt())

      when (id) {
        EDITION_ENTRY_ID -> {
          parseEditionEntry(data, dataStart, dataEnd, timecodeScaleNs, chapters)
        }
      }

      off += idLen + sizeLen + maxOf(size, 1L).toInt()
    }

    chapters.sortBy { it.first }

    return chapters.mapIndexed { i, (start, title) ->
      val endMs = if (i + 1 < chapters.size) chapters[i + 1].first else Long.MAX_VALUE
      ChapterEntry(startTimeMs = start, endTimeMs = endMs, title = title)
    }
  }

  private fun parseEditionEntry(
    data: ByteArray,
    start: Int,
    end: Int,
    timecodeScaleNs: Long,
    chapters: MutableList<Pair<Long, String>>,
  ) {
    var off = start
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val dataStart = off + idLen + sizeLen
      val dataEnd = minOf(data.size, dataStart + maxOf(size, 1L).toInt())

      if (id == CHAPTER_ATOM_ID) {
        parseChapterAtom(data, dataStart, dataEnd, timecodeScaleNs, chapters)
      }

      off += idLen + sizeLen + maxOf(size, 1L).toInt()
    }
  }

  private fun parseChapterAtom(
    data: ByteArray,
    start: Int,
    end: Int,
    timecodeScaleNs: Long,
    chapters: MutableList<Pair<Long, String>>,
  ) {
    var startTimeMs: Long? = null
    var title: String? = null
    var off = start
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val valueOff = off + idLen + sizeLen
      val valueLen = minOf(maxOf(size, 1L).toInt(), data.size - valueOff)

      when (id) {
        CHAPTER_TIME_START_ID -> {
          val startTimeNs = readUint(data, valueOff, valueLen)
          startTimeMs = startTimeNs / 1_000_000L
        }
        CHAPTER_DISPLAY_ID -> {
          if (title == null) {
            title = parseChapterDisplay(data, valueOff, minOf(data.size, valueOff + valueLen))
          }
        }
      }

      off += idLen + sizeLen + maxOf(valueLen, 1)
    }

    if (startTimeMs != null && !title.isNullOrBlank()) {
      val formatted = "%d:%02d".format(startTimeMs / 60000, (startTimeMs % 60000) / 1000)
      Logger.d(BUG4_TAG, "MKV chapter: $formatted \"$title\"")
      chapters.add(startTimeMs to title!!)
    }
  }

  private fun parseChapterDisplay(
    data: ByteArray,
    start: Int,
    end: Int,
  ): String? {
    var off = start
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val valueOff = off + idLen + sizeLen
      val valueLen = minOf(maxOf(size, 1L).toInt(), data.size - valueOff)

      if (id == CHAPTER_STRING_ID && valueLen > 0) {
        return String(data, valueOff, valueLen, Charsets.UTF_8).trimEnd('\u0000')
      }

      off += idLen + sizeLen + maxOf(valueLen, 1)
    }
    return null
  }

  private fun parseCuePoint(
    data: ByteArray,
    start: Int,
    end: Int,
  ): ClusterEntry? {
    var cueTime: Long? = null
    var clusterPosition: Long? = null

    var off = start
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val valueOff = off + idLen + sizeLen
      val valueLen = minOf(maxOf(size, 1L).toInt(), data.size - valueOff)

      when (id) {
        CUE_TIME_ID -> cueTime = readUint(data, valueOff, valueLen)
        CUE_TRACK_POS_ID -> {
          val (cp, _) = parseCueTrackPositions(data, valueOff, minOf(data.size, valueOff + valueLen))
          if (cp != null) clusterPosition = cp
        }
      }

      off += idLen + sizeLen + maxOf(valueLen, 1)
    }

    return if (cueTime != null && clusterPosition != null) {
      ClusterEntry(cueTime, clusterPosition)
    } else {
      null
    }
  }

  private fun parseCueTrackPositions(
    data: ByteArray,
    start: Int,
    end: Int,
  ): Pair<Long?, Long?> {
    var clusterPos: Long? = null
    var cueBlockNumber: Long? = null

    var off = start
    while (off + 3 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, sizeLen) = readVint(data, off + idLen)
      val valueOff = off + idLen + sizeLen
      val valueLen = minOf(maxOf(size, 1L).toInt(), data.size - valueOff)

      when (id) {
        CLUSTER_POS_ID -> clusterPos = readUint(data, valueOff, valueLen)
        BLOCK_NUMBER_ID -> cueBlockNumber = readUint(data, valueOff, valueLen)
      }

      off += idLen + sizeLen + maxOf(valueLen, 1)
    }

    return clusterPos to cueBlockNumber
  }

  private fun extractKeyframeFromCluster(
    clusterData: ByteArray,
    targetTrack: Long,
  ): ByteArray? {
    if (clusterData.size < 8) return null

    val (clusterId, clusterIdLen) = readElementId(clusterData, 0)
    if (clusterId != CLUSTER_ID) return null
    var off = clusterIdLen
    val (_, sizeLen) = readVint(clusterData, off)
    off += sizeLen

    while (off + 2 < clusterData.size) {
      val (id, idLen) = readElementId(clusterData, off)
      val (size, szLen) = readVint(clusterData, off + idLen)
      val dataStart = off + idLen + szLen
      val rawDataEnd = minOf(clusterData.size.toLong(), dataStart + maxOf(size, 1L)).toInt()

      when (id) {
        0xE7L -> { }
        SIMPLE_BLOCK_ID -> {
          val result = extractSimpleBlock(clusterData, dataStart, rawDataEnd, targetTrack)
          if (result != null) return result
        }
        BLOCK_GROUP_ID -> {
          val result = extractBlockGroup(clusterData, dataStart, rawDataEnd, targetTrack)
          if (result != null) return result
        }
      }

      off = rawDataEnd
    }
    return null
  }

  private fun extractSimpleBlock(
    data: ByteArray,
    start: Int,
    end: Int,
    targetTrack: Long,
  ): ByteArray? {
    if (end - start < 4) return null
    var off = start

    val (trackNum, tnLen) = readVint(data, off)
    off += tnLen
    if (off + 3 > end) return null

    off += 2

    val flags = data[off].toInt() and 0xFF
    off++

    val isKeyframe = (flags and 0x80) != 0
    if (!isKeyframe || trackNum != targetTrack) return null

    val lacing = (flags shr 1) and 0x03
    return if (lacing == 0) {
      data.copyOfRange(off, end)
    } else {
      extractFirstLacedFrame(data, off, end, lacing)
    }
  }

  private fun extractBlockGroup(
    data: ByteArray,
    start: Int,
    end: Int,
    targetTrack: Long,
  ): ByteArray? {
    var blockPayload: ByteArray? = null
    var hasReference = false
    var off = start
    while (off + 2 <= end) {
      val (id, idLen) = readElementId(data, off)
      val (size, szLen) = readVint(data, off + idLen)
      val valueOff = off + idLen + szLen
      val valueEnd = minOf(data.size, valueOff + maxOf(size, 1L).toInt())

      when (id) {
        BLOCK_ID -> {
          if (valueEnd - valueOff >= 4) {
            var bOff = valueOff
            val (trackNum, tnLen) = readVint(data, bOff)
            bOff += tnLen + 2
            if (bOff < valueEnd && trackNum == targetTrack) {
              blockPayload = data.copyOfRange(bOff, valueEnd)
            }
          }
        }
        0xFBL -> hasReference = true
      }

      off = valueEnd
    }

    return if (!hasReference && blockPayload != null) blockPayload else null
  }

  private fun extractFirstLacedFrame(
    data: ByteArray,
    start: Int,
    end: Int,
    lacing: Int,
  ): ByteArray? {
    if (start >= end) return null
    val numFrames = (data[start].toInt() and 0xFF) + 1
    if (numFrames <= 0) return null

    return when (lacing) {
      1 -> extractFirstXiphFrame(data, start + 1, end, numFrames)
      2 -> {
        val totalData = end - start - 1
        val frameSize = totalData / numFrames
        if (frameSize <= 0) {
          null
        } else {
          data.copyOfRange(start + 1, start + 1 + frameSize)
        }
      }
      3 -> extractFirstEbmlLacedFrame(data, start + 1, end, numFrames)
      else -> data.copyOfRange(start, end)
    }
  }

  private fun extractFirstXiphFrame(
    data: ByteArray,
    start: Int,
    end: Int,
    numFrames: Int,
  ): ByteArray? {
    var off = start

    val sizes = mutableListOf<Int>()
    var totalSize = 0
    for (i in 0 until numFrames - 1) {
      var frameSize = 0
      while (off < end) {
        val b = data[off].toInt() and 0xFF
        off++
        frameSize += b
        if (b != 255) break
        if (off >= end) return null
      }
      sizes.add(frameSize)
      totalSize += frameSize
    }

    val lastFrameSize = end - off - totalSize
    if (lastFrameSize <= 0) return null
    sizes.add(lastFrameSize)

    return if (sizes.isNotEmpty() && sizes[0] > 0) {
      data.copyOfRange(off, off + sizes[0])
    } else {
      null
    }
  }

  private fun extractFirstEbmlLacedFrame(
    data: ByteArray,
    start: Int,
    end: Int,
    numFrames: Int,
  ): ByteArray? {
    var off = start

    val (firstSize, fsLen) = readVint(data, off)
    off += fsLen
    if (firstSize <= 0 || off + firstSize > end) return null

    return data.copyOfRange(off, (off + firstSize.toInt()).coerceAtMost(end))
  }

  private suspend fun httpHead(url: String): Long? {
    ContentLengthCache.get(url)?.let { return it }
    return try {
      val request =
        videoMetadataRequest(url)
          .head()
          .header("Accept", "*/*")
          .build()
      okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val cl = response.header("Content-Length")?.toLongOrNull()
        if (cl != null && cl > 0) {
          ContentLengthCache.put(url, cl)
        }
        cl
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      null
    }
  }

  internal suspend fun httpRange(
    url: String,
    start: Long,
    size: Int,
  ): ByteArray? {
    if (size <= 0) return null
    val end = start + size - 1
    return try {
      val request =
        videoMetadataRequest(url)
          .header("Range", "bytes=$start-$end")
          .header("Accept", "*/*")
          .header("Accept-Encoding", "identity")
          .build()
      Logger.d(BUG4_TAG, "MKV httpRange: $start-$end (${size}B)")
      okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful && response.code != 206) {
          Logger.d(BUG4_TAG, "MKV httpRange FAIL: code=${response.code}")
          return null
        }
        val body = response.body ?: return null
        response.readContainerRange(start, size)
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Logger.d(BUG4_TAG, "MKV httpRange EXCEPTION: ${e.message}")
      null
    }
  }

  private suspend fun downloadRangeForOffset(
    url: String,
    contentLength: Long,
    fileOffset: Long,
    sizeHint: Int,
  ): ByteArray? {
    val clampedStart = fileOffset.coerceIn(0, contentLength - 1)
    val size = minOf(sizeHint.toLong(), contentLength - clampedStart).toInt()
    return httpRange(url, clampedStart, size)
  }

  private suspend fun parseFromTailDirect(
    url: String,
    contentLength: Long,
    segmentDataOff: Long,
  ): Mp4KeyframeExtractor.ParsedMoov? {
    val tailSize = minOf(SEARCH_TAIL_SIZE * 2L, contentLength).toInt()
    val tailData = httpRange(url, contentLength - tailSize, tailSize) ?: return null

    val cues = findEbmlElementByScan(tailData, 0, tailData.size, CUES_ID)
    if (cues == null) {
      Logger.w(BUG4_TAG, "MKV direct: no Cues found in tail")
      return null
    }

    val (cuesDataOff, cuesTotalSize) = cues
    val dataLen = minOf(cuesTotalSize.toInt(), tailData.size - cuesDataOff.toInt())
    val cuesData = tailData.copyOfRange(cuesDataOff.toInt(), cuesDataOff.toInt() + dataLen)

    val moovInfo =
      Mp4KeyframeExtractor.MoovInfo(
        timescale = 1000,
        duration = 0L,
        codecType = "video/avc",
        width = 1920,
        height = 1080,
        rotation = 0,
        codecConfigNalUnits = emptyList(),
        nalLengthSize = 4,
        keyframes = parseCues(cuesData, 0, segmentDataOff, 1_000_000L),
      )

    return Mp4KeyframeExtractor.ParsedMoov(
      contentLength = contentLength,
      moovByteSize = dataLen,
      moovInfo = moovInfo,
      durationMs = null,
    )
  }

  private fun codecIdToMime(codecId: String): String? =
    when (codecId) {
      "V_MPEG4/ISO/AVC" -> "video/avc"
      "V_MPEGH/ISO/HEVC" -> "video/hevc"
      "V_MPEG4/ISO/ASP" -> "video/mp4v-es"
      "V_VP8" -> "video/x-vnd.on2.vp8"
      "V_VP9" -> "video/x-vnd.on2.vp9"
      "V_AV1" -> "video/av01"
      else -> null
    }

  private fun parseCodecPrivate(
    mime: String,
    data: ByteArray,
  ): Mp4KeyframeExtractor.CodecConfig? =
    when (mime) {
      "video/avc" -> parseAvcC(data)
      "video/hevc" -> parseHvcC(data)
      else -> Mp4KeyframeExtractor.CodecConfig(mime, 4, emptyList())
    }

  private fun parseAvcC(data: ByteArray): Mp4KeyframeExtractor.CodecConfig? {
    if (data.size < 7) return null
    val nalLengthSize = ((data[4].toInt() and 0x03) + 1).coerceIn(1, 4)
    val nalUnits = mutableListOf<ByteArray>()
    val numSps = data[5].toInt() and 0x1F
    var pos = 6
    for (i in 0 until numSps) {
      if (pos + 2 > data.size) break
      val length = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
      pos += 2
      if (length <= 0 || pos + length > data.size) break
      nalUnits.add(data.copyOfRange(pos, pos + length))
      pos += length
    }
    if (pos >= data.size) return Mp4KeyframeExtractor.CodecConfig("video/avc", nalLengthSize, nalUnits)
    val numPps = data[pos].toInt() and 0xFF
    pos++
    for (i in 0 until numPps) {
      if (pos + 2 > data.size) break
      val length = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
      pos += 2
      if (length <= 0 || pos + length > data.size) break
      nalUnits.add(data.copyOfRange(pos, pos + length))
      pos += length
    }
    return Mp4KeyframeExtractor.CodecConfig("video/avc", nalLengthSize, nalUnits)
  }

  private fun parseHvcC(data: ByteArray): Mp4KeyframeExtractor.CodecConfig? {
    if (data.size < 23) return null
    val nalLengthSize = ((data[21].toInt() and 0x03) + 1).coerceIn(1, 4)
    val nalUnits = mutableListOf<ByteArray>()
    val numArrays = data[22].toInt() and 0xFF
    var pos = 23
    for (_a in 0 until numArrays) {
      if (pos + 3 > data.size) break
      pos++
      val numNalus = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
      pos += 2
      for (_n in 0 until numNalus) {
        if (pos + 2 > data.size) break
        val length = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        if (length <= 0 || pos + length > data.size) break
        nalUnits.add(data.copyOfRange(pos, pos + length))
        pos += length
      }
    }
    return Mp4KeyframeExtractor.CodecConfig("video/hevc", nalLengthSize, nalUnits)
  }

  private fun readUint(
    data: ByteArray,
    offset: Int,
    len: Int,
  ): Long {
    val maxLen = minOf(len, 8, data.size - offset)
    var v = 0L
    for (i in 0 until maxLen) {
      v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
    }
    return v
  }

  private fun readFloat(
    data: ByteArray,
    offset: Int,
    len: Int,
  ): Double? =
    when (len) {
      4 -> {
        if (offset + 4 > data.size) return null
        java.lang.Float
          .intBitsToFloat(readInt32BE(data, offset))
          .toDouble()
      }
      8 -> {
        if (offset + 8 > data.size) return null
        java.lang.Double.longBitsToDouble(readInt64BE(data, offset))
      }
      else -> null
    }

  private fun readInt32BE(
    data: ByteArray,
    offset: Int,
  ): Int =
    ((data[offset].toInt() and 0xFF) shl 24) or
      ((data[offset + 1].toInt() and 0xFF) shl 16) or
      ((data[offset + 2].toInt() and 0xFF) shl 8) or
      (data[offset + 3].toInt() and 0xFF)

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

  private fun mkvCacheKey(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    return parsed
      .newBuilder()
      .username("")
      .password("")
      .build()
      .toString()
  }

  private data class ClusterEntry(
    val cueTime: Long,
    val clusterPosition: Long,
  )

  private data class Quintet(
    val mime: String,
    val width: Int,
    val height: Int,
    val codecPrivate: ByteArray?,
    val trackNumber: Long,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Quintet

      if (width != other.width) return false
      if (height != other.height) return false
      if (trackNumber != other.trackNumber) return false
      if (mime != other.mime) return false
      if (!codecPrivate.contentEquals(other.codecPrivate)) return false

      return true
    }

    override fun hashCode(): Int {
      var result = width
      result = 31 * result + height
      result = 31 * result + trackNumber.hashCode()
      result = 31 * result + mime.hashCode()
      result = 31 * result + (codecPrivate?.contentHashCode() ?: 0)
      return result
    }
  }

  companion object {
    private const val BUG4_TAG = "BUG4_HttpExtractor"
    private const val MAX_PARSED_MKV_CACHE_ENTRIES = 32

    private const val SEGMENT_ID = 0x18538067L
    private const val SEEK_HEAD_ID = 0x114D9B74L
    private const val SEEK_ID = 0x4DBBL
    private const val SEEK_ID_ID = 0x53ABL
    private const val SEEK_POS_ID = 0x53ACL
    private const val INFO_ID = 0x1549A966L
    private const val TIMECODE_SCALE_ID = 0x2AD7B1L
    private const val DURATION_ID = 0x4489L
    private const val TRACKS_ID = 0x1654AE6BL
    private const val TRACK_ENTRY_ID = 0xAEL
    private const val TRACK_NUMBER_ID = 0xD7L
    private const val TRACK_TYPE_ID = 0x83L
    private const val CODEC_ID_ID = 0x86L
    private const val CODEC_PRIVATE_ID = 0x63A2L
    private const val VIDEO_ID = 0xE0L
    private const val PIXEL_WIDTH_ID = 0xB0L
    private const val PIXEL_HEIGHT_ID = 0xBAL
    private const val CUES_ID = 0x1C53BB6BL
    private const val CUE_POINT_ID = 0xBBL
    private const val CUE_TIME_ID = 0xB3L
    private const val CUE_TRACK_POS_ID = 0xB7L
    private const val CLUSTER_POS_ID = 0xF1L
    private const val BLOCK_NUMBER_ID = 0x5378L
    private const val CLUSTER_ID = 0x1F43B675L
    private const val SIMPLE_BLOCK_ID = 0xA3L
    private const val BLOCK_GROUP_ID = 0xA0L
    private const val BLOCK_ID = 0xA1L

    private const val SEEK_CUES = CUES_ID
    private const val SEEK_TRACKS = TRACKS_ID
    private const val SEEK_INFO = INFO_ID

    private const val CHAPTERS_ID = 0x1043A770L
    private const val EDITION_ENTRY_ID = 0x45B9L
    private const val CHAPTER_ATOM_ID = 0xB6L
    private const val CHAPTER_TIME_START_ID = 0x91L
    private const val CHAPTER_TIME_END_ID = 0x92L
    private const val CHAPTER_DISPLAY_ID = 0x80L
    private const val CHAPTER_STRING_ID = 0x85L

    private const val SEEK_CHAPTERS = CHAPTERS_ID

    private const val HEAD_SIZE = 65536
    private const val SEARCH_TAIL_SIZE = 1048576
    private const val MIN_CLUSTER_SIZE = 16384
    private const val MAX_CLUSTER_SIZE = 524288
  }
}
