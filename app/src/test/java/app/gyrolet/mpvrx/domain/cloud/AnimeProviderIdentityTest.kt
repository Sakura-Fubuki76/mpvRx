package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Recorded public provider responses: assert identities, not search rank. */
class AnimeProviderIdentityTest {
  private val fixture = Json.parseToJsonElement(javaClass.getResource("/anime/provider-identities.json")!!.readText()).jsonObject
  private fun candidates(label: String) = fixture["anilist"]!!.jsonObject[label]!!.jsonArray.map { value ->
    val row = value.jsonObject
    val titles = row["title"]!!.jsonObject
    fun title(key: String) = titles[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    AnimeTitleCandidate(row["id"]!!.jsonPrimitive.long, title("native"), title("romaji"), title("english"),
      row["synonyms"]!!.jsonArray.map { it.jsonPrimitive.content }, row["format"]!!.jsonPrimitive.content,
      row["episodes"]?.jsonPrimitive?.intOrNull ?: 0)
  }
  private fun subject(id: Long): AnimeSubject {
    val row = fixture["bangumi"]!!.jsonObject[id.toString()]!!.jsonObject
    return AnimeSubject(id, row["name"]!!.jsonPrimitive.content, row["name_cn"]!!.jsonPrimitive.content,
      aliases = row["infobox"]!!.jsonArray.filter { it.jsonObject["key"]!!.jsonPrimitive.content == "别名" }.flatMap {
        when (val value = it.jsonObject["value"]) {
          is JsonArray -> value.map { it.jsonObject["v"]!!.jsonPrimitive.content }
          is JsonPrimitive -> listOf(value.content)
          else -> emptyList()
        }
      }, format = row["platform"]!!.jsonPrimitive.content)
  }
  @Test fun combinedOvasKeepTheSpecificMemorySnowAlias() {
    val native = animeNativeQueries("Re Zero kara Hajimeru Isekai Seikatsu Memory Snow", candidates("Memory Snow"))
    assertEquals(listOf("Re:ゼロから始める異世界生活 Memory Snow"), native)
    assertEquals(225462L, matchAnimeSubject(native.single(), listOf(subject(225462)))?.id)
  }
  @Test fun threeEuphoniumMoviesResolveToDifferentNativeTitles() {
    val rows = candidates("Hibike! Euphonium")
    for ((suffix,id) in listOf("Chikai no Finale" to 216372L, "Kitauji Koukou Suisougaku-bu e Youkoso" to 152092L, "Todoketai Melody" to 211089L)) {
      val native = animeNativeQueries("Gekijouban Hibike! Euphonium $suffix", rows)
      assertEquals(1, native.size)
      assertEquals(id, matchAnimeSubject(native.single(), listOf(subject(id)))?.id)
    }
    assertTrue(animeNativeQueries("Hibike! Euphonium Movie", rows).isEmpty())
  }
  @Test fun abbreviatedMovieAndSummerSpecialHaveVerifiedIdentities() {
    assertEquals(64172L, matchAnimeSubject("THE IDOLM@STER MOVIE", listOf(subject(64172)))?.id)
    assertEquals(1, animeNativeQueries("THE IDOLM@STER MOVIE", candidates("THE IDOLM@STER")).size)
    val native = animeNativeQueries("Tantei Opera Milky Holmes SS", candidates("Milky SS"))
    assertEquals(22756L, matchAnimeSubject(native.single(), listOf(subject(22756)))?.id)
    assertEquals(244761L, matchAnimeSubject("Gekijouban SHIROBAKO", listOf(subject(244761)))?.id)
  }
  @Test fun officialOadAliasDoesNotBecomeOva() {
    assertEquals(45884L, matchAnimeSubject("To LOVE-Ru Darkness OADs", listOf(subject(45884), subject(210486)))?.id)
    assertNull(matchAnimeSubject("To LOVE-Ru Darkness OVA", listOf(subject(45884))))
  }
  @Test fun chineseSeasonNamesAndJapaneseFileAliasesKeepSeasonsSeparate() {
    val rows = listOf(subject(3326), subject(11145))
    assertEquals(3326L, matchAnimeSubject("笨蛋测试召唤兽 Season 1", rows)?.id)
    assertEquals(11145L, matchAnimeSubject("笨蛋测试召唤兽 Season 2", rows)?.id)
    val native = animeNativeQueries("バカとテストと召喚獣 第二季", candidates("Baka season 2"))
    assertEquals(11145L, matchAnimeSubject(native.single(), rows)?.id)
    assertEquals(animeIdentityKey("One Room Second Season"), animeIdentityKey("One Room Season 2"))
  }
  @Test fun alternativeCollectionCannotSilentlyBindOnlyOneHalf() {
    val native = animeNativeQueries("Tantei Opera Milky Holmes Alternative", candidates("Milky Alternative"))
    assertEquals(1, native.size)
    assertNull(matchAnimeSubject(native.single(), listOf(subject(47464),subject(54728))))
  }
  @Test fun filmsWithoutMovieInTheirTitleAndAmbiguityRemainSupported() {
    val movie = AnimeSubject(1,"君の名は。",format="剧场版")
    assertEquals(movie, matchAnimeSubject("君の名は。",listOf(movie)))
    assertNull(matchAnimeSubject("君の名は。",listOf(movie,movie.copy(id=2,format="TV"))))
  }
  @Test fun refreshedLibrariesOnlyCountDueMatchingWork() {
    val query = "directory-v3 | Anime | aliases-v3"
    val now = 10L * 24 * 60 * 60_000
    assertFalse(animeNeedsMatching(query,1,false,0,query,now))
    assertFalse(animeNeedsMatching(query,null,false,now,query,now))
    assertTrue(animeNeedsMatching(query,null,false,now-24*60*60_000,query,now))
    assertFalse(animeNeedsMatching(query+" | request-failed",null,false,now-60_000,query,now))
    assertTrue(animeNeedsMatching(query+" | request-failed",null,false,now-5*60_000,query,now))
    assertTrue(animeNeedsMatching(query.replace("v3","v2"),null,false,now,query,now))
    assertTrue(animeNeedsMatching(null,null,false,0,query,now))
    assertFalse(animeNeedsMatching(null,1,true,0,query,now))
  }
  @Test fun explicitNumberedBangumiPartsSplitFilesWithoutMovingThem() {
    val parts = animeNumberedSubjects("探偵オペラ ミルキィホームズ Alternative", listOf(subject(54728),subject(47464)))
    assertEquals(listOf(47464L,54728L), parts.values.map { it.id })
    assertTrue(animeNumberedSubjects("Unrelated",parts.values.toList()).isEmpty())
    assertTrue(animeNumberedSubjects("探偵オペラ ミルキィホームズ Alternative",listOf(subject(47464))).isEmpty())
    fun file(n: Int) = app.gyrolet.mpvrx.domain.network.NetworkFile("Alternative - 0$n.mkv","/Alternative/0$n.mkv",1,false)
    val group = AnimeVideoGroup("/Alternative","/Alternative","Alternative",listOf(file(1),file(2)),listOf("Alternative"))
    val bindings = parts.map { (n,subject) -> "/Alternative#bangumi:${subject.id}" to AnimePartBinding(group.key,n,subject.title) }.toMap()
    val split = animeSplitGroups(listOf(group),bindings)
    assertEquals(2,split.size)
    assertEquals(group.files,split.flatMap { it.files })
    assertEquals(group.directory,split.last().directory)
    assertEquals("侦探歌剧 少女福尔摩斯 Alternative TWO",split.last().query)
    assertEquals(listOf(group.copy(files=group.files+file(3))),animeSplitGroups(listOf(group.copy(files=group.files+file(3))),bindings))
  }
  @Test fun splitEpisodeOffsetUsesActualBangumiNumber() {
    val one = subject(47464).copy(episodes=listOf(AnimeEpisode(1,1.0,0,"")))
    val two = subject(54728).copy(episodes=listOf(AnimeEpisode(2,2.0,0,"")))
    assertEquals(0,animePartEpisodeOffset(1,one))
    assertEquals(0,animePartEpisodeOffset(2,two))
    assertEquals(1,animePartEpisodeOffset(2,two.copy(episodes=listOf(AnimeEpisode(2,1.0,0,"")))))
    assertNull(animePartEpisodeOffset(2,two.copy(episodes=listOf(AnimeEpisode(2,1.5,0,"")))))
  }
  @Test fun splitCollectionsAttachNestedExtrasOnlyToFirstSubject() {
    fun file(name: String, path: String) = app.gyrolet.mpvrx.domain.network.NetworkFile(name,path,1,false)
    val one=file("Alternative - 01.mkv","/A/01.mkv")
    val two=file("Alternative - 02.mkv","/A/02.mkv")
    val extra=file("PV01.mkv","/A/Extra/Deep/PV01.mkv")
    val credit=file("CV.mkv","/A/Extra/CV.mkv")
    val group=AnimeVideoGroup("/A","/A","Alternative",listOf(one,two,extra,credit),listOf("Alternative"))
    val split=animeSplitGroups(listOf(group),mapOf("one" to AnimePartBinding("/A",1,"ONE"),"two" to AnimePartBinding("/A",2,"TWO")))
    assertEquals(listOf(one,extra,credit),split.first().files)
    assertEquals(listOf(two),split.last().files)
    assertEquals(group.files.toSet(),split.flatMap { it.files }.toSet())
    assertEquals("探偵オペラ ミルキィホームズ Alternative",animePartBase(subject(47464).name))
  }
  @Test fun recentFeatureUsesLatestVideoOwnerIncludingSplitSubjects() {
    val one = app.gyrolet.mpvrx.domain.network.NetworkFile("A - 01.mkv","/A/01.mkv",1,false)
    val two = one.copy(name="A - 02.mkv",path="/A/02.mkv")
    val groups=listOf(AnimeVideoGroup("one","/A","ONE",listOf(one),listOf("ONE")),AnimeVideoGroup("two","/A","TWO",listOf(two),listOf("TWO")))
    assertEquals("two",animeFeaturedGroup(groups,listOf("/Other/01.mkv",two.path,one.path)))
    assertNull(animeFeaturedGroup(groups,emptyList()))
  }
}
