package app.gyrolet.mpvrx.domain.fonts

import java.io.File
import java.io.RandomAccessFile

/** Read only bounded SFNT name tables, never the multi-megabyte glyph data. */
internal object FontNameReader {
  fun names(file: File): Set<String> = readNames(file, setOf(1, 4, 6, 16))

  fun familyNames(file: File): Set<String> = readNames(file, setOf(1, 16))

  private fun readNames(file: File, nameIds: Set<Int>): Set<String> = runCatching {
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
        repeat(records) {
          input.seek(nameOffset + 6 + it * 12L)
          val platform = input.readUnsignedShort()
          input.readUnsignedShort()
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
            val name = String(bytes, if (platform == 0 || platform == 3) Charsets.UTF_16BE else Charsets.ISO_8859_1)
              .trim().removePrefix("@")
            if (name.isNotBlank()) names.add(name)
          }
        }
      }
      names
    }
  }.getOrDefault(emptySet())
}
