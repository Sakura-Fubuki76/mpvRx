package app.gyrolet.mpvrx.domain.cloud

import org.junit.Assert.*
import org.junit.Test

class AnimeMetadataTest {
  @Test fun releaseTagsDoNotPolluteTitleOrEpisode() {
    val parsed = parseAnimeFilename("[Airota&Nekomoe kissaten&LoliHouse] Adachi to Shimamura - 01 [BDRip 1080p HEVC-10bit FLAC].mkv")
    assertEquals("Adachi to Shimamura", parsed.title)
    assertEquals(1.0, parsed.episode!!, 0.0)
    assertFalse(parsed.special)
  }
  @Test fun keepsSeasonAndSpecialEpisodeIdentity() {
    assertEquals("Anime Season 2", parseAnimeFilename("Anime S02E03.mkv").title)
    assertEquals("Anime 2nd Season", parseAnimeFilename("Anime 2nd Season - 03.mkv").title)
    assertTrue(parseAnimeFilename("Anime SP01.mkv").special)
    assertEquals(1.0, parseAnimeFilename("Anime SP01.mkv").episode!!, 0.0)
    assertEquals(12.5, parseAnimeFilename("作品 - 12.5 [1080p].mkv").episode!!, 0.0)
  }
  @Test fun supportsChineseEpisodeAndDoesNotTreatResolutionAsEpisode() {
    assertEquals("葬送的芙莉莲", parseAnimeFilename("葬送的芙莉莲 第01话.mp4").title)
    assertNull(parseAnimeFilename("作品 [1080p HEVC].mkv").episode)
  }
  @Test fun ambiguousSearchNeverBindsFirstOrMostPopularResult() {
    val one = AnimeSubject(1, "Anime", "动画")
    val two = AnimeSubject(2, "Anime", "动画")
    assertNull(matchAnimeSubject("Anime", listOf(one, two)))
    assertEquals(one, matchAnimeSubject("Ａｎｉｍｅ", listOf(one)))
    assertNull(matchAnimeSubject("Anime Season 2", listOf(one)))
  }
  @Test fun openingAndEndingRemainSeparateFromSpecialEpisodes() {
    assertEquals(2, parseAnimeFilename("Anime NCOP01.mkv").episodeType)
    assertEquals(2, parseAnimeFilename("Anime [NCOP] [01].mkv").episodeType)
    assertEquals(3, parseAnimeFilename("Anime NCED01.mkv").episodeType)
    assertEquals(1, parseAnimeFilename("Anime SP01.mkv").episodeType)
  }
  @Test fun mixedMoviesAreNotMergedAndSubtitleVersionsStayTogether() {
    fun file(name: String) = app.gyrolet.mpvrx.domain.network.NetworkFile(name, "/Movies/$name", 100, false)
    val groups = animeVideoGroups(listOf(file("[A] Alpha.mkv"), file("[B] Alpha.mkv"), file("Beta.mkv")))
    assertEquals(2, groups.size)
    assertEquals(2, groups.first { it.query == "Alpha" }.files.size)
    assertEquals(groups.map { it.key }, animeVideoGroups(listOf(file("[A] Alpha.mkv"), file("[B] Alpha.mkv"), file("Beta.mkv"))).map { it.key })
  }
  @Test fun specialsInheritSeriesAndKeepChineseAncestorForMatching() {
    val normal = app.gyrolet.mpvrx.domain.network.NetworkFile("[VCB] Hyouka - 01.mkv", "/冰菓/[VCB] Hyouka/01.mkv", 1, false)
    val special = normal.copy(name = "NCOP01.mkv", path = "/冰菓/[VCB] Hyouka/SPs/NCOP01.mkv")
    val groups = animeVideoGroups(listOf(normal, special))
    assertEquals(1, groups.size)
    assertEquals(2, groups.single().files.size)
    assertTrue(groups.single().queries.contains("冰菓"))
    assertTrue(isAnimeExtraVideo(special))
    assertFalse(isAnimeExtraVideo(normal))
  }
  @Test fun seasonMarkersAndExclamationDistinguishSequels() {
    assertNotEquals(animeNameKey("NEW GAME!"), animeNameKey("NEW GAME!!"))
    assertEquals(animeNameKey("One Room Second Season"), animeNameKey("One Room Season 2"))
    assertEquals("One Room Third Season", parseAnimeFilename("[VCB] One Room Third Season [1080p]").title)
  }

  @Test fun repeatedRowsAndSpecialOnlyGroupsNeverDuplicatePlaybackFiles() {
    fun file(name: String) = app.gyrolet.mpvrx.domain.network.NetworkFile(name, "/Series/Extra/PV/$name", 1, false)
    val a = file("MV3.mkv")
    val groups = animeVideoGroups(listOf(a, a, file("MV4.mkv")))
    groups.forEach { assertEquals(it.files.size, it.files.distinctBy { file -> file.path }.size) }
    assertEquals(2, groups.flatMap { it.files }.distinctBy { it.path }.size)
  }
  @Test fun manualAssignmentsRespectPathBoundariesAndFileOverrides() {
    fun file(name: String, path: String) = app.gyrolet.mpvrx.domain.network.NetworkFile(name, path, 1, false)
    val a = file("Alpha - 01.mkv", "/Alpha/01.mkv")
    val b = file("Beta - 01.mkv", "/Beta/01.mkv")
    val c = file("Beta - 02.mkv", "/Beta/02.mkv")
    val d = file("Gamma - 01.mkv", "/Beta2/01.mkv")
    val rows = listOf(a,b,c,d)
    val groups = regroupAnimeVideos(rows, mapOf("/Beta" to "/Alpha", b.path to "/Beta"))
    assertEquals(setOf(a.path,c.path), groups.first { it.key == "/Alpha" }.files.map { it.path }.toSet())
    assertEquals(listOf(b.path), groups.first { it.key == "/Beta" }.files.map { it.path })
    assertTrue(groups.flatMap { it.files }.any { it.path == d.path })
    assertEquals(rows.map { it.path }.toSet(), groups.flatMap { it.files }.map { it.path }.toSet())
    assertEquals(animeVideoGroups(rows), regroupAnimeVideos(rows, mapOf("/Beta" to "/missing")))
  }
}
