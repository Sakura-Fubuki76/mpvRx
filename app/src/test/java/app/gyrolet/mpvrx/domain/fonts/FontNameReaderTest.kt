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
  @Test fun readsEveryCollectionFaceAndExcludesPostscriptNamesFromFamilies() {
    val font = File.createTempFile("font-collection", ".ttc")
    try {
      val data = ByteBuffer.allocate(256)
      data.putInt(0x74746366).putInt(0x00010000).putInt(2).putInt(20).putInt(48)
      for ((offset, nameOffset) in listOf(20 to 76, 48 to 156)) {
        data.position(offset)
        data.putInt(0x00010000).putShort(1).putShort(0).putShort(0).putShort(0)
        data.putInt(0x6e616d65).putInt(0).putInt(nameOffset).putInt(80)
        data.position(nameOffset)
        val family = (if (offset == 20) "Family A" else "Family B").toByteArray(Charsets.UTF_16BE)
        val postscript = "Postscript".toByteArray(Charsets.UTF_16BE)
        data.putShort(0).putShort(2).putShort(30)
        data.putShort(3).putShort(1).putShort(0).putShort(1).putShort(family.size.toShort()).putShort(0)
        data.putShort(3).putShort(1).putShort(0).putShort(6).putShort(postscript.size.toShort()).putShort(family.size.toShort())
        data.put(family).put(postscript)
      }
      font.writeBytes(data.array())
      assertEquals(setOf("Family A", "Family B"), FontNameReader.familyNames(font))
      assertTrue(FontNameReader.names(font).contains("Postscript"))
    } finally { font.delete() }
  }
  private fun withNames(records: List<Triple<Int, Int, ByteArray>>, check: (File) -> Unit) {
    val font = File.createTempFile("font-encoding", ".ttf")
    try {
      val storage = 6 + 12 * records.size
      val length = storage + records.sumOf { it.third.size }
      val data = ByteBuffer.allocate(28 + length)
      data.putInt(0x00010000).putShort(1).putShort(0).putShort(0).putShort(0)
      data.putInt(0x6e616d65).putInt(0).putInt(28).putInt(length)
      data.putShort(0).putShort(records.size.toShort()).putShort(storage.toShort())
      var offset = 0
      for ((platform, encoding, bytes) in records) {
        data.putShort(platform.toShort()).putShort(encoding.toShort()).putShort(0).putShort(1)
          .putShort(bytes.size.toShort()).putShort(offset.toShort())
        offset += bytes.size
      }
      for (record in records) data.put(record.third)
      font.writeBytes(data.array())
      check(font)
    } finally { font.delete() }
  }

  @Test fun decodesLegacyChineseWindowsAndMacNames() {
    for ((platform, encoding, charset) in listOf(Triple(3, 3, "GBK"), Triple(3, 4, "Big5"), Triple(1, 25, "GB2312"))) {
      val expected = if (encoding == 4) "繁體字幕" else "中文字体"
      withNames(listOf(Triple(platform, encoding, expected.toByteArray(java.nio.charset.Charset.forName(charset))))) {
        assertEquals(setOf(expected), FontNameReader.familyNames(it))
      }
    }
  }

  @Test fun prefersUnicodeOverMislabeledLegacyAliases() {
    withNames(listOf(Triple(1, 0, "中文字体".toByteArray(java.nio.charset.Charset.forName("GBK"))),
      Triple(3, 1, "中文字体".toByteArray(Charsets.UTF_16BE)))) {
      assertEquals(setOf("中文字体"), FontNameReader.familyNames(it))
    }
  }

  @Test fun rejectsMalformedUnknownAndPrivateUseNames() {
    withNames(listOf(Triple(3, 1, byteArrayOf(0x4e)), Triple(1, 32, "Unknown".toByteArray()),
      Triple(3, 1, "\ue000\ufffd".toByteArray(Charsets.UTF_16BE)))) {
      assertTrue(FontNameReader.familyNames(it).isEmpty())
    }
  }

  @Test fun readsMacRomanAccentsWithoutLatin1Mojibake() {
    withNames(listOf(Triple(1, 0, "Café".toByteArray(java.nio.charset.Charset.forName("x-MacRoman"))))) {
      assertEquals(setOf("Café"), FontNameReader.familyNames(it))
    }
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
