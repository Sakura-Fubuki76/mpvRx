package app.gyrolet.mpvrx.domain.fonts

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object FontDocumentStorage {
  private fun localFile(context: Context, uri: Uri): File? {
    val candidate = runCatching {
      when {
        uri.scheme == "file" -> uri.path?.let(::File)
        uri.authority == "com.android.externalstorage.documents" -> externalFontFile(
          DocumentsContract.getDocumentId(uri), Environment.getExternalStorageDirectory(), File("/storage"))
        else -> context.contentResolver.openFileDescriptor(uri, "r")?.use {
          // A provider may expose a local file. Do not link to a transient /proc/self/fd descriptor.
          val path = Os.readlink("/proc/self/fd/${it.fd}")
          path.takeIf { value -> value.startsWith('/') && !value.startsWith("/proc/") && !value.endsWith(" (deleted)") }?.let(::File)
        }
      }
    }.getOrNull()
    return candidate?.takeIf { it.isFile && it.canRead() }
  }

  fun linkExisting(context: Context, uri: Uri, target: File): Boolean =
    localFile(context, uri)?.let { linkReadableFont(it, target) } == true

  fun store(context: Context, uri: Uri, target: File, size: Long = -1, modified: Long = 0): Boolean {
    if (linkExisting(context, uri, target)) return true
    if (target.isFile && size >= 0 && target.length() == size && modified > 0 && target.lastModified() == modified) return true
    target.parentFile.mkdirs()
    val pending = Files.createTempFile(target.parentFile.toPath(), ".font-copy-", ".tmp")
    try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        pending.toFile().outputStream().use { input.copyTo(it) }
      } ?: return false
      if (size > 0 && pending.toFile().length() != size) return false
      if (modified > 0) pending.toFile().setLastModified(modified)
      Files.move(pending, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      return true
    } finally {
      Files.deleteIfExists(pending)
    }
  }

  /** Old active hard links can otherwise keep the replaced bank copies occupying disk space. */
  fun relinkActive(context: Context) {
    val bank = File(context.filesDir, "font-bank")
    if (!bank.isDirectory) return
    // Preserve every alias: the name index intentionally deduplicates identical source paths.
    val links = Files.walk(bank.toPath()).use { paths ->
      paths.filter { Files.isSymbolicLink(it) && it.toFile().isFile }
        .toArray().map { (it as java.nio.file.Path).toFile() }
        .associateBy { fontStorageName(it.relativeTo(bank).invariantSeparatorsPath) }
    }
    File(context.filesDir, "fonts-active").listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
      directory.listFiles().orEmpty().forEach { active ->
        links[active.name]?.let { source -> linkReadableFont(source, active) }
      }
    }
  }
}
