package app.gyrolet.mpvrx.domain.fonts

import java.util.Locale

/** yume's style/inline font selection, respecting custom ASS Format column order. */
internal object AssFontNames {
  fun parse(text: String): Set<String> {
    val names = linkedSetOf<String>()
    var inStyles = false
    var fontColumn = 1
    for (line in text.lineSequence()) {
      val value = line.trim().removePrefix("﻿")
      if (value.startsWith("[")) inStyles = value.equals("[V4+ Styles]", true) || value.equals("[V4 Styles]", true)
      if (inStyles && value.startsWith("Format:", true)) {
        fontColumn = value.substringAfter(':').split(',').indexOfFirst { it.trim().equals("Fontname", true) }
      }
      if (inStyles && value.startsWith("Style:", true) && fontColumn >= 0) {
        value.substringAfter(':').split(',').getOrNull(fontColumn)?.let { add(names, it) }
      }
      // Only override blocks contain ASS font tags; ordinary dialogue is not a font request.
      Regex("\\{[^}]*}").findAll(value).forEach { block ->
        Regex("\\\\fn([^\\\\}]+)").findAll(block.value).forEach { add(names, it.groupValues[1]) }
      }
    }
    return names
  }

  private fun add(names: MutableSet<String>, value: String) {
    value.trim().removePrefix("@").takeIf { it.isNotBlank() }?.let(names::add)
  }

  fun matches(fileName: String, family: String): Boolean {
    val base = fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
    val name = family.trim().removePrefix("@").lowercase(Locale.ROOT)
    return name.isNotEmpty() && (base == name ||
      (base.startsWith(name) && base.length > name.length && base[name.length] in " -_"))
  }
}
