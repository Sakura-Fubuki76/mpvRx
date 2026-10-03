package app.gyrolet.mpvrx.domain.fonts

import org.junit.Assert.*
import org.junit.Test

class AssFontNamesTest {
  @Test fun respectsStylesFormatVerticalFontsAndInlineOverrides() {
    val text = """
      [V4+ Styles]
      Format: Name, Fontsize, Fontname, Bold
      Style: Default,40,@思源黑体,-1
      [Events]
      Dialogue: 0,0,0,Default,,0,0,0,,{\fnArial\b1}hello {\fn另一字体}world
    """.trimIndent()
    assertEquals(setOf("思源黑体", "Arial", "另一字体"), AssFontNames.parse(text))
  }
  @Test fun emptyResetAndOrdinaryDialogueDoNotBecomeFontNames() {
    assertEquals(emptySet<String>(), AssFontNames.parse("Dialogue: text \\fnFakeFont {\\fn}"))
  }
  @Test fun matchesVariantsButAvoidsPrefixCollisions() {
    assertTrue(AssFontNames.matches("Arial-Bold.ttf", "arial"))
    assertTrue(AssFontNames.matches("Arial.ttf", "@Arial"))
    assertFalse(AssFontNames.matches("ArialNarrow.ttf", "Arial"))
  }
  @Test fun handlesConsecutiveOverridesResetAndUnclosedBlocksWithoutRegex() {
    assertEquals(setOf("Arial", "Verdana"), AssFontNames.parse("{\\fnArial\\b1\\fnVerdana} {\\fn} {\\fnUnclosed"))
  }
}
