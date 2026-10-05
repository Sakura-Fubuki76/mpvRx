package app.gyrolet.mpvrx.domain.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudTechnicalMetadataTest {
  private fun element(id: String, payload: ByteArray): ByteArray {
    val bytes = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val size = if (payload.size < 127) byteArrayOf((0x80 or payload.size).toByte())
      else byteArrayOf((0x40 or (payload.size shr 8)).toByte(), payload.size.toByte())
    return bytes + size + payload
  }
  private fun uint(id: String, value: Long, bytes: Int) = element(id, ByteArray(bytes) { (value shr (8 * (bytes - it - 1))).toByte() })
  private fun mkv(): ByteArray {
    val video = element("AE", uint("83", 1, 1) + element("86", "V_MPEGH/ISO/HEVC".toByteArray()) +
      uint("23E383", 41_708_333, 4) + element("E0", uint("B0", 1920, 2) + uint("BA", 1080, 2)))
    val subtitle = element("AE", uint("83", 17, 1) + element("86", "S_TEXT/ASS".toByteArray()))
    return element("1A45DFA3", byteArrayOf()) + element("18538067", element("1654AE6B", video + subtitle))
  }

  @Test fun matroskaHeaderProvidesRealDimensionsFpsCodecAndSubtitles() {
    val info = parseCloudTechnicalMetadata(mkv())!!
    assertEquals(1920, info.width)
    assertEquals(1080, info.height)
    assertEquals(23.976f, info.fps, .001f)
    assertEquals("hevc", info.videoCodec)
    assertTrue(info.hasEmbeddedSubtitles)
    assertEquals("S_TEXT/ASS", info.subtitleCodec)
  }

  @Test fun malformedOrTruncatedHeadersNeverThrow() {
    for (length in 0..mkv().size) parseCloudTechnicalMetadata(mkv().copyOf(length))
    assertNull(parseCloudTechnicalMetadata(ByteArray(32)))
    assertNull(parseCloudTechnicalMetadata(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0)))
  }

  @Test fun mp4SampleTableProvidesDimensionsAndAverageFramerate() {
    fun bytes(value: Long, length: Int) = ByteArray(length) { (value shr (8 * (length - it - 1))).toByte() }
    fun box(name: String, payload: ByteArray) = bytes((payload.size + 8).toLong(), 4) + name.toByteArray() + payload
    val sample = ByteArray(78).apply { bytes(1920, 2).copyInto(this, 24); bytes(1080, 2).copyInto(this, 26) }
    val stsd = box("stsd", ByteArray(4) + bytes(1, 4) + box("avc1", sample))
    val stts = box("stts", ByteArray(4) + bytes(1, 4) + bytes(240, 4) + bytes(1001, 4))
    val mdhd = box("mdhd", ByteArray(12) + bytes(24000, 4) + bytes(240240, 4))
    val hdlr = box("hdlr", ByteArray(8) + "vide".toByteArray())
    val data = box("ftyp", "isom".toByteArray()) + box("moov", box("trak", box("mdia", mdhd + hdlr + box("minf", box("stbl", stsd + stts)))))
    val info = parseCloudTechnicalMetadata(data)!!
    assertEquals(1920, info.width)
    assertEquals(1080, info.height)
    assertEquals(23.976f, info.fps, .001f)
    assertEquals("h264", info.videoCodec)
  }
}
