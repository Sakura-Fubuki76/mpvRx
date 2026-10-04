package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
data class AnimeSubject(
  val id: Long, val name: String, val chineseName: String = "", val summary: String = "",
  val cover: String = "", val date: String = "", val score: Double = 0.0,
  val tags: List<String> = emptyList(), val episodes: List<AnimeEpisode> = emptyList(),
) {
  val title: String get() = chineseName.ifBlank { name }
}

@Serializable
data class AnimeEpisode(val id: Long, val number: Double, val type: Int, val title: String)

data class AnimeFilename(val title: String, val episode: Double?, val special: Boolean, val episodeType: Int = if (special) 1 else 0)

private val animeFilenameCache = object : LinkedHashMap<String, AnimeFilename>(128, .75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimeFilename>?) = size > 8192
}

fun parseAnimeFilename(raw: String): AnimeFilename {
  synchronized(animeFilenameCache) { animeFilenameCache[raw] }?.let { return it }
  return parseAnimeFilenameUncached(raw).also { synchronized(animeFilenameCache) { animeFilenameCache[raw] = it } }
}

/** Keep season markers in the title: removing them would silently merge separate Bangumi subjects. */
private fun parseAnimeFilenameUncached(raw: String): AnimeFilename {
  val parser = com.github.TraceLTRC.AnitomyK()
  runCatching { parser.parse(Normalizer.normalize(raw, Normalizer.Form.NFKC)) }
  fun element(category: com.github.TraceLTRC.ElementCategory) = parser.elements.firstOrNull { it.first == category }?.second.orEmpty()
  var name = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    .replace(Regex("(?i)\\.(mkv|mp4|mov|avi|webm|m4v|ts)$"), "")
  val specialTag = Regex("(?i)(?:^|[\\s_\\-\\[\\(])(SP|OVA|OAD|NCOP|NCED|OP|ED)(?:[\\s_\\-\\]\\)]|\\d|$)").find(name)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT)
  val special = specialTag != null
  val episodeMatch = Regex("(?i)S\\d{1,2}E(\\d{1,3}(?:\\.\\d+)?)").find(name)
    ?: Regex("(?i)(?:^|[\\s_\\-\\[\\(])(?:EP?|SP|OVA|OAD|NCOP|NCED|OP|ED)[ ._-]*(\\d{1,3}(?:\\.\\d+)?)").find(name)
    ?: Regex("第\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*[话話集]").find(name)
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
  val originalTitle = element(com.github.TraceLTRC.ElementCategory.kElementAnimeTitle)
  val originalEpisode = element(com.github.TraceLTRC.ElementCategory.kElementEpisodeNumber).toDoubleOrNull()
  val explicitSeasonOrChinese = Regex("(?i)S\\d{1,2}E\\d|第\\s*\\d+[话話集]|Season\\s*\\d|\\d+(?:nd|rd|th)\\s+Season|(?:Second|Third|Fourth|Final)\\s+Season|\\d+(?:nd|rd|th)(?:\\s|$)").containsMatchIn(raw)
  val type = when (specialTag) {
    "NCOP", "OP" -> 2
    "NCED", "ED" -> 3
    null -> 0
    else -> 1
  }
  return AnimeFilename(if (explicitSeasonOrChinese) name else originalTitle.ifBlank { name },
    originalEpisode ?: episode, special, type)
}

fun animeNameKey(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC)
  .lowercase(java.util.Locale.ROOT)
  .replace(Regex("second\\s+season|2nd\\s+season"), "season2")
  .replace(Regex("third\\s+season|3rd\\s+season"), "season3")
  .replace(Regex("fourth\\s+season|4th\\s+season"), "season4")
  .filter { it.isLetterOrDigit() || it == '!' }

/** Ambiguous search results require confirmation; popularity is never an identity signal. */
fun matchAnimeSubject(query: String, candidates: List<AnimeSubject>): AnimeSubject? {
  val key = animeNameKey(query)
  if (key.length < 2) return null
  return candidates.filter { animeNameKey(it.name) == key || animeNameKey(it.chineseName) == key }
    .distinctBy { it.id }.singleOrNull()
}

private val extraDirectoryNames = setOf("sp", "sps", "special", "specials", "extra", "extras", "bonus", "ncop", "nced", "op", "ed", "pv", "cm", "menu", "menus", "特典", "映像特典")

fun isAnimeExtraVideo(file: app.gyrolet.mpvrx.domain.network.NetworkFile): Boolean =
  file.path.substringBeforeLast('/').split('/').any { it.lowercase(java.util.Locale.ROOT) in extraDirectoryNames } || parseAnimeFilename(file.name).special

private fun animeSeason(name: String): Int? {
  val key = animeNameKey(name)
  return Regex("(?:season|第)([2-9])[季期]?").find(key)?.groupValues?.get(1)?.toIntOrNull()
    ?: Regex("([2-9])(?:nd|rd|th)").find(key)?.groupValues?.get(1)?.toIntOrNull()
    ?: Regex("\\b(?:Second|Third|Fourth)\\s+Season", RegexOption.IGNORE_CASE).find(name)?.value?.let {
      when (it.substringBefore(' ').lowercase()) { "second" -> 2; "third" -> 3; else -> 4 }
    }
}

data class AnimeVideoGroup(val key: String, val directory: String, val query: String,
  val files: List<app.gyrolet.mpvrx.domain.network.NetworkFile>, val queries: List<String> = listOf(query))

/** Specials inherit the release directory; mixed movies still have separate stable bindings. */
fun animeVideoGroups(files: List<app.gyrolet.mpvrx.domain.network.NetworkFile>): List<AnimeVideoGroup> =
  files.distinctBy { app.gyrolet.mpvrx.domain.network.NetworkPath.from(it.path).value }.filter { !it.isDirectory && (it.mimeType?.startsWith("video/") == true ||
    cloudMediaExtension(it.name) in app.gyrolet.mpvrx.utils.storage.FileTypeUtils.VIDEO_EXTENSIONS) }
    .groupBy { file ->
      val parent = file.path.substringBeforeLast('/').ifBlank { "/" }
      val segments = parent.split('/').filter { it.isNotBlank() }
      val extra = segments.indexOfFirst { it.lowercase(java.util.Locale.ROOT) in extraDirectoryNames }
      if (extra > 0) "/" + segments.take(extra).joinToString("/") else parent
    }.flatMap { (directory, videos) ->
      val folderTitle = parseAnimeFilename(directory.substringAfterLast('/')).title
      val normal = videos.filterNot(::isAnimeExtraVideo).ifEmpty { videos }
      val titles = normal.groupBy { parseAnimeFilename(it.name).title }
      val generic = directory == "/" || Regex("(?i)^(anime|movies?|videos?|BD|BDMV|动画|电影|\\d{4})$").matches(folderTitle)
      fun queries(title: String): List<String> {
        val primarySeason = animeSeason(title)
        val ancestors = directory.split('/').filter { it.isNotBlank() }.dropLast(1).asReversed().map { parseAnimeFilename(it).title }
        return (listOf(title) + titles.keys + ancestors).filter { it.isNotBlank() &&
          (primarySeason == null || animeSeason(it) == primarySeason) }.distinct()
      }
      if ((generic || titles.size > 1) && titles.keys.none { animeNameKey(it) == animeNameKey(folderTitle) } && titles.keys.all { it.isNotBlank() }) {
        titles.map { (title, rows) -> AnimeVideoGroup("$directory/#anime=${animeNameKey(title)}", directory, title,
          (rows + videos.filter(::isAnimeExtraVideo).filter { animeNameKey(parseAnimeFilename(it.name).title) == animeNameKey(title) }).distinctBy { it.path }, listOf(title)) }
      } else listOf(AnimeVideoGroup(directory, directory, folderTitle.ifBlank { titles.keys.firstOrNull().orEmpty() }, videos,
        queries(folderTitle.ifBlank { titles.keys.firstOrNull().orEmpty() })))
    }

/** A file override can refine an entire folder assignment. */
fun regroupAnimeVideos(files: List<app.gyrolet.mpvrx.domain.network.NetworkFile>, assignments: Map<String, String>): List<AnimeVideoGroup> {
  val initial = animeVideoGroups(files)
  if (assignments.isEmpty()) return initial
  val targets = initial.associateBy { it.key }
  val output = linkedMapOf<String, MutableList<app.gyrolet.mpvrx.domain.network.NetworkFile>>()
  initial.forEach { group -> group.files.forEach { file ->
    val target = assignments.entries.filter { (path, key) -> key in targets &&
      (file.path == path || file.path.startsWith(path.trimEnd('/') + "/")) }.maxByOrNull { it.key.length }?.value ?: group.key
    output.getOrPut(target) { mutableListOf() }.add(file)
  } }
  return output.map { (key, rows) -> targets.getValue(key).copy(files = rows.distinctBy { it.path }) }
}
