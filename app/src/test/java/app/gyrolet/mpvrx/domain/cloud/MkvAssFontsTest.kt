package app.gyrolet.mpvrx.domain.cloud

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class MkvAssFontsTest {
  private fun element(id: ByteArray, data: ByteArray): ByteArray {
    require(data.size < 127)
    return id + byteArrayOf((0x80 or data.size).toByte()) + data
  }
  @Test fun readsEmbeddedAssStylesAndRejectsTruncatedTracks() {
    val codec = element(byteArrayOf(0x86.toByte()), "S_TEXT/ASS".toByteArray())
    val type = element(byteArrayOf(0x83.toByte()), byteArrayOf(17))
    val header = element(byteArrayOf(0x63, 0xa2.toByte()), "[V4+ Styles]\nStyle: Default,字幕字体,40".toByteArray())
    val track = element(byteArrayOf(0xae.toByte()), codec + type + header)
    val extractor = MkvKeyframeExtractor(OkHttpClient())
    assertEquals(setOf("字幕字体"), extractor.parseAssFontsFromTracks(track, 0))
    assertEquals(emptySet<String>(), extractor.parseAssFontsFromTracks(track.copyOf(track.size - 1), 0))
  }
}
