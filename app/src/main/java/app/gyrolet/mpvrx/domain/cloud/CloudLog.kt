package app.gyrolet.mpvrx.domain.cloud

import android.util.Log
import app.gyrolet.mpvrx.BuildConfig

/**
 * Logging shim for the ported cloud pipeline.
 *
 * The ported extractors call `Logger.d(tag, msg)` / `Logger.w(tag, msg)`; this object keeps that
 * shape so the containers stay byte-for-byte comparable with the source project, while routing to
 * the platform log and honouring the debug build flag.
 */
internal object CloudLog {
  private val verbose: Boolean = BuildConfig.DEBUG

  fun d(
    tag: String,
    message: String,
  ) {
    if (verbose) Log.d(tag, message)
  }

  fun d(
    tag: String,
    message: String,
    tr: Throwable,
  ) {
    if (verbose) Log.d(tag, message, tr)
  }

  fun i(
    tag: String,
    message: String,
  ) {
    if (verbose) Log.i(tag, message)
  }

  fun w(
    tag: String,
    message: String,
  ) {
    Log.w(tag, message)
  }

  fun w(
    tag: String,
    message: String,
    tr: Throwable,
  ) {
    Log.w(tag, message, tr)
  }

  fun e(
    tag: String,
    message: String,
  ) {
    Log.e(tag, message)
  }

  fun e(
    tag: String,
    message: String,
    tr: Throwable,
  ) {
    Log.e(tag, message, tr)
  }
}
