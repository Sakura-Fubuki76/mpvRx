package app.gyrolet.mpvrx.domain.fonts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FontBankMigrationTest {
  @Test fun migrationPreservesBytesAndIsIdempotent() {
    val root = Files.createTempDirectory("font-bank-test").toFile()
    try {
      val old = File(root, "fonts").apply { mkdirs() }
      File(old, "sample.ttf").writeBytes(byteArrayOf(1, 2, 3))
      assertEquals(1, relocateLegacyFontBank(root))
      assertArrayEquals(byteArrayOf(1, 2, 3), File(root, "font-bank/sample.ttf").readBytes())
      assertFalse(old.exists())
      assertNull(relocateLegacyFontBank(root))
    } finally { root.deleteRecursively() }
  }

  @Test fun collisionPreservesBothFonts() {
    val root = Files.createTempDirectory("font-bank-collision").toFile()
    try {
      val old = File(root, "fonts").apply { mkdirs() }
      val bank = File(root, "font-bank").apply { mkdirs() }
      File(old, "same.ttf").writeText("old")
      File(bank, "same.ttf").writeText("current")
      assertEquals(2, relocateLegacyFontBank(root))
      assertEquals(setOf("old", "current"), bank.listFiles()!!.map { it.readText() }.toSet())
      assertFalse(old.exists())
    } finally { root.deleteRecursively() }
  }
}
