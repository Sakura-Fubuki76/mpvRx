package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.domain.network.NetworkFile
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnimeProviderMappingsTest {
  private val fixture = Json.parseToJsonElement(javaClass.getResource("/anime/provider-mappings.json")!!.readText()).jsonObject
  private fun subject(id: Long): AnimeSubject {
    val row = fixture["subjects"]!!.jsonObject[id.toString()]!!.jsonObject
    val episodes = fixture["episodes"]!!.jsonObject[id.toString()]?.jsonArray.orEmpty().map {
      val ep = it.jsonObject
      AnimeEpisode(ep["id"]!!.jsonPrimitive.long, ep["sort"]!!.jsonPrimitive.double,
        ep["type"]!!.jsonPrimitive.int, ep["name_cn"]!!.jsonPrimitive.content, ep["name"]!!.jsonPrimitive.content)
    }
    return AnimeSubject(id, row["name"]!!.jsonPrimitive.content, row["name_cn"]!!.jsonPrimitive.content,
      episodes = episodes, format = row["platform"]!!.jsonPrimitive.content, subjectType = row["type"]!!.jsonPrimitive.int)
  }
  private fun group(title: String, count: Int) = AnimeVideoGroup("/work", "/work", title,
    (1..count).map { NetworkFile("$title - ${it.toString().padStart(2, '0')}.mkv", "/work/$it.mkv", 1, false) })
  private fun yuuna() = fixture["yuuna"]!!.jsonArray.map {
    val row = it.jsonObject
    val titles = row["title"]!!.jsonObject
    AnimeTitleCandidate(row["id"]!!.jsonPrimitive.long, titles["native"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      titles["romaji"]?.jsonPrimitive?.contentOrNull.orEmpty(), titles["english"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      row["synonyms"]!!.jsonArray.map { it.jsonPrimitive.content }, row["format"]!!.jsonPrimitive.content,
      row["episodes"]?.jsonPrimitive?.intOrNull ?: 0)
  }
  @Test fun liveEventsUseVideoSubjectsAndRejectOtherMedia() {
    val rows = listOf(subject(216792), subject(544183), subject(544184))
    assertEquals(216792L, matchAnimeEvent("Rabbit House Tea Party 2016", rows)?.id)
    assertEquals(544184L, matchAnimeEvent("K-ON! Live Event ~Come with Me!!~", rows)?.id)
    assertEquals(544183L, matchAnimeEvent("K-ON! Live Event ~LET'S GO!~", rows)?.id)
    assertNull(matchAnimeEvent("K-ON!", rows))
    assertNull(matchAnimeEvent("Rabbit House Tea Party 2016", listOf(rows[0].copy(subjectType = 3))))
    assertNull(matchAnimeEvent("Rabbit House Tea Party 2016", listOf(rows[0], rows[0].copy(id = 999))))
  }
  @Test fun firstYuunaChapterRequiresVerifiedAliasAndTwelveEpisodes() {
    assertEquals(listOf("結城友奈は勇者である"), animeNativeQueries("YuYuYu - Yuuki Yuuna no Shou", yuuna(), 12))
    assertTrue(animeNativeQueries("YuYuYu - Yuuki Yuuna no Shou", yuuna(), 6).isEmpty())
  }
  @Test fun threeYuunaMoviesRequireCompleteProviderParts() {
    val query = animeNativeQueries("YuYuYu - Movie", yuuna(), 3).single()
    val rows = listOf(subject(196259), subject(207167), subject(207168))
    assertEquals(listOf(196259L, 207167L, 207168L), animeNumberedSubjects(query, rows).values.map { it.id })
    assertTrue(animeNativeQueries("YuYuYu - Movie", yuuna(), 2).isEmpty())
  }
  @Test fun secondSeasonChaptersHaveDifferentEpisodeOffsets() {
    val season = subject(195937)
    val chapters = animeCompoundChapters(season.name)
    assertEquals(2, chapters.size)
    assertEquals(season.id, matchAnimeChapter(chapters[0], listOf(season))?.id)
    assertEquals(0, animeChapterEpisodeOffset(chapters[0], group("YuYuYu", 6), season))
    assertEquals(-6, animeChapterEpisodeOffset(chapters[1], group("YuYuYu", 6), season))
    assertNull(animeChapterEpisodeOffset(chapters[1], group("YuYuYu", 5), season))
    val recap = NetworkFile("YuYuYu - Yuusha no Shou [00].mkv", "/work/0.mkv", 1, false)
    assertEquals(-6, animeChapterEpisodeOffset(chapters[1], group("YuYuYu", 6).let { it.copy(files = it.files + recap) }, season))
    assertEquals(6.5, animeMappedEpisode(season, recap.name, -6, true)?.number)
    assertNull(animeMappedEpisode(season, recap.name, 0, true))
    assertTrue(animeDiscoveryTerms(chapters[0]).take(3).contains(subject(109328).name))
  }
  @Test fun monogatariSourcesUseOriginalTitlesAndAccountForRecaps() {
    val season = subject(68812)
    listOf(Triple("猫物語 (白)", 6, 0), Triple("傾物語", 5, -6), Triple("囮物語", 5, -11),
      Triple("鬼物語", 4, -16), Triple("恋物語", 6, -20)).forEach { (source, count, offset) ->
      assertEquals(source, offset, animeSourceEpisodeOffset(source, group("Monogatari", count), season))
    }
    assertNull(animeSourceEpisodeOffset("猫物語 (白)", group("Monogatari", 7), season))
    assertNull(animeSourceEpisodeOffset("Unknown", group("Monogatari", 6), season))
  }
  @Test fun sharedProviderCardsKeepEveryFilesOriginalEpisodeOffset() {
    val first = group("Washio", 6).copy(key = "/series/first", directory = "/series/first",
      files = group("Washio", 6).files.map { it.copy(path = it.path.replace("/work", "/series/first")) })
    val second = group("Yuusha", 6).copy(key = "/series/second", directory = "/series/second",
      files = group("Yuusha", 6).files.map { it.copy(path = it.path.replace("/work", "/series/second")) })
    val unmatched = group("Unknown", 1)
    val merged = animeMergeSubjectGroups(listOf(second, unmatched, first),
      mapOf(first.key to 195937L, second.key to 195937L), mapOf(first.key to 0, second.key to -6))
    assertEquals(2, merged.size)
    val card = merged.single { first.key in it.sourceKeys }
    assertEquals(first.key, card.key)
    assertEquals("/series", card.directory)
    assertEquals(12, card.files.size)
    assertEquals(12, card.regularPaths.size)
    assertEquals(-6, card.episodeOffsets[second.files.first().path])
    assertEquals(card.key, animeFeaturedGroup(merged, listOf(second.files.first().path)))
  }
}
