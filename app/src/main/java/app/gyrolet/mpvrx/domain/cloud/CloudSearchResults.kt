package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath

/** An incomplete server index must not discard already known matches. Fresh API versions win. */
internal fun mergeCloudSearchResults(indexed: List<NetworkFile>, cached: List<NetworkFile>, query: String): List<NetworkFile> =
  (indexed + cached.filter { it.name.contains(query.trim(), ignoreCase = true) })
    .distinctBy { NetworkPath.from(it.path).value }
