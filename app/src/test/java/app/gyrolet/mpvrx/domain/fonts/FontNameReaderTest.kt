package app.gyrolet.mpvrx.domain.fonts

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FontNameReaderTest {
  @Test fun readsFamilyFromNameTableWithoutGlyphData() {
    val font = File.createTempFile("font-name", ".ttf")
    try {
      val name = "动画字幕字体".toByteArray(Charsets.UTF_16BE)
      val data = ByteBuffer.allocate(46 + name.size)
      data.putInt(0x00010000).putShort(1).putShort(0).putShort(0).putShort(0)
      data.putInt(0x6e616d65).putInt(0).putInt(28).putInt(18 + name.size)
      data.putShort(0).putShort(1).putShort(18)
      data.putShort(3).putShort(1).putShort(0x0804).putShort(1).putShort(name.size.toShort()).putShort(0).put(name)
      font.writeBytes(data.array())
      assertEquals(setOf("动画字幕字体"), FontNameReader.names(font))
    } finally { font.delete() }
  }
  @Test fun rejectsTruncatedAndOutOfBoundsFontTables() {
    val font = File.createTempFile("font-broken", ".ttc")
    try {
      font.writeBytes(byteArrayOf(1,2,3))
      assertTrue(FontNameReader.names(font).isEmpty())
      font.writeBytes(ByteBuffer.allocate(16).putInt(0x74746366).putInt(0).putInt(Int.MAX_VALUE).putInt(0).array())
      assertTrue(FontNameReader.names(font).isEmpty())
    } finally { font.delete() }
  }
}
