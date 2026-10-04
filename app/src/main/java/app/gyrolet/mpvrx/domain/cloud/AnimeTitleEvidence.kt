package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import java.text.Normalizer

private val movieMarker = Regex("(?i)^(?:gekijouban|劇場版|剧场版)\\s*|\\b(?:the\\s+)?movie(?:\\s+\\d+)?\\b")
private val seasonMarker = Regex("(?i)\\s*(?:season\\s*([0-9]+)|([0-9]+)(?:st|nd|rd|th)\\s+season|(first|second|third|fourth)\\s+season|第([一二三四五六七八九十0-9]+)[季期])\\s*$")
private fun ordinal(value: String): Int? = value.toIntOrNull() ?: mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10)[value.lowercase()]
fun animeExplicitSeason(raw: String): Int? = seasonMarker.find(Normalizer.normalize(raw, Normalizer.Form.NFKC))?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.let(::ordinal)
fun animeWithoutSeason(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC).replace(seasonMarker, "").trim()
fun animeIsMovie(raw: String): Boolean = movieMarker.containsMatchIn(raw)

/** Formatting equivalence only: retain season numbers, !/!!, and OAD versus OVA identity. */
fun animeIdentityKey(raw: String, format: String = ""): String {
  val movie = animeIsMovie(raw) || format.equals("MOVIE", true) || format == "剧场版"
  val season = animeExplicitSeason(raw)
  var title = animeWithoutSeason(raw)
  title = title.replace(movieMarker, " ").trim(' ', '-', ':')
  if (season != null && season > 1) title += " $season"
  return animeNameKey(title) + if (movie) "|movie" else ""
}

fun animeDiscoveryTerms(raw: String): List<String> {
  val movieFree = raw.replace(movieMarker, " ").trim(' ', '-', ':')
  val base = animeWithoutSeason(movieFree)
  val special = raw.replace(Regex("(?i)\\s+SS$"), " Summer Special")
  return listOf(raw, special, movieFree, base, base.split(' ').take(3).joinToString(" "))
    .filter { it.isNotBlank() }.distinct()
}

fun animeMovieBase(raw: String): String? {
  val marker = Regex("(?i)\\b(?:the\\s+)?movie\\b").find(raw) ?: return null
  return animeNameKey(raw.substring(0, marker.range.first))
}

@Serializable
data class AnimeTitleCandidate(val id: Long, val native: String, val romaji: String = "", val english: String = "",
  val synonyms: List<String> = emptyList(), val format: String = "", val episodes: Int = 0) {
  val names: List<String> get() = listOf(native, romaji, english) + synonyms
}

/** AniList is an identity bridge; do not choose by search order or popularity. */
fun animeNativeQueries(query: String, candidates: List<AnimeTitleCandidate>): List<String> {
  val variants = listOf(query, query.replace(Regex("(?i)\\s+SS$"), " Summer Special"))
  val keys = variants.map { animeIdentityKey(it) }.toSet()
  val season = animeExplicitSeason(query)
  val nativeBase = animeNameKey(animeWithoutSeason(query))
  val abbreviatedMovie = animeIsMovie(query) && Regex("(?i)\\bmovie$").containsMatchIn(query)
  val matches = candidates.filter { candidate ->
    candidate.names.any { it.isNotBlank() && animeIdentityKey(it, if (animeIsMovie(query)) candidate.format else "") in keys } ||
      (abbreviatedMovie && candidate.format == "MOVIE" && candidate.names.any { animeMovieBase(it) == animeMovieBase(query) }) ||
      (season != null && season > 1 && query.any { it.code in 0x3040..0x30ff } && candidate.format == "TV" &&
        animeNameKey(candidate.native).startsWith(nativeBase) && listOf(candidate.romaji, candidate.english).any {
          animeExplicitSeason(it) == season || Regex("(?:^|\\s)${season}$").containsMatchIn(it)
        })
  }.distinctBy { it.id }
  val candidate = matches.singleOrNull() ?: return emptyList()
  val japanese = candidate.names.filter { name -> name.any { it.code in 0x3040..0x30ff || it.code in 0x4e00..0x9fff } }.distinct()
  // A provider may combine multiple OVAs. Prefer the specific native alias with the same subtitle.
  val specific = japanese.filter { name ->
    val suffix = Regex("[A-Za-z][A-Za-z0-9]*(?:[ :_-]+[A-Za-z][A-Za-z0-9]*)*$").find(name)?.value ?: return@filter false
    val key = animeNameKey(suffix)
    key.length >= 4 && key !in setOf("ova", "ovas", "oad", "oads", "movie", "special") && animeNameKey(query).endsWith(key)
  }
  return if (specific.isNotEmpty()) specific else listOf(candidate.native).filter { it.isNotBlank() }
}

fun animeMatchQuery(group: AnimeVideoGroup): String = "directory-v3 | " + group.queries.joinToString(" | ") + " | aliases-v5"

/** Progress counts actual matching work. Saved identities do not expire with a page refresh. */
fun animeNeedsMatching(savedQuery: String?, subjectId: Long?, manual: Boolean, attemptedAt: Long,
  currentQuery: String, now: Long): Boolean {
  if (manual) return false
  if (savedQuery == null || savedQuery.removeSuffix(" | request-failed") != currentQuery) return true
  if (subjectId != null) return false
  val retryAfter = if (savedQuery.endsWith(" | request-failed")) 5 * 60_000L else 24 * 60 * 60_000L
  return now - attemptedAt >= retryAfter
}

@Serializable
data class AnimePartBinding(val parent: String, val number: Int, val title: String)

/** Explicit ONE/TWO (or roman/numeric) subjects only; never split by result ordering. */
fun animeNumberedSubjects(query: String, candidates: List<AnimeSubject>): Map<Int, AnimeSubject> {
  val ordinal = Regex("(?i)^(.*?)\\s+(ONE|TWO|THREE|FOUR|IV|III|II|I|[1-4])(?=[\\s~～〜:：—-]|$)")
  val rows = candidates.mapNotNull { subject ->
    val numbers = (listOf(subject.name, subject.chineseName) + subject.aliases).mapNotNull { name ->
      val match = ordinal.find(name) ?: return@mapNotNull null
      if (animeIdentityKey(match.groupValues[1]) != animeIdentityKey(query)) return@mapNotNull null
      when (match.groupValues[2].uppercase()) { "ONE", "I", "1" -> 1; "TWO", "II", "2" -> 2; "THREE", "III", "3" -> 3; else -> 4 }
    }.distinct()
    numbers.singleOrNull()?.let { it to subject }
  }.distinctBy { it.second.id }
  if (rows.size !in 2..4 || rows.map { it.first }.sorted() != (1..rows.size).toList()) return emptyMap()
  return rows.toMap().toSortedMap()
}

fun animePartBase(name: String): String? = Regex("(?i)^(.*?)\\s+(?:ONE|TWO|THREE|FOUR|IV|III|II|I|[1-4])(?=[\\s~～〜:：—-]|$)").find(name)?.groupValues?.get(1)
fun animePartRegularFiles(group: AnimeVideoGroup) = group.files.filter { animeSectionPath(group.directory, it).isEmpty() && !isAnimeExtraVideo(it) }

/** Numbered main videos select their subject; extras belong to the first subject. */
fun animeSplitGroups(groups: List<AnimeVideoGroup>, bindings: Map<String, AnimePartBinding>): List<AnimeVideoGroup> = groups.flatMap { group ->
  val parts = bindings.filterValues { it.parent == group.key }.entries.sortedBy { it.value.number }
  val regular = animePartRegularFiles(group)
  val extras = group.files.filterNot { it in regular }
  val byNumber = regular.groupBy { parseAnimeFilename(it.name).episode?.takeIf { number -> number % 1.0 == 0.0 }?.toInt() }
  if (parts.size < 2 || parts.map { it.value.number }.distinct().size != parts.size || byNumber.keys != parts.map { it.value.number }.toSet()) listOf(group)
  else parts.mapIndexed { index, (key, binding) -> group.copy(key = key, query = binding.title, files = byNumber.getValue(binding.number) + if (index == 0) extras else emptyList(), queries = listOf(binding.title)) }
}

/** Bangumi can number the single episode in TWO as 2, rather than 1. */
fun animePartEpisodeOffset(number: Int, subject: AnimeSubject): Int? {
  val source = subject.episodes.singleOrNull { it.type == 0 }?.number ?: return null
  return if (source % 1.0 == 0.0) number - source.toInt() else null
}

/** History is loaded with the snapshot, so the UI never guesses the first card before recency. */
fun animeFeaturedGroup(groups: List<AnimeVideoGroup>, recentPaths: List<String>): String? {
  val owners = groups.flatMap { group -> group.files.map { it.path to group.key } }.toMap()
  return recentPaths.firstNotNullOfOrNull { owners[it] }
}
