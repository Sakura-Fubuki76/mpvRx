package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.domain.network.NetworkFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeResumeTest {
  private fun file(path: String) = NetworkFile(path.substringAfterLast('/'), path, 1, false)
  private val episodes = (1..3).map { file("/Anime/$it.mkv") }

  @Test fun latestEpisodeWinsOverEarlierPartialEpisodes() {
    assertEquals(episodes[2], animeResumeFile(episodes,
      listOf("/Other/1.mkv", episodes[2].path, episodes[0].path), { true }, { false }))
  }

  @Test fun watchedFlagDoesNotOverrideExplicitLastPlayedEpisode() {
    assertEquals(episodes[1], animeResumeFile(episodes, listOf(episodes[1].path), { false }, { true }))
  }

  @Test fun removedFilesAndExtrasDoNotReplaceARegularEpisode() {
    assertEquals(episodes[1], animeResumeFile(episodes,
      listOf("/Anime/deleted.mkv", "/Anime/Extra/PV.mkv", episodes[1].path), { false }, { false }))
  }

  @Test fun historyMatchesNormalizedPathsAndReturnsCurrentMetadata() {
    val current = file("/Anime//2.mkv").copy(durationMs = 120_000)
    assertEquals(current, animeResumeFile(listOf(current), listOf("/Anime/2.mkv"), { false }, { false }))
  }

  @Test fun absentHistoryFallsBackToPartialThenUnwatchedThenFirstEpisode() {
    assertEquals(episodes[1], animeResumeFile(episodes, emptyList(), { it == episodes[1] }, { false }))
    assertEquals(episodes[2], animeResumeFile(episodes, emptyList(), { false }, { it != episodes[2] }))
    assertEquals(episodes[0], animeResumeFile(episodes, emptyList(), { false }, { true }))
    assertNull(animeResumeFile(emptyList(), listOf("/Anime/1.mkv"), { true }, { false }))
  }
}
