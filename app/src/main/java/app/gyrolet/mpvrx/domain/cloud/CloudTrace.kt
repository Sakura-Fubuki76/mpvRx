package app.gyrolet.mpvrx.domain.cloud

import android.util.Log
import app.gyrolet.mpvrx.BuildConfig

/** Debug diagnostics never contain credentials, signed URLs, or raw filenames. */
object CloudTrace {
  fun event(stage: String, connectionId: Long = 0, path: String? = null, detail: String = "") {
    if (!BuildConfig.DEBUG) return
    val identity = path?.let { " pathKey=${it.hashCode().toUInt().toString(16)}" }.orEmpty()
    Log.d("CloudTrace", "stage=$stage connection=$connectionId$identity $detail")
  }
}
