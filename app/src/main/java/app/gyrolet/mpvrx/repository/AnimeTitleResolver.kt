package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.domain.cloud.animeNameKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** AniList only resolves exact English/romaji aliases to native names; Bangumi remains the source of metadata. */
internal class AnimeTitleResolver(private val http: OkHttpClient, private val cacheFile: File) {
  private val lock = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private var cache: MutableMap<String, JsonElement>? = null
  suspend fun nativeName(query: String): String? = withContext(Dispatchers.IO) {
    if (!query.any { it in 'A'..'Z' || it in 'a'..'z' }) return@withContext null
    lock.withLock {
      val rows = cache ?: runCatching { json.parseToJsonElement(cacheFile.readText()).jsonObject.toMutableMap() }
        .getOrDefault(mutableMapOf()).also { cache = it }
      val key = animeNameKey(query)
      val now = System.currentTimeMillis()
      rows[key]?.jsonObject?.let { row ->
        val ttl = if (row["native"]?.jsonPrimitive?.contentOrNull != null) 30L * 24 * 60 * 60_000 else 24L * 60 * 60_000
        if (now - (row["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0) < ttl) return@withLock row["native"]?.jsonPrimitive?.contentOrNull
      }
      val canonicalQuery = query.replace(Regex("(?i)\\bha\\b"), "wa")
        .replace(Regex("(?i)\\bOniichan\\b"), "Onii-chan")
        .replace(Regex("(?i)\\bLovecome\\b"), "Love Come")
        .replace(Regex("(?i)\\bMachigatte\\s+Iru\\b"), "Machigatteiru")
      // A shorter search only discovers candidates; identity still requires the full exact alias.
      val terms = listOf(query, canonicalQuery, canonicalQuery.split(' ').take(3).joinToString(" ")).distinct()
      var native: String? = null
      for (term in terms) {
      delay(2_000)
      currentCoroutineContext().ensureActive()
      val body = buildJsonObject {
        put("query", "query (\$search: String) { Page(page: 1, perPage: 25) { media(search: \$search, type: ANIME) { id title { romaji english native } synonyms } } }")
        putJsonObject("variables") { put("search", term) }
      }.toString()
      val request = Request.Builder().url("https://graphql.anilist.co")
        .header("User-Agent", "Sakura-Fubuki76/mpvRx/2.7.3 (https://github.com/Sakura-Fubuki76/mpvRx)")
        .post(body.toRequestBody("application/json".toMediaType())).build()
      val response = http.newCall(request).execute().use {
        if (it.code == 429) delay((it.header("Retry-After")?.toLongOrNull() ?: 60).coerceIn(1, 120) * 1000)
        check(it.isSuccessful) { "AniList HTTP ${it.code}" }
        json.parseToJsonElement(it.body?.string() ?: error("Empty AniList response")).jsonObject
      }
      check(response["errors"] == null) { "AniList query failed" }
      val candidates = response["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray.orEmpty()
      val matches = candidates.filter { value ->
        val row = value.jsonObject
        val titles = row["title"]?.jsonObject.orEmpty().values + row["synonyms"]?.jsonArray.orEmpty()
        titles.any { it.jsonPrimitive.contentOrNull?.let(::animeNameKey) == key }
      }.distinctBy { it.jsonObject["id"] }
      native = matches.singleOrNull()?.jsonObject?.get("title")?.jsonObject?.get("native")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        if (native != null) break
      }
      rows[key] = buildJsonObject { put("native", native?.let(::JsonPrimitive) ?: JsonNull); put("updatedAt", now) }
      cacheFile.parentFile?.mkdirs()
      val temp = File(cacheFile.parentFile, "anilist-native.tmp")
      temp.writeText(JsonObject(rows).toString())
      java.nio.file.Files.move(temp.toPath(), cacheFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      native
    }
  }
}
