package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.math.abs

/** Image association only; never changes a Bangumi subject or its episodes. */
@Serializable
data class AnimeArtworkBinding(
  val type: String = "", val id: Long = 0, val season: Int? = null,
  val episodes: List<Int> = emptyList(), val scope: String = "", val evidence: String = "",
)

internal data class TmdbBindingCandidate(val binding: AnimeArtworkBinding, val score: Int, val votes: Int = 0)
private fun dayDistance(a: String, b: String): Long? = try {
  abs(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)))
} catch (_: Exception) { null }
private fun JsonObject.number(key: String): Int = get(key)?.jsonPrimitive?.intOrNull ?: 0
private fun subjectNames(subject: AnimeSubject) = tmdbArtworkNames(subject).map(::tmdbTitleKey).filter { it.isNotBlank() }.toSet()
private fun detailNames(details: JsonObject): Set<String> =
  (listOf("name", "original_name", "title", "original_title").map { details.tmdbText(it) } +
    details["search_titles"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } +
    details["alternative_titles"]?.jsonObject?.let { it.tmdbRows("results") + it.tmdbRows("titles") }.orEmpty().map { it.tmdbText("title") })
    .map(::tmdbTitleKey).filter { it.isNotBlank() }.toSet()
private fun tmdbEpisodeTitle(raw: String): String {
  var title = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
    .replace(Regex("[（(][ぁ-ゖァ-ヺー]+[）)]"), "")
    .replace(Regex("(?i)(?:第\\s*\\d+\\s*[話话集]|\\bOVA\\s*\\d+|^\\d+杯目)\\s*"), "")
  val numerals = mapOf("壱" to "1", "壹" to "1", "一" to "1", "贰" to "2", "貳" to "2", "貮" to "2", "弐" to "2", "二" to "2", "参" to "3", "參" to "3", "叁" to "3", "三" to "3", "肆" to "4", "四" to "4", "伍" to "5", "五" to "5", "陸" to "6", "六" to "6")
  title = title.replace(Regex("其[ノの]?([壱壹一贰貳貮弐二参參叁三肆四伍五陸六])")) { "part" + numerals[it.groupValues[1]] }
  return title
}
private fun associationEpisodes(subject: AnimeSubject): List<AnimeEpisode> =
  subject.episodes.filter { it.type == 0 }.ifEmpty { subject.episodes }.filterNot { episode ->
    listOf(episode.title, episode.originalTitle).any { Regex("(?i)総集[編篇]|总集[编篇]|\\brecap\\b|\\brecapitulation\\b").containsMatchIn(it) }
  }
private fun episodeCount(subject: AnimeSubject): Int = associationEpisodes(subject).size

internal fun tmdbSeriesTitleMatches(subject: AnimeSubject, details: JsonObject): Boolean = subjectNames(subject).intersect(detailNames(details)).isNotEmpty()

internal fun tmdbMovieBinding(subject: AnimeSubject, movie: JsonObject): TmdbBindingCandidate? {
  if (subjectNames(subject).intersect(detailNames(movie)).isEmpty()) return null
  val distance = dayDistance(subject.date, movie.tmdbText("release_date"))
  // Video releases can follow cinema premieres; a large date gap needs stronger evidence.
  if (distance != null && distance > 730) return null
  if (episodeCount(subject) > 3 && subject.format == "TV") return null
  val score = 100 + when { distance == null -> 0; distance <= 14 -> 20; distance <= 370 -> 10; else -> 1 }
  return TmdbBindingCandidate(AnimeArtworkBinding("movie", movie["id"]!!.jsonPrimitive.long, scope = "work", evidence = "title+release"), score, movie.number("vote_count"))
}

internal fun tmdbWholeSeriesBinding(subject: AnimeSubject, series: JsonObject, seasons: List<JsonObject> = emptyList()): TmdbBindingCandidate? {
  if (subjectNames(subject).intersect(detailNames(series)).isEmpty()) return null
  val count = episodeCount(subject)
  if (count <= 0) return null
  val regular = seasons.filter { it.number("season_number") > 0 }.flatMap { it.tmdbRows("episodes") }
  val all = seasons.flatMap { it.tmdbRows("episodes") }
  val exactCount = series.number("number_of_episodes") == count
  val combinedSpecials = seasons.count { it.number("season_number") > 0 } == 1 && count > regular.size && count <= all.size &&
    associationEpisodes(subject).count { episode ->
      listOf(episode.title, episode.originalTitle).map { tmdbTitleKey(tmdbEpisodeTitle(it)) }.any { key -> all.any { tmdbTitleKey(tmdbEpisodeTitle(it.tmdbText("name"))) == key } }
    } >= 2
  if (!exactCount && !combinedSpecials) return null
  if ((dayDistance(subject.date, series.tmdbText("first_air_date")) ?: Long.MAX_VALUE) > 14) return null
  return TmdbBindingCandidate(AnimeArtworkBinding("tv", series["id"]!!.jsonPrimitive.long, scope = "work", evidence = "title+date+full-count"), 120, series.number("vote_count"))
}

internal fun tmdbSeasonBinding(subject: AnimeSubject, series: JsonObject, season: JsonObject, verifiedSeries: Boolean = false): TmdbBindingCandidate? {
  val episodes = season.tmdbRows("episodes").sortedBy { it.number("episode_number") }
  if (episodes.isEmpty()) return null
  val count = episodeCount(subject)
  val n = season.number("season_number")
  val names = subjectNames(subject)
  val seriesNames = detailNames(series)
  val subjectEpisodes = associationEpisodes(subject)
  val episodeNames = subjectEpisodes.flatMap { listOf(it.title, it.originalTitle) }.map { tmdbTitleKey(tmdbEpisodeTitle(it)) }.filter { it.length >= 4 }.toSet()
  // Remove a verified series prefix only for comparing episode-level titles.
  fun episodeKey(title: String): String {
    val key = tmdbTitleKey(tmdbEpisodeTitle(title))
    return (seriesNames + names).sortedByDescending { it.length }.firstOrNull { key.startsWith(it) && key.length > it.length + 3 }
      ?.let { key.removePrefix(it) } ?: key
  }
  val identityNames = (names + episodeNames).map(::episodeKey).filter { it.length >= 4 }.toSet()
  val segments = subjectEpisodes.flatMap { listOf(it.title, it.originalTitle) }.map { raw ->
    raw.split(Regex("[/／]")).map { episodeKey(it) }.filter { it.length >= 6 }.toSet()
  }.filter { it.size >= 2 }
  val named = episodes.filter { row ->
    val key = episodeKey(row.tmdbText("name"))
    key in identityNames || identityNames.any { it.length >= 8 && key.length >= 8 &&
      (key.endsWith(it) || it.endsWith(key)) && minOf(key.length, it.length).toDouble() / maxOf(key.length, it.length) >= .65 } ||
      segments.any { expected -> row.tmdbText("name").split(Regex("[/／]")).map { episodeKey(it) }.toSet().intersect(expected).size >= 2 }
  }
  val closestDate = episodes.mapNotNull { dayDistance(subject.date, it.tmdbText("air_date")) }.minOrNull()
  val dateStarts = episodes.filter { closestDate != null && closestDate <= 14 && dayDistance(subject.date, it.tmdbText("air_date")) == closestDate }
  val titleDirect = names.intersect(seriesNames).isNotEmpty()
  val titleSeason = names.contains(tmdbTitleKey(season.tmdbText("name")))
  val seasonDate = (dayDistance(subject.date, season.tmdbText("air_date")) ?: Long.MAX_VALUE) <= 14
  val regular = n > 0
  val fullSeason = regular && count > 0 && (episodes.size == count || (seasonDate && named.size >= 2 && abs(episodes.size - count) <= 2))
  if (fullSeason && (named.size >= minOf(2, count) || (seasonDate && (titleDirect || titleSeason || verifiedSeries)))) {
    return TmdbBindingCandidate(AnimeArtworkBinding("tv", series["id"]!!.jsonPrimitive.long, n,
      scope = if (titleDirect && series.number("number_of_episodes") == count) "work" else "season",
      evidence = if (named.isNotEmpty()) "episode-titles+count" else "season-date+count"),
      110 + (if (named.size >= minOf(2, count)) 20 else 0), series.number("vote_count"))
  }
  if (count <= 0) return null
  val dateAnchor = regular && (titleDirect || verifiedSeries) && dateStarts.size == 1 && count <= episodes.size
  val start = when {
    dateAnchor -> episodes.indexOf(dateStarts.single())
    named.size >= minOf(2, count) -> episodes.indexOf(named.first())
    !regular && count == 1 && named.size == 1 -> episodes.indexOf(named.single())
    else -> return null
  }
  val end = if (!dateAnchor && named.size >= 2) maxOf(start + count, episodes.indexOf(named.last()) + 1) else start + count
  val range = episodes.subList(start, end.coerceAtMost(episodes.size))
  if (abs(range.size - count) > 2) return null
  val matchedInRange = range.count { it in named }
  // A date by itself cannot identify a special, and a first-season subset is not a whole series.
  if (!regular && matchedInRange < minOf(2, count)) return null
  return TmdbBindingCandidate(AnimeArtworkBinding("tv", series["id"]!!.jsonPrimitive.long, n,
    range.map { it.number("episode_number") }, "episodes", if (named.isNotEmpty()) "episode-titles+range" else "episode-date+range"),
    110 + if (matchedInRange >= minOf(2, count)) 20 else 0, series.number("vote_count"))
}

internal fun selectTmdbBinding(candidates: List<TmdbBindingCandidate>): AnimeArtworkBinding? {
  val ranked = candidates.distinctBy { listOf(it.binding.type, it.binding.id, it.binding.season, it.binding.episodes) }.sortedWith(compareByDescending<TmdbBindingCandidate> { it.score }
    .thenByDescending { it.votes }.thenByDescending { when (it.binding.scope) { "work" -> 3; "season" -> 2; else -> 1 } })
  val best = ranked.firstOrNull() ?: return null
  val second = ranked.getOrNull(1) ?: return best.binding
  // Established versus empty duplicate records can be disambiguated after structural verification.
  return best.binding.takeIf { best.score > second.score || (best.votes >= 10 && second.votes == 0) ||
    (best.binding.id == second.binding.id && best.binding.type == second.binding.type && best.binding.scope in listOf("work", "season") && second.binding.scope == "episodes") }
}
