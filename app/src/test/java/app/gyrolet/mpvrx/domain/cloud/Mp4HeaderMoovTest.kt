package app.gyrolet.mpvrx.domain.cloud

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Mp4HeaderMoovTest {
  private fun box(type: String, size: Int, payload: ByteArray = ByteArray(0)): ByteArray =
    ByteBuffer.allocate(8 + payload.size).putInt(size).put(type.toByteArray()).put(payload).array()

  @Test fun fragmentedInitializationAtFront() {
    val prefix = box("ftyp", 24, ByteArray(16)) + box("moov", 1126, ByteArray(1118)) + box("moof", 5000)
    assertEquals(24 to 1126, findHeaderMoov(prefix, 258854598))
  }

  @Test fun truncatedMoovCanBeFetchedWithoutScanningTail() {
    val prefix = box("ftyp", 24, ByteArray(16)) + box("moov", 100000, ByteArray(64))
    assertEquals(24 to 100000, findHeaderMoov(prefix, 500000))
  }

  @Test fun payloadSignatureIsNotMoov() {
    val prefix = box("mdat", 1000000, box("moov", 24, ByteArray(16)))
    assertNull(findHeaderMoov(prefix, 2000000))
  }

  @Test fun extendedSizeAndInvalidBounds() {
    val prefix = ByteBuffer.allocate(16).putInt(1).put("moov".toByteArray()).putLong(1024).array()
    assertEquals(0 to 1024, findHeaderMoov(prefix, 2000))
    assertNull(findHeaderMoov(prefix, 1000))
    assertNull(findHeaderMoov(box("moov", 32), 16))
    assertNull(findHeaderMoov(box("moov", 32 * 1024 * 1024), 64 * 1024 * 1024))
  }
}
