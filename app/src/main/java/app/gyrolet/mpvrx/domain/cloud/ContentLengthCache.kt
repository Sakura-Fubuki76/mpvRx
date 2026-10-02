package app.gyrolet.mpvrx.domain.cloud

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.LinkedHashMap

object ContentLengthCache {
  private const val MAX_ENTRIES = 64

  private val lock = Any()
  private val entries =
    object : LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_ENTRIES
    }

  private fun cacheKey(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    return parsed
      .newBuilder()
      .username("")
      .password("")
      .encodedQuery(null)
      .fragment(null)
      .build()
      .toString()
  }

  fun get(url: String): Long? =
    synchronized(lock) {
      entries[cacheKey(url)]
    }

  fun put(
    url: String,
    contentLength: Long,
  ) {
    synchronized(lock) {
      entries[cacheKey(url)] = contentLength
    }
  }
}
