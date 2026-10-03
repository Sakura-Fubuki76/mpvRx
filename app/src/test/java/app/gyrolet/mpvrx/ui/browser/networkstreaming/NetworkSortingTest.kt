package app.gyrolet.mpvrx.ui.browser.networkstreaming

import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.preferences.NetworkSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSortingTest {
  private fun file(name: String, path: String = name, directory: Boolean = false) = NetworkFile(name, path, 1, directory, 1)
  @Test fun directoriesRemainFirstAndLongNumbersSortNaturally() {
    val files = listOf(file("v10.mp4"), file("folder10", directory = true), file("v2.mp4"), file("folder2", directory = true))
    assertEquals(listOf("folder2", "folder10", "v2.mp4", "v10.mp4"), files.sortedForNetworkBrowser(NetworkSortType.Title, SortOrder.Ascending).map { it.name })
    assertEquals(listOf("folder10", "folder2", "v10.mp4", "v2.mp4"), files.sortedForNetworkBrowser(NetworkSortType.Title, SortOrder.Descending).map { it.name })
  }
  @Test fun equalDateAndSizeUseTitleThenStablePath() {
    val files = listOf(file("EP2.mp4", "/b"), file("ep2.mp4", "/a"), file("ep10.mp4"))
    for (type in NetworkSortType.entries) {
      assertEquals(listOf("/a", "/b", "ep10.mp4"), files.sortedForNetworkBrowser(type, SortOrder.Ascending).map { it.path })
    }
  }
}
