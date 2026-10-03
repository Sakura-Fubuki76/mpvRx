package app.gyrolet.mpvrx.domain.fonts

import java.io.File

internal fun relocateLegacyFontBank(root: File): Int? {
  val old = File(root, "fonts")
  val bank = File(root, "font-bank")
  if (!old.exists()) return null
  if (!bank.exists()) {
    check(old.renameTo(bank)) { "Unable to relocate legacy font bank" }
  } else {
    old.listFiles().orEmpty().forEach { file ->
      val target = File(bank, file.name).takeUnless { it.exists() }
        ?: File(bank, "legacy-${System.nanoTime()}-${file.name}")
      check(file.renameTo(target)) { "Unable to preserve legacy font file" }
    }
    check(old.delete()) { "Unable to remove empty legacy font directory" }
  }
  return bank.listFiles()?.size ?: 0
}
