package app.gyrolet.mpvrx.domain.fonts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontFileLinksTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun resolvesLocalDocumentIdsWithoutDecodingFileNamesTwice() {
    val primary = File("primary")
    val volumes = File("volumes")
    assertEquals(File(primary, "Fonts/中文%20.ttf"), externalFontFile("primary:Fonts/中文%20.ttf", primary, volumes))
    assertEquals(File(volumes, "1234-ABCD/Fonts/a.ttf"), externalFontFile("1234-ABCD:Fonts/a.ttf", primary, volumes))
    listOf("raw:Fonts/a.ttf", "primary:../a.ttf", "primary:/a.ttf", "primary:Fonts\\a.ttf", "virtual").forEach {
      assertNull(externalFontFile(it, primary, volumes))
    }
  }

  @Test fun missingSourcePreservesExistingCopy() {
    val target = temporary.newFile("bank.ttf").apply { writeText("old font") }
    assertFalse(linkReadableFont(File(temporary.root, "missing.ttf"), target))
    assertEquals("old font", target.readText())
  }

  @Test fun linksReplaceCopiesAndKeepOriginalIndependentOfAliases() {
    val source = temporary.newFile("original.ttf").apply { writeText("font") }
    val probe = File(temporary.root, "probe")
    try { Files.createSymbolicLink(probe.toPath(), source.toPath()) }
    catch (error: Exception) { assumeNoException("Host filesystem cannot create symlinks", error) }
    Files.deleteIfExists(probe.toPath())
    val bank = temporary.newFile("bank.ttf").apply { writeText("old copy") }
    assertTrue(linkReadableFont(source, bank))
    assertTrue(Files.isSymbolicLink(bank.toPath()))
    val active = temporary.newFile("active.ttf").apply { writeText("old active") }
    assertTrue(linkReadableFont(bank, active))
    source.writeText("updated font")
    assertEquals("updated font", active.readText())
    assertTrue(linkReadableFont(source, bank))
    Files.delete(active.toPath())
    Files.delete(bank.toPath())
    assertEquals("updated font", source.readText())
  }
}
