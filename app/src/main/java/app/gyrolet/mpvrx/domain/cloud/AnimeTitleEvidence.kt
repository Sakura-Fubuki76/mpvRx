package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import java.text.Normalizer

private val movieMarker = Regex("(?i)^(?:gekij(?:ou|o|ō)ban|劇場版|剧场版)\\s*|\\b(?:the\\s+)?movie\\b")
private val seasonMarker = Regex("(?i)\\s*(?:(?:season\\s*|\\bs)([0-9]+)|([0-9]+)(?:st|nd|rd|th)\\s+season|(first|second|third|fourth)\\s+season|第([一二三四五六七八九十0-9]+)[季期])\\s*$")
private fun ordinal(value: String): Int? = value.toIntOrNull() ?: mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10)[value.lowercase()]
fun animeExplicitSeason(raw: String): Int? = seasonMarker.find(Normalizer.normalize(raw, Normalizer.Form.NFKC))?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.let(::ordinal)
fun animeWithoutSeason(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC).replace(seasonMarker, "").trim()
fun animeIsMovie(raw: String): Boolean = movieMarker.containsMatchIn(raw)
fun animeSideStoryKey(raw: String): String = animeIdentityKey(raw.replace(Regex("(?i)\\bgaiden\\b|外伝|外传"), " "))

/** Formatting equivalence only: retain season numbers, !/!!, and OAD versus OVA identity. */
fun animeIdentityKey(raw: String, format: String = ""): String {
  val movie = animeIsMovie(raw) || format.equals("MOVIE", true) || format == "剧场版"
  val season = animeExplicitSeason(raw)
  var title = animeWithoutSeason(raw)
  title = title.replace(movieMarker, " ").trim(' ', '-', ':')
  if (movie) title = title.replace(Regex("^総集編\\s*"), "")
    .replace(Regex("【(?:前|後)編】(?=\\s*\\p{L})"), "")
  if (movie) title = title.replace(Regex("(?i)\\bpart\\s+(IV|III|II|I|[0-9]+)\\b")) {
    when (it.groupValues[1].uppercase()) { "I" -> "1"; "II" -> "2"; "III" -> "3"; "IV" -> "4"; else -> it.groupValues[1] }
  }
  if (season != null && season > 1) title += " $season"
  return animeNameKey(title) + if (movie) "|movie" else ""
}

fun animeDiscoveryTerms(raw: String): List<String> {
  val movieFree = raw.replace(movieMarker, " ").trim(' ', '-', ':')
  val base = animeWithoutSeason(movieFree)
  val special = raw.replace(Regex("(?i)\\s+SS$"), " Summer Special")
  val longVowel = raw.replace(Regex("(?i)\\boh(?=[a-z])"), "Oo")
  val shortMovieBase = if (animeIsMovie(raw)) base.substringBefore(' ').takeIf { it.length >= 4 }.orEmpty() else ""
  val numberedBase = base.replace(Regex("\\s+[0-9]+$"), "")
  val franchise = raw.split(Regex("\\s+[-:]\\s+"), limit = 2).first()
  return listOf(raw, special, movieFree, base, longVowel, numberedBase, franchise, base.split(' ').take(3).joinToString(" "), shortMovieBase)
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

/** Expand an abbreviation only when a provider explicitly associates it with one base title. */
fun animeFranchiseDiscoveryTerms(query: String, candidates: List<AnimeTitleCandidate>): List<String> {
  val prefix = query.split(Regex("\\s+[-:]\\s+"), limit = 2).first()
  if (prefix == query) return emptyList()
  val seeds = candidates.filter { candidate -> candidate.names.any { animeIdentityKey(it) == animeIdentityKey(prefix) } }
    .distinctBy { it.id }
  return seeds.singleOrNull()?.romaji?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
}

/** AniList is an identity bridge; do not choose by search order or popularity. */
fun animeNativeQueries(query: String, candidates: List<AnimeTitleCandidate>): List<String> {
  val variants = listOf(query, query.replace(Regex("(?i)\\s+SS$"), " Summer Special"))
  val keys = variants.map { animeIdentityKey(it) }.toSet()
  val season = animeExplicitSeason(query)
  val nativeBase = animeNameKey(animeWithoutSeason(query))
  val abbreviatedMovie = animeIsMovie(query) && Regex("(?i)\\bmovie$").containsMatchIn(query)
  fun bridgeNames(candidate: AnimeTitleCandidate): List<String> {
    val numbered = Regex("\\s+(S|[0-9]+)$").find(candidate.romaji)
    val suffix = if (':' in candidate.romaji) candidate.romaji.substringAfter(':').trim().takeIf { it.length >= 4 }
      else numbered?.groupValues?.get(1)
    if (suffix == null) return emptyList()
    val prefix = animeIdentityKey(if (':' in candidate.romaji) candidate.romaji.substringBefore(':')
      else candidate.romaji.substring(0, numbered!!.range.first))
    val franchiseAliases = candidates.filter { animeIdentityKey(it.romaji) == prefix }
      .singleOrNull()?.names.orEmpty().filter { it.isNotBlank() }
    return candidate.synonyms.filter { ':' in it }.map { "${it.substringBefore(':')}: $suffix" } +
      franchiseAliases.map { "$it $suffix" }
  }
  val matches = candidates.filter { candidate ->
    (candidate.names + bridgeNames(candidate)).any { it.isNotBlank() && animeIdentityKey(it, if (animeIsMovie(query)) candidate.format else "") in keys } ||
      (candidate.format == "MOVIE" && Regex("(?i)\\bmovie\\s+\\d+\\s+[A-Za-z]").containsMatchIn(query) &&
        candidate.names.any { name -> animeIdentityKey(query.replace(Regex("(?i)(\\bmovie)\\s+\\d+\\b"), "$1")) == animeIdentityKey(name, "MOVIE") }) ||
      (candidate.format in setOf("OVA", "SPECIAL") && animeSideStoryKey(query) != animeIdentityKey(query) &&
        candidate.names.any { animeSideStoryKey(it) == animeSideStoryKey(query) }) ||
      (abbreviatedMovie && candidate.format == "MOVIE" && candidate.names.any { animeMovieBase(it) == animeMovieBase(query) }) ||
      (season != null && season > 1 && query.any { it.code in 0x3040..0x30ff } && candidate.format == "TV" &&
        animeNameKey(candidate.native).startsWith(nativeBase) && listOf(candidate.romaji, candidate.english).any {
          animeExplicitSeason(it) == season || Regex("(?:^|\\s)${season}$").containsMatchIn(it)
        })
  }.distinctBy { it.id }
  // Releases use multiple spellings of Japanese long vowels (Ohmuro / Oomuro).
  // Use only explicit romaji evidence after exact matching, and retain subtitle/season identity.
  fun romanizedKey(name: String): String = animeIdentityKey(name)
    .replace("oh", "o").replace("oo", "o").replace("ou", "o").replace("uu", "u")
  val fallback = if (matches.isEmpty() && query.none { it.code in 0x3040..0x30ff || it.code in 0x4e00..0x9fff }) {
    candidates.filter { it.romaji.isNotBlank() && romanizedKey(query) == romanizedKey(it.romaji) }
      .distinctBy { it.id }
  } else emptyList()
  val candidate = matches.singleOrNull() ?: fallback.singleOrNull() ?: return emptyList()
  val japanese = candidate.names.filter { name -> name.any { it.code in 0x3040..0x30ff || it.code in 0x4e00..0x9fff } }.distinct()
  // A provider may combine multiple OVAs. Prefer the specific native alias with the same subtitle.
  val specific = japanese.filter { name ->
    val suffix = Regex("[A-Za-z][A-Za-z0-9]*(?:[ :_-]+[A-Za-z][A-Za-z0-9]*)*$").find(name)?.value ?: return@filter false
    val key = animeNameKey(suffix)
    key.length >= 4 && key !in setOf("ova", "ovas", "oad", "oads", "movie", "special") && animeNameKey(query).endsWith(key)
  }
  val queries = if (specific.isNotEmpty()) specific else listOf(candidate.native).filter { it.isNotBlank() }
  return queries.map { name ->
    if (animeIsMovie(query) && candidate.format == "MOVIE" && !animeIsMovie(name)) "劇場版 $name" else name
  }
}

fun animeMatchQuery(group: AnimeVideoGroup): String = "directory-v3 | " + group.queries.joinToString(" | ") + " | aliases-v9"

/** Progress counts actual matching work. Saved identities do not expire with a page refresh. */
fun animeNeedsMatching(savedQuery: String?, subjectId: Long?, manual: Boolean, attemptedAt: Long,
  currentQuery: String, now: Long, retryUnmatched: Boolean = false): Boolean {
  if (manual) return false
  if (savedQuery == null || savedQuery.removeSuffix(" | request-failed") != currentQuery) return true
  if (subjectId != null) return false
  if (retryUnmatched) return true
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
