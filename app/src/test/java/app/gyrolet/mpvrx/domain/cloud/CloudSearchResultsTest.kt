package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.domain.network.NetworkFile
import org.junit.Assert.*
import org.junit.Test

class CloudSearchResultsTest {
  @Test fun emptyIndexRetainsKnownMatchesButNotUnrelatedCachedFiles() {
    val match = NetworkFile("Episode.mkv", "/anime/Episode.mkv", 100, false)
    val unrelated = NetworkFile("Other.mkv", "/anime/Other.mkv", 100, false)
    assertEquals(listOf(match), mergeCloudSearchResults(emptyList(), listOf(match, unrelated), " episode "))
  }
  @Test fun normalizedDuplicateUsesFreshApiVersion() {
    val cached = NetworkFile("Episode.mkv", "anime/Episode.mkv", 100, false)
    val fresh = cached.copy(path = "/anime/Episode.mkv", size = 200, lastModified = 1000)
    assertEquals(listOf(fresh), mergeCloudSearchResults(listOf(fresh), listOf(cached), "Episode"))
  }
}
