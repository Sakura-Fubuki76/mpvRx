package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A chapter parsed out of the container (MP4 `chpl`/`chapters`, Matroska `Chapters`).
 *
 * Mirrors yume's `ChapterEntry`; kept in this package so the ported extractors stay close to the
 * original source.
 */
@Serializable
data class ChapterEntry(
  val startTimeMs: Long,
  val endTimeMs: Long,
  val title: String,
)

/**
 * Stable cache identity for a media URL: userinfo (`user:pass@`), query (e.g. OpenList `?sign=`)
 * and fragment are stripped so the same file keeps one key across credential and signed-URL
 * rotation. Non-http(s) inputs are returned unchanged.
 */
internal fun stableWebDavUrl(url: String): String {
  val parsed = url.toHttpUrlOrNull() ?: return url
  return parsed
    .newBuilder()
    .username("")
    .password("")
    .query(null)
    .fragment(null)
    .build()
    .toString()
}
