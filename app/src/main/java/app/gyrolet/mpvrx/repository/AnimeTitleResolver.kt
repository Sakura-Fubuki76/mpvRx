package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.domain.cloud.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Resolve verified multilingual aliases; never use AniList's search ranking as identity. */
internal class AnimeTitleResolver(private val http: OkHttpClient, private val cacheFile: File) {
  private val lock = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private var cache: MutableMap<String, JsonElement>? = null
  private var nextRequestAt = 0L

  suspend fun nativeQueries(query: String, force: Boolean = false): List<String> = withContext(Dispatchers.IO) {
    lock.withLock {
      val rows = cache ?: runCatching { json.parseToJsonElement(cacheFile.readText()).jsonObject.toMutableMap() }
        .getOrDefault(mutableMapOf()).also { cache = it }
      val key = animeIdentityKey(query)
      val now = System.currentTimeMillis()
      rows[key]?.jsonObject?.let { row ->
        val saved = row["nativeQueries"]?.jsonArray?.map { it.jsonPrimitive.content }
        val ttl = if (!saved.isNullOrEmpty()) 30L * 24 * 60 * 60_000 else 24L * 60 * 60_000
        if (!force && saved != null && now - (row["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0) < ttl) return@withLock saved
      }
      var resolved = emptyList<String>()
      for (term in animeDiscoveryTerms(query).take(4)) {
        val response = request(term)
        val candidates = response["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray.orEmpty().map { value ->
          val row = value.jsonObject
          val title = row["title"]?.jsonObject.orEmpty()
          fun string(key: String) = title[key]?.jsonPrimitive?.contentOrNull.orEmpty()
          AnimeTitleCandidate(row["id"]!!.jsonPrimitive.long, string("native"), string("romaji"), string("english"),
            row["synonyms"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            row["format"]?.jsonPrimitive?.contentOrNull.orEmpty(), row["episodes"]?.jsonPrimitive?.intOrNull ?: 0)
        }
        resolved = animeNativeQueries(query, candidates)
        if (resolved.isNotEmpty()) break
      }
      rows[key] = buildJsonObject { put("nativeQueries", JsonArray(resolved.map(::JsonPrimitive))); put("updatedAt", now) }
      cacheFile.parentFile?.mkdirs()
      val temp = File(cacheFile.parentFile, "anilist-native.tmp")
      temp.writeText(JsonObject(rows).toString())
      java.nio.file.Files.move(temp.toPath(), cacheFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      resolved
    }
  }

  private suspend fun request(term: String): JsonObject {
    for (attempt in 0..1) {
      delay((nextRequestAt - System.currentTimeMillis()).coerceAtLeast(0))
      currentCoroutineContext().ensureActive()
      val body = buildJsonObject {
        put("query", "query (\$search: String) { Page(page: 1, perPage: 25) { media(search: \$search, type: ANIME) { id format episodes title { romaji english native } synonyms } } }")
        putJsonObject("variables") { put("search", term) }
      }.toString()
      val request = Request.Builder().url("https://graphql.anilist.co")
        .header("User-Agent", "Sakura-Fubuki76/mpvRx/2.7.3 (https://github.com/Sakura-Fubuki76/mpvRx)")
        .post(body.toRequestBody("application/json".toMediaType())).build()
      var retryAfter = 0L
      val response = http.newCall(request).execute().use {
        if (it.code == 429) {
          retryAfter = (it.header("Retry-After")?.toLongOrNull() ?: 60).coerceIn(1, 120) * 1000
          null
        } else {
          check(it.isSuccessful) { "AniList HTTP ${it.code}" }
          json.parseToJsonElement(it.body?.string() ?: error("Empty AniList response")).jsonObject
        }
      }
      nextRequestAt = System.currentTimeMillis() + if (retryAfter > 0) retryAfter else 2_000
      if (response == null) continue
      check(response["errors"] == null) { "AniList query failed" }
      return response
    }
    error("AniList HTTP 429")
  }
}
