package app.gyrolet.mpvrx.utils.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubtitleDiscoveryTest {
  private fun file(name: String, path: String = "/video/$name") = SubtitleDiscovery.Entry(name, path, false)
  private fun folder(name: String, path: String = "/video/$name") = SubtitleDiscovery.Entry(name, path, true)

  private fun discover(
    video: String,
    tree: Map<String, List<SubtitleDiscovery.Entry>>,
    videoPath: String = "/video/$video",
  ): List<String> = runBlocking {
    SubtitleDiscovery.find("/video", video, videoPath, { true }) { path, limit, _ ->
      val entries = tree[path].orEmpty()
      SubtitleDiscovery.Listing(entries.take(limit), entries.size <= limit)
    }.map { it.name }
  }

  @Test fun customAnimeReleaseTagsStillMatchInsideUpstreamDiscovery() {
    val video = "[Group A] My Anime - 01 [1080p].mkv"
    val good = "[Group B] My Anime - 01.zh.ass"
    assertEquals(listOf(good), discover(video, mapOf("/video" to listOf(
      file(video), file(good), file("[Group B] My Anime - 02.zh.ass"), file("Other Story - 01.ass"),
    ))))
  }

  @Test fun animeEpisodeFoldersCannotLeakGenericTracksFromAnotherEpisode() {
    val video = "My Anime - 01.mkv"
    assertEquals(listOf("English.srt"), discover(video, mapOf(
      "/video" to listOf(file(video), folder("My Anime - 01"), folder("My Anime - 02")),
      "/video/My Anime - 01" to listOf(file("English.srt", "/video/My Anime - 01/English.srt")),
      "/video/My Anime - 02" to listOf(file("Chinese.ass", "/video/My Anime - 02/Chinese.ass")),
    )))
  }

  @Test fun fuzzyTitleOverlapDoesNotOverrideUpstreamSequelRules() {
    assertEquals(emptyList<String>(), discover("Movie.mkv", mapOf(
      "/video" to listOf(file("Movie.mkv"), file("Movie.2.srt")),
    )))
  }

  @Test fun standardSeasonEpisodeMatchingRejectsOtherEpisodesAndSeasons() {
    val video = "Show.S01E02.mkv"
    assertEquals(listOf("Show.S01E02.zh.srt"), discover(video, mapOf("/video" to listOf(
      file(video), file("Show.S01E02.zh.srt"), file("Show.S01E03.zh.srt"), file("Show.S02E02.zh.srt"),
    ))))
  }

  @Test fun nestedSubtitleFoldersUseUpstreamLanguageLabels() {
    val video = "Movie.mkv"
    assertEquals(listOf("English.srt"), discover(video, mapOf(
      "/video" to listOf(file(video), folder("Subs")),
      "/video/Subs" to listOf(folder("Languages", "/video/Subs/Languages")),
      "/video/Subs/Languages" to listOf(file("English.srt", "/video/Subs/Languages/English.srt"),
        file("README.txt"), file("metadata.json"), file("data.xml")),
    )))
  }

  @Test fun genericTracksAreNotAssignedToEveryVideoInAnAlbum() {
    assertEquals(emptyList<String>(), discover("First.mkv", mapOf(
      "/video" to listOf(file("First.mkv"), file("Second.mkv"), folder("Subs")),
      "/video/Subs" to listOf(file("English.srt")),
    )))
  }

  @Test fun providerIdsAreResolvedUsingTheListedDisplayName() {
    assertEquals(listOf("Show.S01E02.zh.ass"), discover("Now playing", mapOf(
      "/video" to listOf(file("Show.S01E02.mkv", "/video/object123"), file("Show.S01E02.zh.ass")),
    ), videoPath = "/video/object123"))
  }

  @Test fun vobSubCompanionIsNotAddedTwice() {
    assertEquals(listOf("Movie.idx"), discover("Movie.mkv", mapOf(
      "/video" to listOf(file("Movie.mkv"), file("Movie.idx"), file("Movie.sub")),
    )))
  }

  @Test fun directoriesDeeperThanTwoLevelsAreNotVisited() {
    assertEquals(emptyList<String>(), discover("Movie.mkv", mapOf(
      "/video" to listOf(file("Movie.mkv"), folder("Subs")),
      "/video/Subs" to listOf(folder("one", "/video/Subs/one")),
      "/video/Subs/one" to listOf(folder("two", "/video/Subs/one/two")),
      "/video/Subs/one/two" to listOf(file("Movie.zh.srt")),
    )))
  }

  @Test fun staleGenerationDoesNotStartListing() = runBlocking {
    val result = SubtitleDiscovery.find("/video", "Movie.mkv", isCurrent = { false }) { _, _, _ ->
      fail("stale generation performed IO")
      SubtitleDiscovery.Listing(emptyList())
    }
    assertTrue(result.isEmpty())
  }

  @Test fun generationChangeDuringListingDiscardsItsResults() = runBlocking {
    var active = true
    val result = SubtitleDiscovery.find("/video", "Movie.mkv", isCurrent = { active }) { _, _, _ ->
      active = false
      SubtitleDiscovery.Listing(listOf(file("Movie.srt")))
    }
    assertTrue(result.isEmpty())
  }

  @Test fun directoryWorkIsBounded() = runBlocking {
    var calls = 0
    SubtitleDiscovery.find("/video", "Movie.mkv", isCurrent = { true }) { path, limit, _ ->
      calls++
      assertTrue(limit in 1..SubtitleDiscovery.MAX_ENTRIES)
      SubtitleDiscovery.Listing(if (path == "/video") (0..99).map { folder("dir$it") } else emptyList())
    }
    assertTrue(calls <= SubtitleDiscovery.MAX_DIRECTORIES)
  }

  @Test fun incompleteDirectoryListingsAreNeverCachedAsComplete() {
    val cache = SubtitleDirectoryCache()
    cache.put("folder", 1L, SubtitleDiscovery.Listing(listOf(file("Movie.srt")), complete = false))
    assertNull(cache.get("folder", 1L, 10))
  }

  @Test fun cachedListingsRespectStampAndEntryBudget() {
    val cache = SubtitleDirectoryCache()
    cache.put("folder", 1L, SubtitleDiscovery.Listing(listOf(file("one.srt"), file("two.srt"))))
    assertFalse(cache.get("folder", 1L, 1)!!.complete)
    assertEquals(1, cache.get("folder", 1L, 1)!!.entries.size)
    assertNull(cache.get("folder", 2L, 10))
  }
}
