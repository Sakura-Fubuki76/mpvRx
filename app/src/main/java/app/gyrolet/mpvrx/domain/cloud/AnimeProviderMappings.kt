package app.gyrolet.mpvrx.domain.cloud

/** Events are a separate provider type; CDs and games must never supply video metadata. */
fun animeEventTerms(query: String): List<String> {
  val marker = Regex("(?i)\\blive\\s+event\\b").find(query)
  val event = marker?.let { query.substring(it.range.last + 1).trim(' ', '~', '～', '〜') }
    ?: query.takeIf { Regex("(?i)\\btea\\s+party\\s+\\d{4}\\b").containsMatchIn(it) }
    ?: return emptyList()
  // Common English/Japanese phrase spelling, independent of any work or provider id.
  val translated = if (animeNameKey(event).removeSuffix("!") == "letsgo") "レッツゴー！" else event
  return listOf(event, translated).distinct().filter { it.length >= 4 }
}

fun matchAnimeEvent(query: String, candidates: List<AnimeSubject>): AnimeSubject? {
  val keys = animeEventTerms(query).map(::animeNameKey)
  return candidates.filter { subject -> subject.subjectType == 6 &&
    (listOf(subject.name, subject.chineseName) + subject.aliases).any { name ->
      val key = animeNameKey(name)
      keys.any { key == it || key.endsWith(it) }
    }
  }.distinctBy { it.id }.singleOrNull()
}

/** A compound title explicitly records the chapter order used by this provider. */
fun animeCompoundChapters(name: String): List<String> {
  val normalized = name.replace('－', '-').replace('─', '-')
  val match = Regex("^(.*?)\\s+(-[^/]+-(?:/-[^/]+-)+)$").matchEntire(normalized) ?: return emptyList()
  return match.groupValues[2].split('/').map { "${match.groupValues[1]} $it" }
}

fun matchAnimeChapter(query: String, candidates: List<AnimeSubject>): AnimeSubject? = candidates.filter {
  it.subjectType == 2 && it.format == "TV" && animeCompoundChapters(it.name).any { name -> animeIdentityKey(name) == animeIdentityKey(query) }
}.distinctBy { it.id }.singleOrNull()

private fun localAnimeNumbers(group: AnimeVideoGroup): List<Int>? {
  val values = animePartRegularFiles(group).map { parseAnimeFilename(it.name).episode ?: return null }.filter { it > 0 }
  if (values.isEmpty() || values.any { it % 1 != 0.0 }) return null
  val numbers = values.map { it.toInt() }.distinct().sorted()
  return numbers.takeIf { it == (1..it.size).toList() }
}

fun animeChapterEpisodeOffset(query: String, group: AnimeVideoGroup, subject: AnimeSubject): Int? {
  val chapters = animeCompoundChapters(subject.name)
  val index = chapters.indexOfFirst { animeIdentityKey(it) == animeIdentityKey(query) }
  if (index < 0) return null
  val numbers = localAnimeNumbers(group) ?: return null
  val episodes = subject.episodes.filter { it.type == 0 }.map { it.number }.sorted()
  if (episodes != (1..episodes.size).map { it.toDouble() } || numbers.size * chapters.size != episodes.size) return null
  return -index * numbers.size
}

/** Original episode titles identify the adapted source, including provider-numbered recap slots. */
fun animeSourceEpisodeOffset(sourceTitle: String, group: AnimeVideoGroup, subject: AnimeSubject): Int? {
  val numbers = localAnimeNumbers(group) ?: return null
  val episodes = subject.episodes.filter { it.type == 0 }.sortedBy { it.number }
  val key = animeNameKey(sourceTitle)
  val matched = episodes.filter { it.originalTitle.isNotBlank() && animeNameKey(it.originalTitle).startsWith(key) }
  if (key.length < 3 || matched.isEmpty()) return null
  val first = matched.first().number
  if (first % 1 != 0.0 || matched.map { it.number } != (first.toInt() until first.toInt() + matched.size).map { it.toDouble() }) return null
  val tail = episodes.filter { it.number >= first + matched.size && it.number < first + numbers.size }
  if (matched.size + tail.size != numbers.size || tail.any { !it.originalTitle.contains("総集編") }) return null
  return 1 - first.toInt()
}

fun animeMappedEpisode(subject: AnimeSubject, filename: String, offset: Int, regular: Boolean): AnimeEpisode? {
  val parsed = parseAnimeFilename(filename)
  val local = parsed.episode ?: return null
  if (regular && local == 0.0 && offset < 0 && animeCompoundChapters(subject.name).isNotEmpty()) {
    return subject.episodes.filter { it.type == 1 && it.number > -offset && it.number < 1 - offset }.singleOrNull()
  }
  return subject.episodes.firstOrNull { it.number == local - offset && it.type == parsed.episodeType && (regular || it.type != 0) }
}

/** Provider identity controls cards; physical owners still control each file's episode number. */
fun animeMergeSubjectGroups(groups: List<AnimeVideoGroup>, subjects: Map<String, Long?>,
  offsets: Map<String, Int>): List<AnimeVideoGroup> = groups.groupBy { group ->
    subjects[group.key]?.let { "subject:$it" } ?: "path:${group.key}"
  }.values.map { members ->
    val anchor = members.minBy { it.key }
    val segments = members.map { it.directory.split('/').filter(String::isNotBlank) }
    val common = segments.first().takeWhileIndexed { index, segment -> segments.all { it.getOrNull(index) == segment } }
    anchor.copy(directory = if (members.size == 1) anchor.directory else "/" + common.joinToString("/"),
      files = members.flatMap { it.files }.distinctBy { it.path }, queries = members.flatMap { it.queries }.distinct(),
      sourceKeys = members.map { it.key }.toSet(),
      episodeOffsets = members.flatMap { group -> group.files.map { it.path to (offsets[group.key] ?: 0) } }.toMap(),
      regularPaths = members.flatMap { group -> group.files.filter { animeSectionPath(group.directory, it).isEmpty() && !isAnimeExtraVideo(it) }.map { it.path } }.toSet())
  }

private inline fun <T> List<T>.takeWhileIndexed(predicate: (Int, T) -> Boolean): List<T> {
  val result = mutableListOf<T>()
  forEachIndexed { index, value -> if (!predicate(index, value)) return result else result.add(value) }
  return result
}
