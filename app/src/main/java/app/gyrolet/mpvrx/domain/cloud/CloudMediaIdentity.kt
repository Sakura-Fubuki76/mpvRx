package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkPath
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun cloudMediaKey(connection: NetworkConnection?, path: String, size: Long, modified: Long): String {
  val endpoint = connection?.let {
    "${it.id}|${it.protocol}|${it.host.lowercase()}|${it.port}|${it.path}|${it.useHttps}"
  } ?: "direct"
  val canonical = if (connection != null) NetworkPath.from(path).value else stableWebDavUrl(path)
  return "$endpoint|$canonical|$size|$modified"
}

fun cloudMediaExtension(path: String): String =
  (path.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: path).substringAfterLast('.', "").lowercase()
