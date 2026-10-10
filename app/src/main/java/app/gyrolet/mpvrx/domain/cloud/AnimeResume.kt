package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath

/** History is newest first and already scoped to the storage. Match actual files, not episode numbers. */
internal fun animeResumeFile(
  files: List<NetworkFile>,
  recentPaths: List<String>,
  isPartial: (NetworkFile) -> Boolean,
  isWatched: (NetworkFile) -> Boolean,
): NetworkFile? {
  val byPath = files.associateBy { NetworkPath.from(it.path).value }
  return recentPaths.firstNotNullOfOrNull { byPath[NetworkPath.from(it).value] }
    ?: files.firstOrNull { isPartial(it) && !isWatched(it) }
    ?: files.firstOrNull { !isWatched(it) }
    ?: files.firstOrNull()
}
