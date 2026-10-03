package app.gyrolet.mpvrx.domain.cloud

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class YuvBitmapConverterTest {
  @Test fun independentChromaStridesAndLimitedRangeAreRespected() {
    val frame = YuvBitmapConverter.imagePixels(
      ByteBuffer.wrap(ByteArray(8) { 16 }), 2, 1,
      ByteBuffer.wrap(byteArrayOf(128.toByte(), 128.toByte())), 1, 1,
      ByteBuffer.wrap(byteArrayOf(128.toByte(), 128.toByte(), 0, 128.toByte())), 2, 1,
      0, 0, 2, 4, 2, 2, false)!!
    assertEquals(0xff000000.toInt(), frame.pixels[0])
    assertNotEquals(frame.pixels[0], frame.pixels[4])
    assertTrue((frame.pixels[4] shr 8 and 255) > 0)
  }
  @Test fun largeFramesAreBoundedWithoutLosingPortraitAspect() {
    assertEquals(1024 to 576, YuvBitmapConverter.thumbnailDimensions(3840, 2160))
    assertEquals(576 to 1024, YuvBitmapConverter.thumbnailDimensions(2160, 3840))
    assertNull(YuvBitmapConverter.thumbnailDimensions(Int.MAX_VALUE, 1))
  }
}
