package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
data class AnimeSubject(
  val id: Long, val name: String, val chineseName: String = "", val summary: String = "",
  val cover: String = "", val date: String = "", val score: Double = 0.0,
  val tags: List<String> = emptyList(), val episodes: List<AnimeEpisode> = emptyList(),
  val cast: List<AnimeCast> = emptyList(), val staff: List<AnimePerson> = emptyList(),
  val creditsFetchedAt: Long = 0,
  val aliases: List<String> = emptyList(), val format: String = "",
  val subjectType: Int = 2, val episodeSchemaVersion: Int = 0,
  val titleLogo: String = "", val tmdbId: Long = 0,
  val artworkFetchedAt: Long = 0,
  val artworkCover: String = "", val artworkBackdrop: String = "", val artworkSchemaVersion: Int = 0,
  val artworkPoster: String = "",
  val artworkBinding: AnimeArtworkBinding? = null,
  val manualArtworkCover: String? = null, val manualArtworkPoster: String? = null, val manualArtworkLogo: String? = null,
) {
  val displayLogo: String get() = manualArtworkLogo ?: titleLogo
  val title: String get() = chineseName.ifBlank { name }
  val detailCover: String get() = manualArtworkCover ?: artworkCover.ifBlank { cover }.ifBlank { artworkBackdrop }
  val libraryCover: String get() = manualArtworkPoster ?: artworkPoster.ifBlank { cover }
}

@Serializable
data class AnimeEpisode(val id: Long, val number: Double, val type: Int, val title: String, val originalTitle: String = "")

@Serializable
data class AnimePerson(val id: Long, val name: String, val image: String = "", val role: String = "")

@Serializable
data class AnimeCast(val id: Long, val name: String, val image: String = "", val role: String = "", val actors: List<AnimePerson> = emptyList())

data class AnimeFilename(val title: String, val episode: Double?, val special: Boolean, val episodeType: Int = if (special) 1 else 0)

private val animeFilenameCache = object : LinkedHashMap<String, AnimeFilename>(128, .75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimeFilename>?) = size > 8192
}

fun parseAnimeFilename(raw: String): AnimeFilename {
  synchronized(animeFilenameCache) { animeFilenameCache[raw] }?.let { return it }
  return parseAnimeFilenameUncached(raw).also { synchronized(animeFilenameCache) { animeFilenameCache[raw] = it } }
}

private val animeSpecialPattern = Regex("(?i)(?:^|[\\s_\\-\\[\\(])(SP|OVA|OAD|NCOP|NCED|OP|ED)(?:[\\s_\\-\\]\\)]|\\d|$)")
private fun animeSpecialTag(name: String): String? = animeSpecialPattern.find(Normalizer.normalize(name, Normalizer.Form.NFKC))?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT)

/** Keep season markers in the title: removing them would silently merge separate Bangumi subjects. */
private fun parseAnimeFilenameUncached(raw: String): AnimeFilename {
  val parser by lazy {
    com.github.TraceLTRC.AnitomyK().also { runCatching { it.parse(Normalizer.normalize(raw, Normalizer.Form.NFKC)) } }
  }
  fun element(category: com.github.TraceLTRC.ElementCategory) = parser.elements.firstOrNull { it.first == category }?.second.orEmpty()
  var name = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    .replace(Regex("(?i)\\.(mkv|mp4|mov|avi|webm|m4v|ts)$"), "")
  val specialTag = animeSpecialTag(name)
  val special = specialTag != null
  val numberedMovie = Regex("(?i)\\bmovie\\s+\\d+\\s+[A-Za-z]").containsMatchIn(name)
  val episodeMatch = if (numberedMovie) null else Regex("(?i)S\\d{1,2}E(\\d{1,3}(?:\\.\\d+)?)").find(name)
    ?: Regex("(?i)(?:^|[\\s_\\-\\[\\(])(?:EP?|SP|OVA|OAD|NCOP|NCED|OP|ED)[ ._-]*(\\d{1,3}(?:\\.\\d+)?)").find(name)
    ?: Regex("第\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*[话話集]").find(name)
    ?: Regex("\\[(\\d{1,3}(?:\\.\\d+)?)(?:v\\d+)?\\]", RegexOption.IGNORE_CASE).find(name)
    ?: Regex("(?i)\\s-\\s*(\\d{1,3}(?:\\.\\d+)?)(?:v\\d+)?(?=[\\s\\[\\]_()-]|$)").find(name)
    ?: Regex("(?:[ -]+|\\[)(\\d{1,3}(?:\\.\\d+)?)(?:v\\d+)?(?=[\\s\\[\\]_()-]|$)").find(name)
  val episode = episodeMatch?.groupValues?.get(1)?.toDoubleOrNull()
  if (episodeMatch != null) {
    val prefix = name.substring(0, episodeMatch.range.first)
    // Preserve S02 from S02E01 as a season discriminator.
    val season = Regex("(?i)^S(\\d{1,2})E").find(episodeMatch.value)?.groupValues?.get(1)
    name = prefix + if (season != null && season.toInt() > 1) " Season ${season.toInt()}" else ""
  }
  name = name.replace(Regex("\\[[^]]*]|\\([^)]*(?:1080|720|2160|HEVC|AVC|FLAC|BDRip)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("(?i)\\b(?:BDRip|BluRay|WEB[- ]?DL|WEBRip|HEVC|AVC|FLAC|AAC|10bit|10-bit|x264|x265|H[.]?26[45]|\\d{3,4}[pi])\\b"), " ")
    .replace('_', ' ').replace(Regex("\\s+"), " ").trim(' ', '-', '.', '(', ')')
  val originalTitle = if (episode != null || numberedMovie) "" else element(com.github.TraceLTRC.ElementCategory.kElementAnimeTitle)
  val originalEpisode = if (episode != null || numberedMovie) null else element(com.github.TraceLTRC.ElementCategory.kElementEpisodeNumber).toDoubleOrNull()
  val explicitSeasonOrChinese = Regex("(?i)S\\d{1,2}E\\d|第\\s*\\d+[话話集]|Season\\s*\\d|\\d+(?:nd|rd|th)\\s+Season|(?:Second|Third|Fourth|Final)\\s+Season|\\d+(?:nd|rd|th)(?:\\s|$)").containsMatchIn(raw)
  val type = when (specialTag) {
    "NCOP", "OP" -> 2
    "NCED", "ED" -> 3
    null -> 0
    else -> 1
  }
  return AnimeFilename(if (explicitSeasonOrChinese) name else originalTitle.ifBlank { name },
    episode ?: originalEpisode, special, type)
}

private fun latinWithoutDiacritics(name: String): String = buildString {
  name.forEach { char ->
    if (char.code > 127 && Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN) {
      append(Normalizer.normalize(char.toString(), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() })
    } else append(char)
  }
}

fun animeNameKey(name: String): String = latinWithoutDiacritics(Normalizer.normalize(name, Normalizer.Form.NFKC))
  .lowercase(java.util.Locale.ROOT)
  .replace(Regex("\\s+(iii|ii|iv)$")) { " " + when (it.groupValues[1]) { "ii" -> "2"; "iii" -> "3"; else -> "4" } }
  .replace(Regex("\\b(oad|ova)s\\b"), "$1")
  .replace(Regex("second\\s+season|2nd\\s+season"), "season2")
  .replace(Regex("third\\s+season|3rd\\s+season"), "season3")
  .replace(Regex("fourth\\s+season|4th\\s+season"), "season4")
  .replace(Regex("(?i)\\bha\\b"), "wa")
  .map { if (it.code in 0x3041..0x3096) (it.code + 0x60).toChar() else it }.joinToString("")
  .filter { it.isLetterOrDigit() || it in "!?+♪" }

/** Ambiguous search results require confirmation; popularity is never an identity signal. */
fun matchAnimeSubject(query: String, candidates: List<AnimeSubject>): AnimeSubject? {
  val key = animeIdentityKey(query)
  if (key.length < 2) return null
  val shortMovie = animeIsMovie(query) && Regex("(?i)\\bmovie$").containsMatchIn(query)
  val exact = candidates.filter { subject ->
    (listOf(subject.name, subject.chineseName) + subject.aliases).any { it.isNotBlank() && animeIdentityKey(it, if (animeIsMovie(query)) subject.format else "") == key } ||
      (shortMovie && subject.format == "剧场版" && (listOf(subject.name) + subject.aliases).any { animeMovieBase(it) == animeMovieBase(query) })
  }.distinctBy { it.id }
  if (exact.isNotEmpty()) return exact.singleOrNull()
  if (animeSideStoryKey(query).length < 2) return null
  return candidates.filter { subject -> subject.format in setOf("OVA", "其他", "SPECIAL") &&
    (listOf(subject.name, subject.chineseName) + subject.aliases).any {
      animeSideStoryKey(it) == animeSideStoryKey(query)
    }
  }.distinctBy { it.id }.singleOrNull()
}

private val extraDirectoryNames = setOf("sp", "sps", "special", "specials", "extra", "extras", "bonus", "ncop", "nced", "op", "ed", "pv", "cm", "menu", "menus", "previews", "creditless", "cds", "ova", "次回予告", "特典", "映像特典")

fun isAnimeExtraVideo(file: app.gyrolet.mpvrx.domain.network.NetworkFile): Boolean =
  file.path.substringBeforeLast('/').split('/').any { it.lowercase(java.util.Locale.ROOT) in extraDirectoryNames } || animeSpecialTag(file.name) != null

private fun animeSeason(name: String): Int? {
  Regex("(?:^|\\s)([2-9])$").find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
  val key = animeNameKey(name)
  return Regex("(?:season|第)([2-9])[季期]?").find(key)?.groupValues?.get(1)?.toIntOrNull()
    ?: Regex("([2-9])(?:nd|rd|th)").find(key)?.groupValues?.get(1)?.toIntOrNull()
    ?: Regex("\\b(?:Second|Third|Fourth)\\s+Season", RegexOption.IGNORE_CASE).find(name)?.value?.let {
      when (it.substringBefore(' ').lowercase()) { "second" -> 2; "third" -> 3; else -> 4 }
    }
}

data class AnimeVideoGroup(val key: String, val directory: String, val query: String,
  val files: List<app.gyrolet.mpvrx.domain.network.NetworkFile>, val queries: List<String> = listOf(query),
  val sourceKeys: Set<String> = emptySet(), val episodeOffsets: Map<String, Int> = emptyMap(), val regularPaths: Set<String> = emptySet())

/** Directory titles preserve trailing sequel numbers; they are not episode numbers. */
fun animeDirectoryTitle(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
  .replace(Regex("\\[[^]]*]"), " ")
  .replace(Regex("(?i)\\([^)]*(?:1080|720|2160|1920|HEVC|AVC|FLAC|BDRip)[^)]*\\)"), " ")
  .replace(Regex("(?i)\\b(?:BDRip|BD-BOX|BluRay|WEB[- ]?DL|WEBRip|HEVC|AVC|FLAC|AAC|10bit|x264|x265|\\d{3,4}[pi])\\b"), " ")
  .replace(Regex("(?i)\\s*[-+]\\s*(?:TV|SP|OVA)(?:\\s*[-+]\\s*(?:TV|SP|OVA))*$"), "")
  .replace('_', ' ').replace(Regex("\\s+"), " ").trim(' ', '-', '.')
  .replace(Regex("^\\d{1,2}\\s*[-.]\\s+"), "")

fun animeSectionPath(directory: String, file: app.gyrolet.mpvrx.domain.network.NetworkFile): String {
  val parent = file.path.substringBeforeLast('/').ifBlank { "/" }
  return if (parent == directory) "" else parent.removePrefix(directory.trimEnd('/') + "/")
}

private val animeSeasonDirectory = Regex("(?i)^(?:S|Season[ _-]*)(0?[1-9][0-9]?)$")

/** Release folders own their descendants. Preserve real section paths instead of guessing types. */
fun animeVideoGroups(files: List<app.gyrolet.mpvrx.domain.network.NetworkFile>): List<AnimeVideoGroup> {
  val videos = files.distinctBy { app.gyrolet.mpvrx.domain.network.NetworkPath.from(it.path).value }.filter {
    !it.isDirectory && (it.mimeType?.startsWith("video/") == true ||
      cloudMediaExtension(it.name) in app.gyrolet.mpvrx.utils.storage.FileTypeUtils.VIDEO_EXTENSIONS)
  }
  val releaseTag = Regex("(?i)Ma\\d+p_|Hi\\d+p_|\\d{3,4}[pi]|\\d{3,4}x\\d{3,4}")
  val parentPaths = videos.filterNot(::isAnimeExtraVideo).map { it.path.substringBeforeLast('/') }.toSet()
  val genericDirectory = Regex("(?i)^(anime|movies?|videos?|BD|BDMV|动画|电影)$")
  val menuDirectory = Regex("(?i)^Menu(?: \\(.*\\))?$")
  val workParents = parentPaths.filterNot { genericDirectory.matches(animeDirectoryTitle(it.substringAfterLast('/'))) }.toSet()
  val grouped = videos.groupBy { file ->
    val parent = file.path.substringBeforeLast('/').ifBlank { "/" }
    val segments = parent.split('/').filter { it.isNotBlank() }
    val extra = segments.indexOfFirst { it.lowercase(java.util.Locale.ROOT) in extraDirectoryNames ||
      menuDirectory.matches(it) }
    val release = segments.take(if (extra >= 0) extra else segments.size).indexOfLast { releaseTag.containsMatchIn(it) }
    val enclosing = (1..segments.size).map { "/" + segments.take(it).joinToString("/") }
      .firstOrNull { candidate ->
        candidate in workParents && segments.drop(candidate.split('/').count { it.isNotBlank() })
          .none { animeSeasonDirectory.matches(it) }
      }
    when {
      enclosing != null -> enclosing
      release >= 0 -> "/" + segments.take(release + 1).joinToString("/")
      extra > 0 -> "/" + segments.take(extra).joinToString("/")
      else -> (1..segments.size).map { "/" + segments.take(it).joinToString("/") }
        .lastOrNull { it in parentPaths } ?: parent
    }
  }
  val ancestorUsage = grouped.keys.flatMap { candidate ->
    candidate.split('/').dropLast(1).map(::animeDirectoryTitle).distinct()
  }.groupingBy { it }.eachCount()
  return grouped.flatMap { (directory, rows) ->
    val folderTitle = animeDirectoryTitle(directory.substringAfterLast('/'))
    val generic = directory == "/" || Regex("(?i)^(anime|movies?|videos?|BD|BDMV|动画|电影|\\d{4})$").matches(folderTitle)
    // Repeated episodic filename identities can disprove a container's apparent work title.
    // Only inspect direct regular episodes; nested special releases retain their owner.
    val directTitles = if (generic) emptyMap() else rows.filter {
      it.path.substringBeforeLast('/') == directory && !isAnimeExtraVideo(it)
    }.map { parseAnimeFilename(it.name) }.filter { it.episode != null && it.title.isNotBlank() }
      .groupBy { animeNameKey(it.title) }
    val mixed = generic || (directTitles.size > 1 && directTitles.values.all { episodes -> episodes.map { it.episode }.distinct().size >= 2 } &&
      animeNameKey(folderTitle) !in directTitles && !releaseTag.containsMatchIn(directory.substringAfterLast('/')))
    val titles = if (mixed) rows.groupBy { parseAnimeFilename(it.name).title } else emptyMap()
    val seasonFolder = animeSeasonDirectory.matchEntire(folderTitle)
    val films = rows.filter { it.path.substringBeforeLast('/') == directory && !isAnimeExtraVideo(it) }
      .map { parseAnimeFilename(it.name).title }
      .filter { Regex("(?i)\\bmovie\\s+\\d+\\s+[A-Za-z]").containsMatchIn(it) &&
        animeMovieBase(it) == animeMovieBase(folderTitle) }.distinctBy { animeIdentityKey(it) }
    if (animeIsMovie(folderTitle) && films.size >= 2) {
      val ordinary = rows.filterNot(::isAnimeExtraVideo).groupBy { parseAnimeFilename(it.name).title }
      val ordered = ordinary.keys.sortedWith(compareBy<String> { if (it in films) 0 else 1 }.thenBy { it })
      val extras = rows.filter(::isAnimeExtraVideo).groupBy { extra ->
        val key = animeIdentityKey(parseAnimeFilename(extra.name).title)
        ordered.singleOrNull { animeIdentityKey(it) == key } ?: ordered.first()
      }
      return@flatMap ordered.map { filenameTitle ->
        val members = ordinary.getValue(filenameTitle) + extras[filenameTitle].orEmpty()
        AnimeVideoGroup("$directory#anime:${animeIdentityKey(filenameTitle)}", directory, filenameTitle, members, listOf(filenameTitle))
      }
    }
    val title = if (seasonFolder != null) animeDirectoryTitle(directory.substringBeforeLast('/').substringAfterLast('/')) + " Season " + seasonFolder.groupValues[1].toInt()
      else if (generic) titles.keys.singleOrNull().orEmpty() else folderTitle
    val ancestors = directory.split('/').filter { it.isNotBlank() }.dropLast(1).asReversed().map(::animeDirectoryTitle)
    val season = animeSeason(title)
    val safeAncestors = ancestors.filter { ancestorUsage[it] == 1 }
    val fileAliases = if (seasonFolder == null) emptyList() else rows.filter {
      it.path.substringBeforeLast('/') == directory && !isAnimeExtraVideo(it)
    }.map { parseAnimeFilename(it.name) }.filter { it.episode != null && it.title.isNotBlank() }
      .groupBy { animeIdentityKey(it.title) }.values.filter { it.map { row -> row.episode }.distinct().size >= 2 }
      .map { it.first().title }
    val queries = (listOf(title) + safeAncestors + fileAliases).filter { it.isNotBlank() && (season == null || animeSeason(it) == season || animeExplicitSeason(it) == season) }.distinct()
    if (mixed && titles.size > 1) {
      // In a mixed folder, a filename is evidence of identity; the folder is only a location.
      // Keep unidentifiable files together rather than attaching them to an arbitrary show.
      rows.groupBy { animeNameKey(parseAnimeFilename(it.name).title) }.map { (key, members) ->
        val filenameTitle = members.map { parseAnimeFilename(it.name).title }.minOrNull().orEmpty()
        AnimeVideoGroup("$directory#anime:$key", directory, filenameTitle, members,
          listOf(filenameTitle).filter { it.isNotBlank() })
      }
    } else listOf(AnimeVideoGroup(directory, directory, title.ifBlank { folderTitle }, rows, queries))
  }
}
