package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TmdbArtworkClientTest {
  private fun row(value: String) = Json.parseToJsonElement(value).jsonObject
  @Test fun detailCoverUsesNeutralPosterThenBangumiThenNeutralBackdrop() {
    val subject = AnimeSubject(1, "Test", cover = "bangumi", artworkCover = "poster", artworkBackdrop = "backdrop")
    assertEquals("poster", subject.detailCover)
    assertEquals("bangumi", subject.copy(artworkCover = "").detailCover)
    assertEquals("backdrop", subject.copy(artworkCover = "", cover = "").detailCover)
    val images = row("""{"posters":[{"file_path":"/zh.jpg","iso_639_1":"zh","vote_count":100},{"file_path":"/neutral.jpg","iso_639_1":null,"vote_count":1}],"backdrops":[{"file_path":"/backdrop.jpg","iso_639_1":null}]}""")
    assertTrue(selectTmdbTextlessImage(images, "posters").endsWith("/neutral.jpg"))
    assertTrue(selectTmdbTextlessImage(images, "backdrops").endsWith("/backdrop.jpg"))
    assertEquals("", selectTmdbTextlessImage(row("""{"posters":[{"file_path":"/zh.jpg","iso_639_1":"zh"}]}"""), "posters"))
  }
  @Test fun artworkRejectsUnrelatedAndLiveActionTitles() {
    val subject = AnimeSubject(1, "SHIROBAKO", "白箱", date = "2014-10-09")
    assertNull(selectTmdbArtworkSubject(subject, listOf(row("""{"id":1,"name":"Black Box","genre_ids":[16]}"""))))
    assertNull(selectTmdbArtworkSubject(subject, listOf(row("""{"id":1,"name":"白箱","genre_ids":[18]}"""))))
  }
  @Test fun exactYearDisambiguatesIdenticalTitles() {
    val subject = AnimeSubject(1, "SHIROBAKO", date = "2014-10-09")
    val older = row("""{"id":1,"name":"SHIROBAKO","first_air_date":"2010-01-01","genre_ids":[16]}""")
    val correct = row("""{"id":2,"name":"SHIROBAKO","first_air_date":"2014-10-09","genre_ids":[16]}""")
    assertEquals(correct, selectTmdbArtworkSubject(subject, listOf(older, correct)))
    assertNull(selectTmdbArtworkSubject(subject, listOf(older, older.toMutableMap().apply { put("id", JsonPrimitive(3)) }.let(::JsonObject))))
  }
  @Test fun seasonNamesCanUseSeriesArtworkWithoutChangingSubject() {
    val subject = AnimeSubject(2, "響け！ユーフォニアム", "吹响吧！上低音号 第二季")
    val series = row("""{"id":1,"original_name":"響け！ユーフォニアム","genre_ids":[16]}""")
    assertEquals(series, selectTmdbArtworkSubject(subject, listOf(series)))
    assertEquals(2L, subject.id)
  }
  @Test fun logosPreferChineseThenJapaneseAndRejectUnsupportedSvg() {
    val en = row("""{"file_path":"/en.png","iso_639_1":"en","vote_average":9}""")
    val ja = row("""{"file_path":"/ja.png","iso_639_1":"ja","vote_average":3}""")
    val zh = row("""{"file_path":"/zh.png","iso_639_1":"zh","vote_average":2}""")
    val svg = row("""{"file_path":"/zh.svg","iso_639_1":"zh","vote_average":10}""")
    assertEquals("/zh.png", selectTmdbLogo(listOf(en, ja, zh, svg)))
    assertEquals("/ja.png", selectTmdbLogo(listOf(en, ja, svg)))
    assertEquals("", selectTmdbLogo(listOf(svg)))
  }
}
