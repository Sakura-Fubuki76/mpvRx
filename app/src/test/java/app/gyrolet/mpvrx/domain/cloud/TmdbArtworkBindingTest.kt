package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TmdbArtworkBindingTest {
  private fun row(value: String) = Json.parseToJsonElement(value).jsonObject
  @Test fun theatricalMarkerAndAllAliasesRemainSearchable() {
    val subject = AnimeSubject(1, "メイドインアビス 深き魂の黎明", aliases = listOf("one", "two", "three", "来自深渊深魂的黎明"), date = "2020-01-17")
    assertTrue(tmdbArtworkNames(subject).contains("来自深渊深魂的黎明"))
    assertNotNull(tmdbMovieBinding(subject, row("""{"id":573730,"original_title":"劇場版メイドインアビス 深き魂の黎明","release_date":"2020-01-17"}""")))
  }
  @Test fun ovaUsesMovieImagesWithoutChangingBangumiIdentity() {
    val subject = AnimeSubject(225462,"Re:Zero Memory Snow",date="2019-06-07",format="OVA")
    val binding = tmdbMovieBinding(subject,row("""{"id":532321,"title":"Re:Zero Memory Snow","release_date":"2018-10-06"}"""))!!.binding
    assertEquals("movie",binding.type)
    assertEquals(225462L,subject.id)
  }
  @Test fun sequelUsesSeasonPosterNotEntireSeriesIdentity() {
    val subject=AnimeSubject(1,"Example Second Season",date="2023-07-09",episodes=(1..2).map { AnimeEpisode(it.toLong(),it.toDouble(),0,"Title $it") })
    val series=row("""{"id":12,"name":"Example","number_of_episodes":4}""")
    val season=row("""{"season_number":2,"air_date":"2023-07-09","episodes":[{"episode_number":1,"name":"A","air_date":"2023-07-09"},{"episode_number":2,"name":"B"}]}""")
    val binding=tmdbSeasonBinding(subject,series,season)!!.binding
    assertEquals(2,binding.season);assertEquals("season",binding.scope)
    assertTrue(binding.episodes.isEmpty())
  }
  @Test fun mergedSeasonUsesEpisodeRangeAndCannotBorrowWholeSeasonArt() {
    val subject=AnimeSubject(1,"Example Season 2",date="2023-07-09",episodes=listOf(AnimeEpisode(1,1.0,0,"Second opening"),AnimeEpisode(2,2.0,0,"Second ending")))
    val series=row("""{"id":12,"name":"Example","number_of_episodes":4}""")
    val season=row("""{"season_number":1,"air_date":"2021-07-04","episodes":[{"episode_number":1,"name":"First opening","air_date":"2021-07-04"},{"episode_number":2,"name":"First ending"},{"episode_number":3,"name":"Second opening","air_date":"2023-07-09"},{"episode_number":4,"name":"Second ending"}]}""")
    val binding=tmdbSeasonBinding(subject,series,season)!!.binding
    assertEquals(listOf(3,4),binding.episodes);assertEquals("episodes",binding.scope)
  }
  @Test fun specialTitleMatchesAcrossYearBoundaryButDateAloneDoesNot() {
    val subject=AnimeSubject(1,"Example Alternative TWO",date="2012-12-31",episodes=listOf(AnimeEpisode(1,1.0,0,"Alternative TWO")))
    val series=row("""{"id":12,"name":"Example"}""")
    val season=row("""{"season_number":0,"episodes":[{"episode_number":2,"name":"Alternative ONE","air_date":"2012-08-25"},{"episode_number":3,"name":"Alternative TWO","air_date":"2013-01-03"}]}""")
    assertEquals(listOf(3),tmdbSeasonBinding(subject,series,season)!!.binding.episodes)
    assertNull(tmdbSeasonBinding(subject.copy(name="Different special",episodes=listOf(AnimeEpisode(2,1.0,0,"No matching title"))),series,season))
  }
  @Test fun duplicateRecordsRequireVerifiedStructureAndEstablishedRecord() {
    val a=TmdbBindingCandidate(AnimeArtworkBinding("tv",1,2,scope="season"),130,100)
    val b=a.copy(binding=a.binding.copy(id=2),votes=0)
    assertEquals(a.binding,selectTmdbBinding(listOf(a,b)))
    assertNull(selectTmdbBinding(listOf(a,b.copy(votes=50))))
  }
  @Test fun liveEventsAllowMusicWithoutAcceptingUnrelatedDrama() {
    val subject=AnimeSubject(1,"Live event",format="演出")
    assertTrue(tmdbAcceptsGenre(subject,listOf(10402)))
    assertFalse(tmdbAcceptsGenre(subject,listOf(18)))
    assertFalse(tmdbAcceptsGenre(subject.copy(format="TV"),listOf(10402)))
  }
  @Test fun explicitManualImagesSurviveSerializationAndOverrideAutomaticImages() {
    val json=Json { encodeDefaults=true }
    val subject=AnimeSubject(1,"Example",cover="bangumi",artworkCover="auto-detail",artworkPoster="auto-library",titleLogo="auto-logo",manualArtworkCover="chosen",manualArtworkPoster="home",manualArtworkLogo="")
    val restored=json.decodeFromString<AnimeSubject>(json.encodeToString(AnimeSubject.serializer(),subject))
    assertEquals("chosen",restored.detailCover);assertEquals("home",restored.libraryCover);assertEquals("",restored.displayLogo)
    assertEquals("auto-logo",restored.copy(manualArtworkLogo=null).displayLogo)
  }
  @Test fun multilingualEpisodePrefixesAndRubyReadingsDoNotChangeIdentity() {
    val subject=AnimeSubject(1,"Example special",date="2011-08-25",episodes=listOf(AnimeEpisode(1,1.0,0,"さようなら小衣ちゃん永遠に")))
    val series=row("""{"id":12,"name":"Example"}""")
    val season=row("""{"season_number":0,"episodes":[{"episode_number":1,"name":"第1話 さようなら小衣ちゃん永遠(とわ)に","air_date":"2011-08-25"}]}""")
    assertEquals(listOf(1),tmdbSeasonBinding(subject,series,season)!!.binding.episodes)
  }

}
