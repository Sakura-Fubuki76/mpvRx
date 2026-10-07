package app.gyrolet.mpvrx.domain.fonts

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replace only our private alias, after opening the new link successfully. Never modify the source. */
internal fun linkReadableFont(source: File, target: File): Boolean = runCatching {
  if (!source.isFile || !source.canRead()) return false
  val sourcePath = source.toPath().toAbsolutePath().normalize()
  val targetPath = target.toPath().toAbsolutePath().normalize()
  if (sourcePath == targetPath) return true
  if (Files.isSymbolicLink(targetPath) && Files.readSymbolicLink(targetPath) == sourcePath) {
    target.inputStream().use { it.read() }
    return true
  }
  target.parentFile.mkdirs()
  val pending = Files.createTempFile(target.parentFile.toPath(), ".font-link-", ".tmp")
  try {
    Files.delete(pending)
    Files.createSymbolicLink(pending, sourcePath)
    pending.toFile().inputStream().use { it.read() }
    Files.move(pending, targetPath, StandardCopyOption.REPLACE_EXISTING)
    true
  } finally {
    Files.deleteIfExists(pending)
  }
}.getOrDefault(false)

/** Document IDs are decoded by DocumentsContract; never URL-decode their filenames again. */
internal fun externalFontFile(documentId: String, primaryRoot: File, volumesRoot: File): File? {
  if (':' !in documentId) return null
  val volume = documentId.substringBefore(':')
  val relative = documentId.substringAfter(':')
  if (relative.startsWith('/') || '\\' in relative || relative.split('/').any { it == ".." }) return null
  val root = when {
    volume.equals("primary", true) -> primaryRoot
    volume.matches(Regex("[a-fA-F0-9]{4}-[a-fA-F0-9]{4}")) -> File(volumesRoot, volume)
    else -> return null
  }
  return File(root, relative)
}
