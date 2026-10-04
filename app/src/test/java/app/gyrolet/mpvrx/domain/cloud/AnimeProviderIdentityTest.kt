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
    assertTrue(animeNeedsMatching(query,null,false,now,query,now,retryUnmatched = true))
    assertFalse(animeNeedsMatching(query,1,false,now,query,now,retryUnmatched = true))
    assertFalse(animeNeedsMatching(query,1,true,now,query,now,retryUnmatched = true))
  }
  @Test fun cafeAccentsSeasonShorthandAndLongVowelSpellingsRetainIdentity() {
    val first = AnimeTitleCandidate(154412, "女神のカフェテラス", "Megami no Café Terrace", format = "TV")
    val second = AnimeTitleCandidate(0, "女神のカフェテラス 第2期", "Megami no Café Terrace 2nd Season", format = "TV")
    assertEquals(listOf(first.native), animeNativeQueries("Megami no Cafe Terrace", listOf(first, second)))
    assertEquals(listOf(second.native), animeNativeQueries("Megami no Cafe Terrace S2", listOf(first, second)))
    val sisters = AnimeTitleCandidate(167984, "大室家 dear sisters", "Oomuro-ke: dear sisters", format = "MOVIE")
    val friends = sisters.copy(id = 1, native = "大室家 dear friends", romaji = "Oomuro-ke: dear friends")
    assertEquals(listOf(sisters.native), animeNativeQueries("Ohmuro-ke - dear sisters", listOf(sisters, friends)))
    assertTrue(animeNativeQueries("Ohmuro-ke - dear sisters", listOf(sisters, sisters.copy(id = 2))).isEmpty())
    assertNotEquals(animeNameKey("が"), animeNameKey("か"))
  }
  @Test fun realProviderDiscoveryBridgesLongVowelsAndMixedMovieAliases() {
    assertTrue(animeDiscoveryTerms("Ohmuro-ke - dear sisters").take(4).contains("Oomuro-ke - dear sisters"))
    assertEquals(listOf("大室家 dear sisters"), animeNativeQueries("Ohmuro-ke - dear sisters", candidates("Oomuro-ke dear sisters")))
    val query = "Gekijouban DanMachi Orion no Ya"
    assertTrue(animeDiscoveryTerms(query).take(4).contains("DanMachi"))
    val native = animeNativeQueries(query, candidates("DanMachi"))
    assertEquals(listOf("劇場版 ダンジョンに出会いを求めるのは間違っているだろうか ─ オリオンの矢 ─"), native)
    val bangumi = AnimeSubject(238005, "劇場版 ダンジョンに出会いを求めるのは間違っているだろうか －オリオンの矢－")
    assertEquals(bangumi, matchAnimeSubject(native.single(), listOf(bangumi)))
    assertTrue(animeNativeQueries("Gekijouban DanMachi Unknown Subtitle", candidates("DanMachi")).isEmpty())
  }
  @Test fun sequelPunctuationAndMoviePartNumbersAreIdentityEvidence() {
    assertNotEquals(animeIdentityKey("Gochuumon wa Usagi Desuka?"), animeIdentityKey("Gochuumon wa Usagi Desuka??"))
    assertNotEquals(animeIdentityKey("Yuru Yuri Nachuyachumi!"), animeIdentityKey("Yuru Yuri Nachuyachumi!+"))
    assertNotEquals(animeIdentityKey("Yuru Yuri"), animeIdentityKey("Yuru Yuri♪♪"))
    assertEquals(animeIdentityKey("Gekijoban Non Non Biyori Vacation"), animeIdentityKey("Gekijouban Non Non Biyori Vacation"))
    assertEquals(animeIdentityKey("Gekijōban Non Non Biyori Vacation"), animeIdentityKey("Gekijouban Non Non Biyori Vacation"))
    assertEquals(animeIdentityKey("Puella Magi Madoka Magica Movie 1 Beginnings"), animeIdentityKey("Puella Magi Madoka Magica the Movie Part I: Beginnings"))
    assertNotEquals(animeIdentityKey("Example Movie 1"), animeIdentityKey("Example Movie 2"))
    assertTrue(animeDiscoveryTerms("Yuru Yuri 2").take(4).contains("Yuru Yuri"))
  }
  @Test fun providerAliasesDistinguishSeasonsAndExpandVerifiedFranchiseAbbreviations() {
    for (suffix in listOf("?", "??")) {
      val rows = candidates("Gochuumon")
      val expected = rows.single { animeIdentityKey(it.romaji) == animeIdentityKey("Gochuumon wa Usagi Desuka$suffix") }.native
      assertEquals(listOf(expected), animeNativeQueries("Gochuumon wa Usagi Desuka$suffix", rows))
    }
    val yuru = candidates("YuruYuri")
    assertEquals(listOf(yuru.single { it.id == 12403L }.native), animeNativeQueries("Yuru Yuri 2", yuru))
    assertEquals(listOf(yuru.single { it.id == 21088L }.native), animeNativeQueries("Yuru Yuri 3", yuru))
    val yuuna = candidates("Yuuki Yuuna")
    assertEquals(listOf("Yuuki Yuuna wa Yuusha de Aru"), animeFranchiseDiscoveryTerms("YuYuYu - Dai Mankai no Shou", yuuna))
    assertEquals(listOf(yuuna.single { it.id == 122292L }.native), animeNativeQueries("YuYuYu - Dai Mankai no Shou", yuuna))
    assertEquals(listOf(yuuna.single { it.id == 97769L }.native), animeNativeQueries("YuYuYu - Yuusha no Shou", yuuna))
    assertTrue(animeNativeQueries("YuYuYu - Unknown no Shou", yuuna).isEmpty())
    assertTrue(animeFranchiseDiscoveryTerms("YuYuYu - Dai Mankai no Shou", yuuna + yuuna.single { it.id == 20800L }.copy(id = -1)).isEmpty())
  }
  @Test fun providerKanaAndSideStoryDescriptorsBridgeWithoutDroppingSubtitles() {
    val native = animeNativeQueries("Yuru Yuri 3", candidates("YuruYuri")).single()
    assertEquals(127573L, matchAnimeSubject(native, listOf(subject(127573)))?.id)
    val sideStory = animeNativeQueries("Higurashi no Naku Koro ni Gaiden Nekogoroshi-hen", candidates("Higurashi Nekogoroshi")).single()
    assertEquals(37870L, matchAnimeSubject(sideStory, listOf(subject(37870)))?.id)
    assertNull(matchAnimeSubject("Gaiden", listOf(subject(37870))))
    assertTrue(animeNativeQueries("Higurashi no Naku Koro ni Gaiden Unknown-hen", candidates("Higurashi Nekogoroshi")).isEmpty())
    assertNull(matchAnimeSubject(sideStory, listOf(subject(37870), subject(37870).copy(id = -1))))
  }
  @Test fun explicitMovieCollectionsKeepFilmNumbersAndEveryFile() {
    val first = "Made in Abyss Movie 1 Tabidachi no Yoake"
    val second = "Made in Abyss Movie 2 Hourou Suru Tasogare"
    val root = "/[Group] Made in Abyss Movie [Ma10p_1080p]"
    fun file(title: String, sub: String = "") = app.gyrolet.mpvrx.domain.network.NetworkFile("[Group] $title [1080p].mkv", "$root/$sub[Group] $title [1080p].mkv", 1, false)
    val secondMenu = file("$second [Menu]", "SPs/")
    val files = listOf(file(first), file(second), file("Marulk-chan no Nichijou - 01"), file("Marulk-chan no Nichijou - 02"), file("PV01", "SPs/"), secondMenu)
    assertEquals(first, parseAnimeFilename(files.first().name).title)
    assertNull(parseAnimeFilename(files.first().name).episode)
    val groups = animeVideoGroups(files)
    assertEquals(3, groups.size)
    assertEquals(files.toSet(), groups.flatMap { it.files }.toSet())
    assertEquals(files.size, groups.sumOf { it.files.size })
    assertEquals(first, groups.first().query)
    assertTrue(secondMenu in groups.single { it.query == second }.files)
    assertEquals(listOf("劇場版 メイドインアビス 旅立ちの夜明け"), animeNativeQueries(first, candidates("Made in Abyss")))
    assertEquals(listOf("劇場版 メイドインアビス 放浪する黄昏"), animeNativeQueries(second, candidates("Made in Abyss")))
    val firstNative = animeNativeQueries(first, candidates("Made in Abyss")).single()
    val secondNative = animeNativeQueries(second, candidates("Made in Abyss")).single()
    assertEquals(240798L, matchAnimeSubject(firstNative, listOf(subject(240798), subject(240799)))?.id)
    assertEquals(240799L, matchAnimeSubject(secondNative, listOf(subject(240798), subject(240799)))?.id)
    assertNotEquals(animeIdentityKey("劇場版 Example【前編】"), animeIdentityKey("劇場版 Example【後編】"))
    assertTrue(animeNativeQueries("Made in Abyss Movie 1 Unknown Subtitle", candidates("Made in Abyss")).isEmpty())
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
