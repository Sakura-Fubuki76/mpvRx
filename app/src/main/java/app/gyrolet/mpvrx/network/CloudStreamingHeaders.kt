package app.gyrolet.mpvrx.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

object CloudStreamingHeaders {
  fun userAgent(url: String): String? {
    val host = url.toHttpUrlOrNull()?.host ?: return null
    return if (listOf("pan.baidu.com", "baidupcs.com", "baidupcs.net").any { host == it || host.endsWith(".$it") }) "pan.baidu.com" else null
  }
  fun apply(request: Request): Request {
    val builder = request.newBuilder()
    if (request.header("Range") != null) builder.header("Accept-Encoding", "identity")
    userAgent(request.url.toString())?.let { builder.header("User-Agent", it) }
    return builder.build()
  }
}
