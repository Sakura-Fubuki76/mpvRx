package app.gyrolet.mpvrx.domain.cloud

/** Only walk top-level box boundaries; never mistake a signature inside mdat for metadata. */
internal fun findHeaderMoov(prefix: ByteArray, fileSize: Long): Pair<Int, Int>? {
  fun u32(at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (prefix[at + i].toLong() and 255) }
  var at = 0
  while (at <= prefix.size - 8) {
    val raw = u32(at)
    val headerSize = if (raw == 1L) 16 else 8
    if (at > prefix.size - headerSize) return null
    val size = when (raw) {
      0L -> fileSize - at
      1L -> {
        val high = u32(at + 8)
        if (high > Int.MAX_VALUE) return null
        (high shl 32) or u32(at + 12)
      }
      else -> raw
    }
    if (size < headerSize || size > fileSize - at) return null
    if (String(prefix, at + 4, 4, Charsets.US_ASCII) == "moov") {
      return if (size <= 16 * 1024 * 1024) at to size.toInt() else null
    }
    if (size > prefix.size - at) return null
    at += size.toInt()
  }
  return null
}
