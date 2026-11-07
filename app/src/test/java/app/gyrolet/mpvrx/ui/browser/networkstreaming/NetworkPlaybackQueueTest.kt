package app.gyrolet.mpvrx.ui.browser.networkstreaming

import app.gyrolet.mpvrx.domain.network.DlnaPath
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.preferences.NetworkSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import org.junit.Assert.*
import org.junit.Test

class NetworkPlaybackQueueTest {
  private val root = NetworkPath.from("/")
  private fun file(name: String, mime: String = "video/mp4") = NetworkFile(name, "/$name", 1, false, mimeType = mime)
  private val later = file("episode10.mkv")
  private val earlier = file("episode2.mkv")
  private val music = file("Track without extension", "audio/flac")

  private fun queue(protocol: NetworkProtocol, path: NetworkPath = root, enabled: Boolean = true,
    index: List<NetworkFile>? = null, clicked: NetworkFile = later, audio: Boolean = false) = networkPlaybackQueue(
    clicked, listOf(later, music, earlier), index, protocol, path, enabled, audio,
    NetworkSortType.Title, SortOrder.Ascending,
  )

  @Test fun dlnaAudioDoesNotDependOnLocalAudioPreference() {
    assertTrue(networkBrowserIncludesAudio(NetworkProtocol.DLNA, false))
    assertTrue(music.isPlayableNetworkAudio())
    for (protocol in listOf(NetworkProtocol.OPENLIST, NetworkProtocol.WEBDAV)) {
      assertFalse(networkBrowserIncludesAudio(protocol, true))
    }
    assertFalse(networkBrowserIncludesAudio(NetworkProtocol.SMB, false))
    assertTrue(networkBrowserIncludesAudio(NetworkProtocol.SMB, true))
  }

  @Test fun orderedDlnaAlbumKeepsServerOrderEvenWithAutomaticQueueOff() {
    val album = DlnaPath.child(root, "album-id", 'o')
    assertEquals(listOf(later, music, earlier), queue(NetworkProtocol.DLNA, album, enabled = false))
  }

  @Test fun ordinaryDlnaFoldersHonorAutomaticQueuePreferenceAndNaturalSort() {
    assertEquals(listOf(later), queue(NetworkProtocol.DLNA, enabled = false))
    assertEquals(listOf(earlier, later, music), queue(NetworkProtocol.DLNA))
  }

  @Test fun cloudQueuesKeepIndexResultsAndRemainVideoOnly() {
    val match = file("episode1.mkv")
    for (protocol in listOf(NetworkProtocol.OPENLIST, NetworkProtocol.WEBDAV)) {
      assertEquals(listOf(match, later), queue(protocol, index = listOf(later, music, match), audio = true))
    }
  }

  @Test fun nonDlnaPathsNeverOptIntoAlbumOrdering() {
    val dlnaLookingPath = DlnaPath.child(root, "album-id", 'o')
    assertEquals(listOf(later), queue(NetworkProtocol.OPENLIST, dlnaLookingPath, enabled = false))
    assertEquals(listOf(earlier, later), queue(NetworkProtocol.SMB, dlnaLookingPath))
  }

  @Test fun staleListingStillIncludesTheClickedItem() {
    val missing = file("new.mkv")
    assertEquals(listOf(earlier, later, missing), queue(NetworkProtocol.OPENLIST, clicked = missing))
  }
}
