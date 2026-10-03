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
      var offset = 0
      while (offset < value.length) {
        val start = value.indexOf('{', offset)
        if (start < 0) break
        val end = value.indexOf('}', start + 1)
        if (end < 0) break
        val block = value.substring(start + 1, end)
        var tag = block.indexOf("\\fn")
        while (tag >= 0) {
          val next = block.indexOf('\\', tag + 3).let { if (it < 0) block.length else it }
          add(names, block.substring(tag + 3, next))
          tag = block.indexOf("\\fn", next)
        }
        offset = end + 1
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
