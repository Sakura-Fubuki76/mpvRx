package app.gyrolet.mpvrx.domain.cloud

/** Route original diagnostic calls without exposing original URL/header-bearing messages. */
internal object SpriteMigrationLog {
  fun d(tag: String, message: String) {
    if (message.startsWith("Round ") || message.startsWith("Batch ") || message.startsWith("Decoded ") || message.startsWith("MKV decoded "))
      CloudTrace.event("sprite.yume", detail = message.take(240))
  }
  fun w(tag: String, message: String) { CloudTrace.event("sprite.yume.warning", detail = "stage=$tag") }
  fun e(tag: String, message: String, error: Throwable) { CloudTrace.event("sprite.yume.failed", detail = "stage=$tag error=${error.javaClass.simpleName}") }
}
