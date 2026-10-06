package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class TmdbArtworkSelectionTest {
  private fun client(responses: Map<String,String>) = TmdbArtworkClient(OkHttpClient.Builder().addInterceptor { chain ->
    val body = responses[chain.request().url.encodedPath.removePrefix("/3/")] ?: error("Unexpected endpoint")
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
      .body(body.toResponseBody("application/json".toMediaType())).build()
  }.build())
  @Test fun fullWorkUsesGlobalNeutralPosterEvenWhenSeasonOnlyHasTitledArt() = runBlocking {
    val client=client(mapOf(
      "search/multi" to """{"results":[{"id":1,"media_type":"tv","name":"Example","genre_ids":[16],"first_air_date":"2024-07-01"}]}""",
      "tv/1" to """{"id":1,"name":"Example","number_of_episodes":1,"first_air_date":"2024-07-01","seasons":[{"season_number":1,"episode_count":1}]}""",
      "tv/1/season/1" to """{"season_number":1,"air_date":"2024-07-01","episodes":[{"episode_number":1,"name":"The opening","air_date":"2024-07-01"}]}""",
      "tv/1/images" to """{"posters":[{"file_path":"/neutral.jpg","iso_639_1":null,"width":1600},{"file_path":"/titled.jpg","iso_639_1":"ja","width":1600}],"logos":[{"file_path":"/logo.png","iso_639_1":"ja"}]}""",
      "tv/1/season/1/images" to """{"posters":[{"file_path":"/season-title.jpg","iso_639_1":"ja"}]}"""
    ))
    val result=client.fetch(AnimeSubject(1,"Example",date="2024-07-01",episodes=listOf(AnimeEpisode(1,1.0,0,"The opening"))),"test-credential")!!
    assertEquals("work",result.binding.scope)
    assertTrue(result.cover.endsWith("/neutral.jpg"));assertTrue(result.poster.endsWith("/titled.jpg"))
  }
  @Test fun verifiedSeasonAndEpisodeRangeUseTheSeriesLogo() = runBlocking {
    val responses = mapOf(
      "search/multi" to """{"results":[{"id":1,"media_type":"tv","name":"Example","genre_ids":[16]}]}""",
      "tv/1" to """{"id":1,"name":"Example","number_of_episodes":12,"seasons":[{"season_number":2,"episode_count":2}]}""",
      "tv/1/season/2" to """{"season_number":2,"air_date":"2024-07-01","episodes":[{"episode_number":1,"name":"The opening","air_date":"2024-07-01"},{"episode_number":2,"name":"The return","air_date":"2024-07-08"}]}""",
      "tv/1/images" to """{"posters":[{"file_path":"/series.jpg","iso_639_1":null}],"logos":[{"file_path":"/zh.png","iso_639_1":"zh","width":3000},{"file_path":"/ja.png","iso_639_1":"ja","width":1500}]}""",
      "tv/1/season/2/images" to """{"posters":[{"file_path":"/season.jpg","iso_639_1":null}]}"""
    )
    val season = AnimeSubject(1,"Example",date="2024-07-01",episodes=listOf(
      AnimeEpisode(1,1.0,0,"The opening"),AnimeEpisode(2,2.0,0,"The return")))
    val result = client(responses).fetch(season,"test-credential")!!
    assertEquals("season",result.binding.scope)
    assertEquals("https://image.tmdb.org/t/p/original/ja.png",result.logo)
    assertTrue(result.cover.endsWith("/season.jpg"))
    val range = client(responses - "tv/1/season/2/images")
      .fetch(season.copy(episodes=season.episodes.take(1)),"test-credential")!!
    assertEquals("episodes",range.binding.scope)
    assertEquals(listOf(1),range.binding.episodes)
    assertEquals(result.logo,range.logo)
    assertEquals("",range.cover);assertEquals("",range.poster)
  }
  @Test fun logoAssetsCannotUsePostersAndLibraryChoicesExcludeNeutralPosters() = runBlocking {
    val client=client(mapOf("movie/1/images" to """{"posters":[{"file_path":"/neutral.jpg","iso_639_1":null,"width":1600},{"file_path":"/title.jpg","iso_639_1":"zh","width":1400}],"logos":[{"file_path":"/logo.png","iso_639_1":"ja"},{"file_path":"/unsupported.svg","iso_639_1":"en"}]}"""))
    val hit=AnimeArtworkHit("TMDB",1,"movie","Example","")
    val logos=client.imageChoices(hit,"test-credential",AnimeArtworkTarget.LOGO)
    assertEquals(1,logos.size);assertTrue(logos.single().preview.endsWith("/logo.png"))
    val home=client.imageChoices(hit,"test-credential",AnimeArtworkTarget.LIBRARY_POSTER)
    assertEquals(1,home.size);assertTrue(home.single().url.endsWith("/title.jpg"))
    val detail=client.imageChoices(hit,"test-credential",AnimeArtworkTarget.DETAIL_POSTER)
    assertEquals(2,detail.size);assertTrue(detail.first().url.endsWith("/neutral.jpg"))
  }
  @Test fun legacyManualCoverOnlyOverridesDetailsAndHomeCanBeChangedIndependently() {
    val subject=AnimeSubject(1,"Example",cover="bangumi",artworkCover="auto-neutral",artworkPoster="auto-title",manualArtworkCover="user-neutral")
    assertEquals("user-neutral",subject.detailCover);assertEquals("auto-title",subject.libraryCover)
    val changed=subject.copy(manualArtworkPoster="user-title")
    assertEquals("user-neutral",changed.detailCover);assertEquals("user-title",changed.libraryCover)
    assertEquals("auto-title",changed.copy(manualArtworkPoster=null).libraryCover)
  }
  @Test fun tmdbIdLooksUpTheExplicitMediaTypeInsteadOfSearchingItsDigits() = runBlocking {
    val client=client(mapOf("movie/216074" to """{"id":216074,"title":"Expected movie","poster_path":"/poster.jpg"}"""))
    val hit=client.lookupImages(216074,"movie","test-credential")
    assertEquals("movie",hit.type);assertEquals("Expected movie",hit.title)
  }
}
