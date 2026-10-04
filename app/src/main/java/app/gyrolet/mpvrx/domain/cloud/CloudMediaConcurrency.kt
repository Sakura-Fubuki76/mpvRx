package app.gyrolet.mpvrx.domain.cloud

/** Match Yume's heap-based budget for concurrent cloud media extraction. */
internal fun cloudMediaConcurrency(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int {
  val maxHeapMb = maxHeapBytes / (1024 * 1024)
  return when {
    maxHeapMb < 384 -> 1
    maxHeapMb < 512 -> 2
    maxHeapMb < 1536 -> 3
    else -> 4
  }
}
