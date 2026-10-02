package app.gyrolet.mpvrx.utils.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMatcherTest {
  @Test
  fun releaseTagsDoNotPreventMatching() {
    assertTrue(fuzzyMatchNames("[Group A] My Anime - 01 [1080p].mkv", "[Group B] My Anime - 01.zh.ass"))
  }

  @Test
  fun differentEpisodesNeverMatch() {
    assertFalse(fuzzyMatchNames("My Anime - 01.mkv", "My Anime - 02.ass"))
  }

  @Test
  fun unrelatedTitlesAndEmptyNamesDoNotMatch() {
    assertFalse(fuzzyMatchNames("First Adventure - 01.mkv", "Other Story - 01.ass"))
    assertFalse(fuzzyMatchNames("", ""))
  }
}
