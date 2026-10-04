package app.gyrolet.mpvrx.ui.browser.networkstreaming

import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.preferences.FolderViewMode

/** Project the persisted subtree without making fresh network requests when changing view mode. */
internal fun cloudFilesForViewMode(files: List<NetworkFile>, currentPath: String, mode: FolderViewMode): List<NetworkFile> {
  val root = NetworkPath.from(currentPath).value
  fun parent(path: String) = path.substringBeforeLast('/', "").ifEmpty { "/" }
  val scoped = files.filter { it.path != root && (root == "/" || it.path.startsWith("$root/")) }
  val videos = scoped.filter { !it.isDirectory && it.isPlayableNetworkVideo() }
  return when (mode) {
    FolderViewMode.FileManager -> scoped.filter { parent(it.path) == root && (it.isDirectory || it.isPlayableNetworkVideo()) }
    FolderViewMode.MediaLibrary -> videos
    FolderViewMode.AlbumView -> {
      val videoFolders = videos.map { parent(it.path) }.toSet()
      scoped.filter { it.isDirectory && it.path in videoFolders } + videos.filter { parent(it.path) == root }
    }
  }
}
