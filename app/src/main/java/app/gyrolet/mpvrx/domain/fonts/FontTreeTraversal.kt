package app.gyrolet.mpvrx.domain.fonts

import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

internal val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
internal fun isFontFile(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in fontExtensions

/** Iterative traversal has no depth/count cutoff; provider cycles are visited only once. */
internal fun <T> walkFontTree(root: T, identity: (T) -> String, directory: (T) -> Boolean,
  children: (T) -> List<T>, file: (T) -> Unit) {
  val pending = ArrayDeque<T>().apply { add(root) }
  val seen = HashSet<String>()
  while (pending.isNotEmpty()) {
    val node = pending.removeLast()
    if (!seen.add(identity(node))) continue
    if (directory(node)) children(node).asReversed().forEach(pending::add) else file(node)
  }
}

/** Keep family-looking filenames while preventing equal basenames in different folders colliding. */
internal fun fontStorageName(relativePath: String): String {
  val name = relativePath.substringAfterLast('/')
  if ('/' !in relativePath) return name
  val digest = MessageDigest.getInstance("SHA-256").digest(relativePath.toByteArray())
    .take(8).joinToString("") { "%02x".format(it) }
  return "${name.substringBeforeLast('.')}--$digest.${name.substringAfterLast('.')}"
}

internal fun fontBankFiles(root: File): List<File> {
  val files = mutableListOf<File>()
  if (root.exists()) walkFontTree(root, { it.canonicalPath }, { it.isDirectory },
    { it.listFiles().orEmpty().toList() }, { if (it.isFile && isFontFile(it.name)) files.add(it) })
  return files
}
