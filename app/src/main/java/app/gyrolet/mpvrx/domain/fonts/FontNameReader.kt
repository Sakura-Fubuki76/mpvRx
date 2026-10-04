package app.gyrolet.mpvrx.domain.fonts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Read only bounded SFNT name tables, never the multi-megabyte glyph data. */
internal object FontNameReader {
  fun names(file: File): Set<String> = readNames(file, setOf(1, 4, 6, 16))

  fun familyNames(file: File): Set<String> = readNames(file, setOf(1, 16), preferUnicode = true)

  private fun readNames(file: File, nameIds: Set<Int>, preferUnicode: Boolean = false): Set<String> = runCatching {
    RandomAccessFile(file, "r").use { input ->
      val names = linkedSetOf<String>()
      if (input.length() < 12) return@use names
      val signature = input.readInt()
      val offsets = if (signature == 0x74746366) {
        input.readInt()
        val count = input.readInt()
        if (count !in 1..64 || 12L + count * 4L > input.length()) return@use names
        List(count) { input.readInt().toLong() and 0xffffffffL }
      } else listOf(0L)
      for (offset in offsets) {
        if (offset < 0 || offset + 12 > input.length()) continue
        input.seek(offset + 4)
        val count = input.readUnsignedShort()
        if (count !in 1..256 || offset + 12 + count * 16L > input.length()) continue
        input.seek(offset + 12)
        var nameOffset = -1L
        var nameLength = 0L
        repeat(count) {
          val tag = input.readInt()
          input.readInt()
          val tableOffset = input.readInt().toLong() and 0xffffffffL
          val tableLength = input.readInt().toLong() and 0xffffffffL
          if (tag == 0x6e616d65) { nameOffset = tableOffset; nameLength = tableLength }
        }
        if (nameOffset < 0 || nameLength !in 6..1048576 || nameOffset + nameLength > input.length()) continue
        input.seek(nameOffset)
        input.readUnsignedShort()
        val records = input.readUnsignedShort()
        val storage = input.readUnsignedShort()
        if (records > 4096 || 6L + records * 12L > nameLength) continue
        val unicodeNames = linkedSetOf<String>()
        val legacyNames = linkedSetOf<String>()
        repeat(records) {
          input.seek(nameOffset + 6 + it * 12L)
          val platform = input.readUnsignedShort()
          val encoding = input.readUnsignedShort()
          input.readUnsignedShort()
          val nameId = input.readUnsignedShort()
          val length = input.readUnsignedShort()
          val stringOffset = input.readUnsignedShort()
          val position = nameOffset + storage + stringOffset
          if (nameId in nameIds && length in 1..4096 &&
              position >= nameOffset && position + length <= nameOffset + nameLength) {
            val bytes = ByteArray(length)
            input.seek(position)
            input.readFully(bytes)
            val name = decodeName(bytes, platform, encoding)
            if (name != null) {
              val unicode = platform == 0 || (platform == 3 && encoding !in 3..5)
              (if (unicode) unicodeNames else legacyNames).add(name)
            }
          }
        }
        // Legacy aliases are often mislabeled by old fonts. Prefer authoritative Unicode names
        // separately for each TTC face, so a collection member is never lost to another member.
        names.addAll(unicodeNames)
        if (!preferUnicode || unicodeNames.isEmpty()) names.addAll(legacyNames)
      }
      names
    }
  }.getOrDefault(emptySet())

  private fun decodeName(bytes: ByteArray, platform: Int, encoding: Int): String? {
    val charsetName = when (platform) {
      0 -> "UTF-16BE"
      3 -> when (encoding) {
        3 -> "GBK"
        4 -> "Big5"
        5 -> "MS949"
        0, 1, 2, 6, 10 -> "UTF-16BE"
        else -> return null
      }
      1 -> when (encoding) {
        0 -> "x-MacRoman"
        1 -> "Shift_JIS"
        2 -> "Big5"
        3 -> "EUC-KR"
        25 -> "GB2312"
        else -> return null
      }
      else -> return null
    }
    return runCatching {
      val name = Charset.forName(charsetName).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().trim().removePrefix("@")
      name.takeIf { it.isNotBlank() && it.codePoints().noneMatch { code ->
        Character.isISOControl(code) || code == 0xfffd || code and 0xffff >= 0xfffe ||
          Character.getType(code) == Character.PRIVATE_USE.toInt() || Character.getType(code) == Character.UNASSIGNED.toInt()
      } }
    }.getOrNull()
  }

}
