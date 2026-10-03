package app.gyrolet.mpvrx.domain.cloud

import okhttp3.Response

/** Never treat a full-file response as bytes from a nonzero offset. */
internal fun Response.readContainerRange(start: Long, requestedSize: Int): ByteArray? {
  if (start < 0 || requestedSize !in 1..32 * 1024 * 1024 || code != 206) return null
  val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    .matchEntire(header("Content-Range").orEmpty()) ?: return null
  val actualStart = match.groupValues[1].toLongOrNull() ?: return null
  val actualEnd = match.groupValues[2].toLongOrNull() ?: return null
  if (actualStart != start || actualEnd < start || actualEnd - start >= requestedSize) return null
  val total = match.groupValues[3].toLongOrNull()
  if (total != null && actualEnd >= total) return null
  val count = (actualEnd - start + 1).toInt()
  val source = body ?: return null
  // Also cap chunked/misdeclared bodies rather than trusting Content-Length.
  return source.byteStream().readNBytes(count).takeIf { it.size == count }
}
