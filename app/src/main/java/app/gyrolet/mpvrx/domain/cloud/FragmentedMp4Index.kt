package app.gyrolet.mpvrx.domain.cloud

/** Index ISO-BMFF fragment metadata through ranges; never download the intervening mdat. */
internal object FragmentedMp4Index {
  data class Defaults(val duration: Long = 0, val size: Long = 0, val flags: Long = 0)
  data class Track(val id: Long, val timescale: Long, val defaults: Defaults)
  data class Sample(val timeMs: Long, val offset: Long, val size: Int)
  data class Fragment(val samples: List<Sample>, val nextDecodeTime: Long)
  private data class Box(val type: String, val start: Int, val end: Int)

  fun track(moov: ByteArray): Track? = safely {
    val root = boxes(moov).firstOrNull { it.type == "moov" } ?: return@safely null
    val parts = boxes(moov, root.start, root.end)
    val mvex = parts.firstOrNull { it.type == "mvex" } ?: return@safely null
    for (trak in parts.filter { it.type == "trak" }) {
      val children = boxes(moov, trak.start, trak.end)
      val tkhd = children.firstOrNull { it.type == "tkhd" } ?: continue
      val mdia = children.firstOrNull { it.type == "mdia" } ?: continue
      val media = boxes(moov, mdia.start, mdia.end)
      val handler = media.firstOrNull { it.type == "hdlr" } ?: continue
      if (handler.end - handler.start < 12 || type(moov, handler.start + 8) != "vide") continue
      val mdhd = media.firstOrNull { it.type == "mdhd" } ?: continue
      val id = u32(moov, tkhd.start + if (moov[tkhd.start].toInt() == 1) 20 else 12)
      val timescale = u32(moov, mdhd.start + if (moov[mdhd.start].toInt() == 1) 20 else 12)
      if (id <= 0 || timescale <= 0) return@safely null
      val trex = boxes(moov, mvex.start, mvex.end).firstOrNull {
        it.type == "trex" && it.end - it.start >= 24 && u32(moov, it.start + 4) == id
      }
      return@safely Track(id, timescale, trex?.let {
        Defaults(u32(moov, it.start + 12), u32(moov, it.start + 16), u32(moov, it.start + 20))
      } ?: Defaults())
    }
    null
  }

  fun scan(contentLength: Long, track: Track, read: (Long, Int) -> ByteArray?): List<Sample>? = safely { scanRanges(contentLength, track, read) }

  private fun scanRanges(contentLength: Long, track: Track, read: (Long, Int) -> ByteArray?): List<Sample>? {
    val samples = mutableListOf<Sample>()
    var offset = 0L
    var decodeTime = 0L
    var atoms = 0
    var window = byteArrayOf()
    var windowStart = -1L
    fun range(at: Long, size: Int): ByteArray? {
      val relative = at - windowStart
      if (relative >= 0 && relative <= window.size && size <= window.size - relative.toInt()) {
        return window.copyOfRange(relative.toInt(), relative.toInt() + size)
      }
      val count = minOf(contentLength - at, maxOf(8192, size).toLong()).toInt()
      window = read(at, count) ?: return null
      windowStart = at
      return window.takeIf { it.size >= size }?.copyOfRange(0, size)
    }
    while (offset < contentLength) {
      if (++atoms > 100000 || contentLength - offset < 8) return null
      val header = range(offset, minOf(16, contentLength - offset).toInt()) ?: return null
      val rawSize = u32(header, 0)
      val size = when (rawSize) { 0L -> contentLength - offset; 1L -> u64(header, 8); else -> rawSize }
      val headerSize = if (rawSize == 1L) 16 else 8
      if (size < headerSize || size > contentLength - offset) return null
      if (type(header, 4) == "moof") {
        if (size > 1024 * 1024) return null
        val bytes = range(offset, size.toInt()) ?: return null
        val fragment = fragment(bytes, offset, track, decodeTime) ?: return null
        if (fragment.samples.any { it.offset < 0 || it.size <= 0 || it.offset > contentLength - it.size }) return null
        samples += fragment.samples
        decodeTime = fragment.nextDecodeTime
        if (samples.size > 250000) return null
      }
      offset += size
    }
    return samples.sortedBy { it.timeMs }.takeIf { it.isNotEmpty() }
  }

  fun fragment(bytes: ByteArray, offset: Long, track: Track, previousDecodeTime: Long): Fragment? = safely {
    require(track.timescale > 0)
    val root = boxes(bytes).firstOrNull { it.type == "moof" } ?: return@safely null
    var decodeTime = previousDecodeTime
    val result = mutableListOf<Sample>()
    val trafs = boxes(bytes, root.start, root.end).filter { it.type == "traf" }
    for ((trafIndex, traf) in trafs.withIndex()) {
      val children = boxes(bytes, traf.start, traf.end)
      val tfhd = children.firstOrNull { it.type == "tfhd" } ?: return@safely null
      val cursor = Cursor(bytes, tfhd.start, tfhd.end)
      val flags = cursor.u32() and 0xffffff
      val id = cursor.u32()
      if (id != track.id) continue
      // Without explicit base/default-base-is-moof a later traf depends on preceding track data.
      if (flags and 1L == 0L && flags and 0x20000L == 0L && trafIndex != 0) return@safely null
      val base = if (flags and 1L != 0L) cursor.u64() else offset
      if (flags and 2L != 0L) cursor.u32()
      val duration = if (flags and 8L != 0L) cursor.u32() else track.defaults.duration
      val size = if (flags and 16L != 0L) cursor.u32() else track.defaults.size
      val sampleFlags = if (flags and 32L != 0L) cursor.u32() else track.defaults.flags
      children.firstOrNull { it.type == "tfdt" }?.let {
        val time = Cursor(bytes, it.start, it.end)
        val version = time.u32() ushr 24
        decodeTime = if (version == 1L) time.u64() else if (version == 0L) time.u32() else return@safely null
      }
      var dataOffset: Long? = null
      for (run in children.filter { it.type == "trun" }) {
        val input = Cursor(bytes, run.start, run.end)
        val full = input.u32()
        val version = full ushr 24
        val runFlags = full and 0xffffff
        val count = input.u32()
        if (count > 250000 || version > 1) return@safely null
        if (runFlags and 1L != 0L) dataOffset = base + input.u32().toInt().toLong()
        val firstFlags = if (runFlags and 4L != 0L) input.u32() else sampleFlags
        var position = dataOffset ?: return@safely null
        repeat(count.toInt()) { index ->
          val sampleDuration = if (runFlags and 0x100L != 0L) input.u32() else duration
          val sampleSize = if (runFlags and 0x200L != 0L) input.u32() else size
          val flagsForSample = if (runFlags and 0x400L != 0L) input.u32() else if (index == 0) firstFlags else sampleFlags
          val composition = if (runFlags and 0x800L != 0L) {
            val value = input.u32(); if (version == 1L) value.toInt().toLong() else value
          } else 0L
          if (sampleSize <= 0 || sampleSize > Int.MAX_VALUE || sampleDuration <= 0 || position < 0 ||
            position > Long.MAX_VALUE - sampleSize || decodeTime > Long.MAX_VALUE - sampleDuration) return@safely null
          if (flagsForSample and 0x10000L == 0L && (flagsForSample ushr 24 and 3) != 1L) {
            result += Sample((decodeTime + composition) * 1000 / track.timescale, position, sampleSize.toInt())
          }
          position += sampleSize
          decodeTime += sampleDuration
        }
        dataOffset = position
      }
    }
    Fragment(result, decodeTime)
  }

  private class Cursor(val bytes: ByteArray, var at: Int, val end: Int) {
    fun u32(): Long { require(at <= end - 4); return u32(bytes, at).also { at += 4 } }
    fun u64(): Long { require(at <= end - 8); return u64(bytes, at).also { at += 8 } }
  }
  private fun boxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<Box> {
    val result = mutableListOf<Box>()
    var at = start
    while (at < end) {
      require(at <= end - 8)
      val size = u32(bytes, at)
      require(size >= 8 && size <= end - at)
      result += Box(type(bytes, at + 4), at + 8, at + size.toInt())
      at += size.toInt()
    }
    return result
  }
  private inline fun <T> safely(block: () -> T?): T? = try { block() } catch (_: IllegalArgumentException) { null } catch (_: IndexOutOfBoundsException) { null }
  private fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 255) }
  private fun u64(bytes: ByteArray, at: Int): Long = ((u32(bytes, at) shl 32) or u32(bytes, at + 4)).also { require(it >= 0) }
  private fun type(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)
}
