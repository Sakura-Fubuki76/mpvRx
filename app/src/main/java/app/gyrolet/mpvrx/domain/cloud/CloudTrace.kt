package app.gyrolet.mpvrx.domain.cloud

import android.util.Log
import app.gyrolet.mpvrx.BuildConfig

/** Debug diagnostics never contain credentials, signed URLs, or raw filenames. */
object CloudTrace {
  private var file: java.io.File? = null
  private val lock = Any()
  fun initialize(context: android.content.Context) {
    if (!BuildConfig.DEBUG) return
    file = java.io.File(context.filesDir, "cloud-debug.log")
  }
  fun event(stage: String, connectionId: Long = 0, path: String? = null, detail: String = "") {
    if (!BuildConfig.DEBUG) return
    val identity = path?.let { " pathKey=${it.hashCode().toUInt().toString(16)}" }.orEmpty()
    val message = "stage=$stage connection=$connectionId$identity $detail"
    Log.i("CloudTrace", message)
    synchronized(lock) {
      runCatching {
        file?.let { target ->
          if (target.length() > 2 * 1024 * 1024) {
            val previous = java.io.File(target.parentFile, "cloud-debug.previous.log")
            java.nio.file.Files.move(target.toPath(), previous.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          }
          target.appendText("${System.currentTimeMillis()} $message\n")
        }
      }
    }
  }
}
