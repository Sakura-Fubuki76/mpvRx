package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class TmdbArtwork(val id: Long, val logo: String, val cover: String, val backdrop: String)

internal fun tmdbArtworkNames(subject: AnimeSubject): List<String> =
  (listOf(subject.name, subject.chineseName) + subject.aliases.take(3)).filter { it.isNotBlank() }
    .flatMap { name -> listOf(name, name.replace(Regex("(?i)(?:第[一二三四五六七八九十0-9]+[季期]|\\s+(?:season|part)\\s*\\d+|\\s+\\d+)$"), "").trim()) }
    .distinct().take(6)

/** Ambiguous titles stay unmatched; artwork must never change the Bangumi identity. */
internal fun selectTmdbArtworkSubject(subject: AnimeSubject, rows: List<JsonObject>): JsonObject? {
  val names = tmdbArtworkNames(subject).map { animeIdentityKey(it) }.filter { it.isNotBlank() }.toSet()
  val year = subject.date.take(4).toIntOrNull()
  val ranked = rows.distinctBy { it["id"] }.mapNotNull { row ->
    val matching = listOf("name", "original_name", "title", "original_title").any { key ->
      row[key]?.jsonPrimitive?.contentOrNull?.let { animeIdentityKey(it) } in names
    }
    val genres = row["genre_ids"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
    if (!matching || (genres.isNotEmpty() && 16 !in genres)) return@mapNotNull null
    val date = (row["first_air_date"] ?: row["release_date"])?.jsonPrimitive?.contentOrNull.orEmpty().take(4).toIntOrNull()
    row to if (year != null && date == year) 2 else 1
  }.sortedByDescending { it.second }
  return ranked.firstOrNull()?.takeIf { ranked.size == 1 || it.second > ranked[1].second }?.first
}

internal fun selectTmdbLogo(rows: List<JsonObject>): String {
  fun languageRank(row: JsonObject) = when (row["iso_639_1"]?.jsonPrimitive?.contentOrNull) {
    "zh" -> 4; "ja" -> 3; null -> 2; "en" -> 1; else -> 0
  }
  return rows.filter { it["file_path"]?.jsonPrimitive?.contentOrNull?.endsWith(".png", true) == true }
    .sortedWith(compareByDescending<JsonObject>(::languageRank)
      .thenByDescending { it["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
      .thenByDescending { it["width"]?.jsonPrimitive?.intOrNull ?: 0 })
    .firstOrNull()?.get("file_path")?.jsonPrimitive?.contentOrNull.orEmpty()
}

internal fun selectTmdbTextlessImage(images: JsonObject, kind: String): String {
    val path = images[kind]?.jsonArray.orEmpty().map { it.jsonObject }
      .filter { it["iso_639_1"] == JsonNull && it["file_path"]?.jsonPrimitive?.contentOrNull?.startsWith('/') == true }
      .sortedWith(compareByDescending<JsonObject> { it["vote_count"]?.jsonPrimitive?.intOrNull ?: 0 }
        .thenByDescending { it["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0 })
      .firstOrNull()?.get("file_path")?.jsonPrimitive?.contentOrNull
    return path?.let { "https://image.tmdb.org/t/p/w1280$it" }.orEmpty()
}

internal class TmdbArtworkClient(private val http: OkHttpClient) {
  private suspend fun request(path: String, credential: String, query: Map<String, String>): JsonObject {
    val key = credential.removePrefix("Bearer ").trim()
    val apiKey = Regex("[a-fA-F0-9]{32}").matches(key)
    val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
      query.forEach { (name, value) -> addQueryParameter(name, value) }
      if (apiKey) addQueryParameter("api_key", key)
    }.build()
    val request = Request.Builder().url(url).header("Accept", "application/json")
      .apply { if (!apiKey) header("Authorization", "Bearer $key") }.build()
    return http.newCall(request).awaitResponse().use {
      check(it.isSuccessful) { "TMDB HTTP ${it.code}" }
      Json.parseToJsonElement(it.body?.string() ?: error("Empty TMDB response")).jsonObject
    }
  }

  suspend fun fetch(subject: AnimeSubject, credential: String): TmdbArtwork? {
    val type = if (subject.format == "剧场版") "movie" else "tv"
    val candidates = mutableListOf<JsonObject>()
    var selected: JsonObject? = null
    for (name in tmdbArtworkNames(subject)) {
      candidates += request("search/$type", credential, mapOf("query" to name, "language" to "zh-CN"))["results"]?.jsonArray.orEmpty().map { it.jsonObject }
      selected = selectTmdbArtworkSubject(subject, candidates)
      if (selected != null) break
    }
    val id = selected?.get("id")?.jsonPrimitive?.longOrNull ?: return null
    val images = request("$type/$id/images", credential, mapOf("include_image_language" to "zh,ja,en,null"))
    val logo = selectTmdbLogo(images["logos"]?.jsonArray.orEmpty().map { it.jsonObject })
    return TmdbArtwork(id, logo.takeIf { it.startsWith('/') }?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty(),
      selectTmdbTextlessImage(images, "posters"), selectTmdbTextlessImage(images, "backdrops"))
  }
}
