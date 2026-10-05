package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Public API snapshots: protect cross-provider structure independently of network availability. */
class TmdbArtworkStructuralReplayTest {
  @Test fun verifiedMoviesSeasonsAndSpecialsRetainTheirExactAssociation() {
    val json=Json { ignoreUnknownKeys=true }
    val cases=javaClass.getResourceAsStream("/tmdb-structural-cases.json")!!.bufferedReader().use { json.parseToJsonElement(it.readText()).jsonArray }
    val failures=mutableListOf<String>()
    for (value in cases) {
      val case=value.jsonObject
      val subject=json.decodeFromJsonElement<AnimeSubject>(case["subject"]!!)
      val candidates=case["records"]!!.jsonArray.flatMap { record ->
        val row=record.jsonObject;val details=row["details"]!!.jsonObject
        if(row["type"]!!.jsonPrimitive.content=="movie") listOfNotNull(tmdbMovieBinding(subject,details))
        else row["seasons"]!!.jsonArray.mapNotNull { tmdbSeasonBinding(subject,details,it.jsonObject,verifiedSeries=true) }
      }
      val result=selectTmdbBinding(candidates)
      val expectedId=case["expectedId"]!!.jsonPrimitive.long
      val expectedSeason=case["expectedSeason"]!!.jsonPrimitive.intOrNull
      val expectedEpisodes=case["expectedEpisodes"]!!.jsonArray.map { it.jsonPrimitive.int }
      if(result==null || result.id!=expectedId || result.type!=case["expectedType"]!!.jsonPrimitive.content || result.season!=expectedSeason || result.episodes!=expectedEpisodes)
        failures += "${subject.id} ${subject.title}: expected $expectedId S$expectedSeason $expectedEpisodes; got $result"
    }
    assertTrue(failures.joinToString("\n"),failures.isEmpty())
  }
  @Test fun establishedSeriesSurviveLocalizedTitlesRecapsAndCombinedSpecials() {
    val json=Json { ignoreUnknownKeys=true }
    val cases=javaClass.getResourceAsStream("/tmdb-existing-match-cases.json")!!.bufferedReader().use { json.parseToJsonElement(it.readText()).jsonArray }
    val failures=mutableListOf<String>()
    for (value in cases) {
      val case=value.jsonObject;val subject=json.decodeFromJsonElement<AnimeSubject>(case["subject"]!!)
      val details=case["details"]!!.jsonObject;val seasons=case["seasons"]!!.jsonArray.map { it.jsonObject }
      val found=seasons.mapNotNull { tmdbSeasonBinding(subject,details,it,tmdbSeriesTitleMatches(subject,details)) }.toMutableList()
      if(found.isEmpty()) tmdbWholeSeriesBinding(subject,details,seasons)?.let(found::add)
      val result=selectTmdbBinding(found)
      if(result?.id!=details["id"]!!.jsonPrimitive.long) failures += "${subject.id} ${subject.title}: $result"
    }
    assertTrue(failures.joinToString("\n"),failures.isEmpty())
  }

}
