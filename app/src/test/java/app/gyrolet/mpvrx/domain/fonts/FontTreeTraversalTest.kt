package app.gyrolet.mpvrx.domain.fonts

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FontTreeTraversalTest {
  @Test fun scansPastOldDepthAndDirectoryLimitsWithoutRecursion() {
    val found = mutableListOf<Int>()
    walkFontTree(0, { it.toString() }, { it < 6000 }, { listOf(it + 1) }, { found.add(it) })
    assertEquals(listOf(6000), found)
  }

  @Test fun cyclesDoNotPreventVisitingOtherBranches() {
    val children = mapOf("root" to listOf("a", "b"), "a" to listOf("root", "font1"), "b" to listOf("font2"))
    val found = mutableListOf<String>()
    walkFontTree("root", { it }, { it in children }, { children.getValue(it) }, { found.add(it) })
    assertEquals(listOf("font1", "font2"), found)
  }

  @Test fun nestedIdenticalBasenamesKeepDifferentStableFontNames() {
    val first = fontStorageName("collection/a/Arial.ttf")
    val second = fontStorageName("collection/b/Arial.ttf")
    assertNotEquals(first, second)
    assertEquals(first, fontStorageName("collection/a/Arial.ttf"))
    assertTrue(AssFontNames.matches(first, "Arial"))
    assertEquals("Arial.ttf", fontStorageName("Arial.ttf"))
  }

  @Test fun internalFontBankIncludesNestedFontsAndSupportedExtensionsOnly() {
    val root = java.nio.file.Files.createTempDirectory("font-bank-test").toFile()
    try {
      File(root, "a/b").mkdirs()
      File(root, "root.ttf").writeText("font")
      File(root, "a/b/nested.OTF").writeText("font")
      File(root, "a/b/ignored.txt").writeText("text")
      assertEquals(setOf("root.ttf", "nested.OTF"), fontBankFiles(root).map { it.name }.toSet())
    } finally { root.deleteRecursively() }
  }
}
