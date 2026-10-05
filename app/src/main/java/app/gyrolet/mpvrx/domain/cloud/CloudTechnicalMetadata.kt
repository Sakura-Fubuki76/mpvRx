package app.gyrolet.mpvrx.domain.cloud

/** Technical fields from a bounded container header; no frame decoding or filename guesses. */
data class CloudTechnicalMetadata(
  val width: Int = 0, val height: Int = 0, val fps: Float = 0f,
  val videoCodec: String = "", val hasEmbeddedSubtitles: Boolean = false, val subtitleCodec: String = "",
)

internal fun parseCloudTechnicalMetadata(data: ByteArray): CloudTechnicalMetadata? = runCatching {
  if (data.size >= 4 && data.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte())) parseMkvTechnicalHeader(data)
  else parseMp4TechnicalHeader(data)
}.getOrNull()

private data class HeaderElement(val id: Long, val start: Int, val end: Int)

private fun ebmlElements(data: ByteArray, start: Int, end: Int): List<HeaderElement> {
  val result = mutableListOf<HeaderElement>()
  var offset = start
  while (offset < end) {
    fun vint(at: Int, keepMarker: Boolean): Pair<Long, Int>? {
      if (at >= end) return null
      val first = data[at].toInt() and 255
      val length = (1..8).firstOrNull { first and (0x80 shr (it - 1)) != 0 } ?: return null
      if (at + length > end || (keepMarker && length > 4)) return null
      var value = (if (keepMarker) first else first and ((0x80 shr (length - 1)) - 1)).toLong()
      repeat(length - 1) { value = (value shl 8) or (data[at + it + 1].toLong() and 255) }
      return value to length
    }
    val (id, idLength) = vint(offset, true) ?: break
    val (size, sizeLength) = vint(offset + idLength, false) ?: break
    val body = offset + idLength + sizeLength
    val bodyEnd = (body.toLong() + size.coerceAtMost((end - body).toLong())).toInt()
    if (bodyEnd < body) break
    result.add(HeaderElement(id, body, bodyEnd))
    if (bodyEnd <= offset) break
    offset = bodyEnd
  }
  return result
}

private fun parseMkvTechnicalHeader(data: ByteArray): CloudTechnicalMetadata? {
  fun children(node: HeaderElement) = ebmlElements(data, node.start, node.end)
  fun uint(node: HeaderElement?): Long {
    if (node == null || node.end - node.start !in 1..8) return 0
    var value = 0L
    for (index in node.start until node.end) value = (value shl 8) or (data[index].toLong() and 255)
    return value.coerceAtLeast(0)
  }
  fun text(node: HeaderElement?) = node?.let { String(data, it.start, it.end - it.start, Charsets.US_ASCII).trimEnd('\u0000') }.orEmpty()
  val segment = ebmlElements(data, 0, data.size).firstOrNull { it.id == 0x18538067L } ?: return null
  val tracks = children(segment).firstOrNull { it.id == 0x1654AE6BL } ?: return null
  var info: CloudTechnicalMetadata? = null
  var subtitleCodec = ""
  for (entry in children(tracks).filter { it.id == 0xAEL }) {
    val fields = children(entry)
    when (uint(fields.firstOrNull { it.id == 0x83L })) {
      1L -> if (info == null) {
        val video = fields.firstOrNull { it.id == 0xE0L }?.let(::children).orEmpty()
        val defaultDuration = uint(fields.firstOrNull { it.id == 0x23E383L })
        val codec = text(fields.firstOrNull { it.id == 0x86L })
        info = CloudTechnicalMetadata(
          uint(video.firstOrNull { it.id == 0xB0L }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
          uint(video.firstOrNull { it.id == 0xBAL }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
          if (defaultDuration > 0) (1_000_000_000.0 / defaultDuration).toFloat() else 0f,
          when (codec) { "V_MPEG4/ISO/AVC" -> "h264"; "V_MPEGH/ISO/HEVC" -> "hevc"; "V_AV1" -> "av1"; "V_VP9" -> "vp9"; else -> codec },
        )
      }
      17L -> subtitleCodec = text(fields.firstOrNull { it.id == 0x86L })
    }
  }
  return info?.takeIf { it.width > 0 && it.height > 0 }?.copy(hasEmbeddedSubtitles = subtitleCodec.isNotBlank(), subtitleCodec = subtitleCodec)
}

private fun parseMp4TechnicalHeader(data: ByteArray): CloudTechnicalMetadata? {
  fun number(at: Int, length: Int): Long {
    if (at < 0 || at.toLong() + length > data.size || length !in 1..8) return 0
    var value = 0L
    repeat(length) { value = (value shl 8) or (data[at + it].toLong() and 255) }
    return value.coerceAtLeast(0)
  }
  fun boxes(start: Int, end: Int): List<HeaderElement> {
    val result = mutableListOf<HeaderElement>()
    var at = start
    while (at + 8 <= end) {
      val size = number(at, 4)
      val header = if (size == 1L) 16 else 8
      val length = when (size) { 0L -> (end - at).toLong(); 1L -> number(at + 8, 8); else -> size }
      if (length < header || at + header > end) break
      result.add(HeaderElement(number(at + 4, 4), at + header, at + length.coerceAtMost((end - at).toLong()).toInt()))
      if (length > end - at) break
      at += length.toInt()
    }
    return result
  }
  fun code(text: String) = text.fold(0L) { value, c -> (value shl 8) or c.code.toLong() }
  fun child(node: HeaderElement, type: String) = boxes(node.start, node.end).firstOrNull { it.id == code(type) }
  val moov = boxes(0, data.size).firstOrNull { it.id == code("moov") } ?: return null
  for (track in boxes(moov.start, moov.end).filter { it.id == code("trak") }) {
    val mdia = child(track, "mdia") ?: continue
    val handler = child(mdia, "hdlr") ?: continue
    if (number(handler.start + 8, 4) != code("vide")) continue
    val minf = child(mdia, "minf") ?: continue
    val stbl = child(minf, "stbl") ?: continue
    val stsd = child(stbl, "stsd") ?: continue
    val sample = boxes(stsd.start + 8, stsd.end).firstOrNull() ?: continue
    if (sample.end - sample.start < 28) continue
    val codec = String(charArrayOf((sample.id shr 24).toInt().toChar(), (sample.id shr 16 and 255).toInt().toChar(), (sample.id shr 8 and 255).toInt().toChar(), (sample.id and 255).toInt().toChar()))
    val mdhd = child(mdia, "mdhd")
    val timescale = mdhd?.let { number(it.start + if (number(it.start, 1) == 1L) 20 else 12, 4) } ?: 0
    val stts = child(stbl, "stts")
    var samples = 0.0
    var ticks = 0.0
    if (stts != null) {
      val count = number(stts.start + 4, 4).coerceAtMost(((stts.end - stts.start - 8).coerceAtLeast(0) / 8).toLong()).toInt()
      repeat(count) {
        val n = number(stts.start + 8 + it * 8, 4).toDouble()
        samples += n
        ticks += n * number(stts.start + 12 + it * 8, 4)
      }
    }
    return CloudTechnicalMetadata(number(sample.start + 24, 2).toInt(), number(sample.start + 26, 2).toInt(),
      if (ticks > 0 && timescale > 0) (samples * timescale / ticks).toFloat() else 0f,
      when (codec) { "avc1", "avc3" -> "h264"; "hvc1", "hev1" -> "hevc"; "av01" -> "av1"; "vp09" -> "vp9"; else -> codec })
      .takeIf { it.width > 0 && it.height > 0 }
  }
  return null
}
