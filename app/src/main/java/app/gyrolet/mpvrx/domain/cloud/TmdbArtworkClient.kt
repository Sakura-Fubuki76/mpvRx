package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class TmdbArtwork(val id: Long, val logo: String, val cover: String, val backdrop: String, val poster: String,
  val binding: AnimeArtworkBinding = AnimeArtworkBinding())

internal fun tmdbArtworkNames(subject: AnimeSubject): List<String> =
  (listOf(subject.name, subject.chineseName) + subject.aliases).filter { it.isNotBlank() }.flatMap { name ->
    listOf(name, animeWithoutSeason(name), name.replace(Regex("(?i)\\bgaiden\\b|外伝|外传"), "").trim(), name.replace(Regex("(?i)^(?:TVアニメ|テレビアニメ|劇場版|剧场版|gekij(?:ou|o|ō)ban)\\s*"), "").trim())
  }.filter { it.isNotBlank() }.distinct()

internal fun tmdbTitleKey(name: String): String = animeIdentityKey(name, "MOVIE").removeSuffix("|movie")

/** Exact title evidence only. Structural season/episode evidence is verified separately. */
internal fun selectTmdbArtworkSubject(subject: AnimeSubject, rows: List<JsonObject>): JsonObject? {
  val names = tmdbArtworkNames(subject).map(::tmdbTitleKey).toSet()
  val year = subject.date.take(4).toIntOrNull()
  val ranked = rows.distinctBy { it["media_type"] to it["id"] }.mapNotNull { row ->
    val matching = listOf("name", "original_name", "title", "original_title").any { row.tmdbText(it).let(::tmdbTitleKey) in names }
    val genres = row["genre_ids"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
    if (!matching || !tmdbAcceptsGenre(subject, genres)) return@mapNotNull null
    val date = row.tmdbText("first_air_date").ifBlank { row.tmdbText("release_date") }.take(4).toIntOrNull()
    row to if (year != null && date == year) 2 else 1
  }.sortedByDescending { it.second }
  return ranked.firstOrNull()?.takeIf { ranked.size == 1 || it.second > ranked[1].second }?.first
}

internal fun tmdbAcceptsGenre(subject: AnimeSubject, genres: List<Int>): Boolean =
  genres.isEmpty() || 16 in genres || (subject.format in listOf("演出", "其他") && 10402 in genres)

internal fun JsonObject.tmdbText(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
internal fun JsonObject.tmdbRows(key: String): List<JsonObject> = get(key)?.jsonArray.orEmpty().map { it.jsonObject }

/** Prefer Japanese assets, then resolution within a language, before popularity. */
private fun tmdbArtworkLanguageRank(row: JsonObject): Int = when (row["iso_639_1"]?.jsonPrimitive?.contentOrNull) {
  "ja" -> 4; "zh" -> 3; "en" -> 2; null -> 1; else -> 0
}

private val tmdbLocalizedArtworkOrder = compareByDescending<JsonObject>(::tmdbArtworkLanguageRank)
  .thenByDescending { it["width"]?.jsonPrimitive?.intOrNull ?: 0 }
  .thenByDescending { it["height"]?.jsonPrimitive?.intOrNull ?: 0 }
  .thenByDescending { it["vote_count"]?.jsonPrimitive?.intOrNull ?: 0 }
  .thenByDescending { it["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
  .thenBy { it.tmdbText("file_path") }

internal fun selectTmdbLogo(rows: List<JsonObject>): String =
  rows.filter { it.tmdbText("file_path").startsWith('/') && it.tmdbText("file_path").endsWith(".png", true) }
    .sortedWith(tmdbLocalizedArtworkOrder).firstOrNull()?.tmdbText("file_path").orEmpty()

internal fun selectTmdbTextlessImage(images: JsonObject, kind: String): String {
    val rows = images[kind]?.jsonArray.orEmpty().map { it.jsonObject }
      .filter { it["iso_639_1"] == JsonNull && it["file_path"]?.jsonPrimitive?.contentOrNull?.startsWith('/') == true }
    val path = preferClearTmdbImages(rows)
      .sortedWith(compareByDescending<JsonObject> { it["vote_count"]?.jsonPrimitive?.intOrNull ?: 0 }
        .thenByDescending { it["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
        .thenByDescending { it["width"]?.jsonPrimitive?.intOrNull ?: 0 })
      .firstOrNull()?.get("file_path")?.jsonPrimitive?.contentOrNull
    return path?.let { "https://image.tmdb.org/t/p/original$it" }.orEmpty()
}

private fun preferClearTmdbImages(rows: List<JsonObject>): List<JsonObject> =
  rows.filter { (it["width"]?.jsonPrimitive?.intOrNull ?: 0) >= 1280 }.ifEmpty { rows }

internal fun selectTmdbPoster(images: JsonObject): String {
  val path = images.tmdbRows("posters")
    .filter { it["iso_639_1"]?.jsonPrimitive?.contentOrNull != null && it.tmdbText("file_path").startsWith('/') }
    .sortedWith(tmdbLocalizedArtworkOrder).firstOrNull()?.tmdbText("file_path")
  return path?.let { "https://image.tmdb.org/t/p/w780$it" }.orEmpty()
}

internal class TmdbArtworkClient(private val http: OkHttpClient) {
  private val responses = object : LinkedHashMap<String, Pair<Long, JsonObject>>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JsonObject>>?): Boolean = size > 256
  }
  private suspend fun request(path: String, credential: String, query: Map<String, String>): JsonObject {
    val key = credential.removePrefix("Bearer ").trim()
    val apiKey = Regex("[a-fA-F0-9]{32}").matches(key)
    val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
      query.forEach { (name, value) -> addQueryParameter(name, value) }
      if (apiKey) addQueryParameter("api_key", key)
    }.build()
    val cacheKey = "${key.hashCode()}:$path:${query.toSortedMap()}"
    synchronized(responses) { responses[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < 6 * 60 * 60_000L }?.let { return it.second } }
    val request = Request.Builder().url(url).header("Accept", "application/json")
      .apply { if (!apiKey) header("Authorization", "Bearer $key") }.build()
    return http.newCall(request).awaitResponse().use {
      check(it.isSuccessful) { "TMDB HTTP ${it.code}" }
      Json.parseToJsonElement(it.body?.string() ?: error("Empty TMDB response")).jsonObject.also { value ->
        synchronized(responses) { responses[cacheKey] = System.currentTimeMillis() to value }
      }
    }
  }

  suspend fun fetch(subject: AnimeSubject, credential: String,
    related: (suspend (Long) -> List<AnimeSubject>)? = null): TmdbArtwork? {
    val direct = discover(subject, credential)
    var matches = resolve(subject, direct, credential)
    if (selectTmdbBinding(matches) == null && related != null) {
      val visited = mutableSetOf(subject.id)
      var frontier = listOf(subject)
      var inspected = 0
      for (depth in 0..2) {
        val next = mutableListOf<AnimeSubject>()
        for (parent in frontier) {
          for (root in related(parent.id)) {
            if (!visited.add(root.id)) continue
            next += root
            if (++inspected > 18) break
            val candidates = discover(root, credential)
            matches = resolve(subject, candidates, credential, root)
            if (selectTmdbBinding(matches) != null) break
          }
          if (selectTmdbBinding(matches) != null || inspected > 18) break
        }
        if (selectTmdbBinding(matches) != null || inspected > 18) break
        frontier = next
      }
    }
    val binding = selectTmdbBinding(matches) ?: return null
    // Episode ranges and special collections do not have their own poster/logo endpoint.
    if (binding.episodes.isNotEmpty()) return TmdbArtwork(binding.id, "", "", "", "", binding)
    val seriesImages = request("${binding.type}/${binding.id}/images", credential, emptyMap())
    val images = if (binding.scope != "work" && binding.season != null) request("tv/${binding.id}/season/${binding.season}/images", credential, emptyMap()) else seriesImages
    // A series logo may name a different installment. Only exact standalone identities use it.
    val logo = if (binding.scope == "work") selectTmdbLogo(seriesImages.tmdbRows("logos")) else ""
    return TmdbArtwork(binding.id, logo.takeIf { it.startsWith('/') }?.let { "https://image.tmdb.org/t/p/original$it" }.orEmpty(),
      selectTmdbTextlessImage(images, "posters"), if (binding.scope == "work") selectTmdbTextlessImage(seriesImages, "backdrops") else "",
      selectTmdbPoster(images), binding)
  }

  suspend fun searchImages(query: String, credential: String): List<AnimeArtworkHit> =
    request("search/multi", credential, mapOf("query" to query, "language" to "zh-CN", "include_adult" to "false"))
      .tmdbRows("results").filter { it.tmdbText("media_type") in listOf("movie", "tv") }.take(20).map { row ->
        val path = row.tmdbText("poster_path")
        AnimeArtworkHit("TMDB", row["id"]!!.jsonPrimitive.long, row.tmdbText("media_type"),
          row.tmdbText("title").ifBlank { row.tmdbText("name") } + " · " + row.tmdbText("release_date").ifBlank { row.tmdbText("first_air_date") }.take(4),
          if (path.startsWith('/')) "https://image.tmdb.org/t/p/w185$path" else "")
      }

  suspend fun lookupImages(id: Long, type: String, credential: String): AnimeArtworkHit {
    require(id > 0 && type in listOf("tv", "movie"))
    val row = request("$type/$id", credential, mapOf("language" to "zh-CN"))
    val path = row.tmdbText("poster_path")
    return AnimeArtworkHit("TMDB", id, type, row.tmdbText("title").ifBlank { row.tmdbText("name") },
      if (path.startsWith('/')) "https://image.tmdb.org/t/p/w185$path" else "")
  }

  suspend fun imageChoices(hit: AnimeArtworkHit, credential: String, target: AnimeArtworkTarget): List<AnimeArtworkChoice> {
    val logo = target == AnimeArtworkTarget.LOGO
    fun choices(images: JsonObject, label: String): List<AnimeArtworkChoice> = images.tmdbRows(if (logo) "logos" else "posters")
      .filter { it.tmdbText("file_path").startsWith('/') && (!logo || it.tmdbText("file_path").endsWith(".png", true)) &&
        (target != AnimeArtworkTarget.LIBRARY_POSTER || it["iso_639_1"]?.jsonPrimitive?.contentOrNull != null) }
      .sortedWith(if (target == AnimeArtworkTarget.DETAIL_POSTER)
        compareByDescending<JsonObject> { it["iso_639_1"] == JsonNull }
          .thenByDescending { it["width"]?.jsonPrimitive?.intOrNull ?: 0 }
          .thenByDescending { it["vote_count"]?.jsonPrimitive?.intOrNull ?: 0 }
        else tmdbLocalizedArtworkOrder).map { row ->
        val path = row.tmdbText("file_path")
        AnimeArtworkChoice("https://image.tmdb.org/t/p/${if (logo) "original" else if (target == AnimeArtworkTarget.LIBRARY_POSTER) "w780" else "original"}$path",
          "https://image.tmdb.org/t/p/${if (logo) "w300" else "w185"}$path",
          listOf(label, row.tmdbText("iso_639_1").ifBlank { "—" }, "${row.tmdbText("width")}×${row.tmdbText("height")}").filter { it.isNotBlank() }.joinToString(" · "), row["iso_639_1"]?.jsonPrimitive?.contentOrNull)
      }
    val result = choices(request("${hit.type}/${hit.id}/images", credential, emptyMap()), "") .toMutableList()
    if (hit.type == "tv" && !logo) {
      val details = request("tv/${hit.id}", credential, mapOf("language" to "zh-CN"))
      for (season in details.tmdbRows("seasons")) {
        val n = season["season_number"]?.jsonPrimitive?.intOrNull ?: continue
        result += choices(request("tv/${hit.id}/season/$n/images", credential, emptyMap()), season.tmdbText("name"))
      }
    }
    return result.distinctBy { it.url }
  }

  private suspend fun discover(subject: AnimeSubject, credential: String): List<JsonObject> {
    val candidates = mutableListOf<JsonObject>()
    for (name in tmdbArtworkNames(subject)) {
      candidates += request("search/multi", credential, mapOf("query" to name, "language" to "zh-CN", "include_adult" to "false"))
        .tmdbRows("results").filter { it.tmdbText("media_type") in listOf("tv", "movie") && tmdbAcceptsGenre(subject, it["genre_ids"]?.jsonArray.orEmpty().mapNotNull { v -> v.jsonPrimitive.intOrNull }) }
      // Exact candidates still undergo date/count/season validation; don't stop on a tie.
      if (selectTmdbArtworkSubject(subject, candidates) != null) break
    }
    // Search includes indexed alternative titles that aren't present in the search response.
    // Keep a small candidate pool; full details and episode evidence decide the association.
    return candidates.distinctBy { it.tmdbText("media_type") to it.tmdbText("id") }.take(6)
  }

  private suspend fun resolve(subject: AnimeSubject, rows: List<JsonObject>, credential: String, discoverySubject: AnimeSubject = subject): List<TmdbBindingCandidate> {
    val result = mutableListOf<TmdbBindingCandidate>()
    for (row in rows) {
      val type = row.tmdbText("media_type")
      val id = row["id"]?.jsonPrimitive?.longOrNull ?: continue
      val core = request("$type/$id", credential, mapOf("language" to "ja-JP", "append_to_response" to "alternative_titles"))
      val details = JsonObject(core + ("search_titles" to JsonArray(listOf("name", "original_name", "title", "original_title").map { JsonPrimitive(row.tmdbText(it)) })))
      if (type == "movie") {
        tmdbMovieBinding(subject, details)?.let(result::add)
      } else {
        val seasons = details.tmdbRows("seasons").filter { (it["episode_count"]?.jsonPrimitive?.intOrNull ?: 0) > 0 }
        val matches = mutableListOf<TmdbBindingCandidate>()
        val seasonData = mutableListOf<JsonObject>()
        for (season in seasons) {
          val n = season["season_number"]?.jsonPrimitive?.intOrNull ?: continue
          val data = request("tv/$id/season/$n", credential, mapOf("language" to "ja-JP"))
          seasonData += data
          tmdbSeasonBinding(subject, details, data, tmdbSeriesTitleMatches(discoverySubject, details))?.let(matches::add)
        }
        result += matches
        if (matches.isEmpty()) tmdbWholeSeriesBinding(subject, details, seasonData)?.let(result::add)
      }
    }
    return result
  }
}
