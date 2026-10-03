package app.gyrolet.mpvrx.data.network.client

import android.net.Uri
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.network.SharedHttpClient
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenList API addresses stay private to the client; playback and subtitles use the existing proxy. */
class OpenListClient(
  private val connection: NetworkConnection,
  private val http: OkHttpClient = SharedHttpClient.derive { callTimeout(0, TimeUnit.SECONDS) },
  private val tokenStore: OpenListTokenStore? = null,
) : NetworkClient {
  private val json = Json { ignoreUnknownKeys = true }
  private val loginMutex = Mutex()
  @Volatile private var token: String? = null
  @Volatile private var connected = false
  private val origin = HttpUrl.Builder().scheme(if (connection.useHttps) "https" else "http")
    .host(connection.host.trim().removePrefix("[").removeSuffix("]")).port(connection.port).build()
  private val root = NetworkPath.from(connection.path)

  private fun fullPath(path: String): String = NetworkPath.from(root.value + "/" + NetworkPath.from(path).relative).value
  private fun relativePath(path: String): NetworkPath {
    val parsed = NetworkPath.from(path)
    require(parsed.segments.take(root.segments.size) == root.segments) { "Search result outside connection root" }
    return NetworkPath.from(parsed.segments.drop(root.segments.size).joinToString("/"))
  }

  private suspend fun <T> result(block: suspend () -> T): Result<T> = try {
    Result.success(block())
  } catch (cancelled: CancellationException) { throw cancelled }
  catch (error: Exception) { Result.failure(error) }

  override suspend fun connect(): Result<Unit> = result {
    listFiles("/").getOrThrow()
    connected = true
  }
  override suspend fun disconnect() { connected = false; token = null }
  override fun isConnected(): Boolean = connected
  override val supportsSearch: Boolean get() = true

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> = result {
    val directory = NetworkPath.from(path)
    val files = linkedMapOf<String, NetworkFile>()
    var page = 1
    do {
      val data = api("fs/list", buildJsonObject {
        put("path", fullPath(path)); put("password", ""); put("page", page); put("per_page", 200); put("refresh", false)
      })
      val items = data["content"] as? JsonArray ?: JsonArray(emptyList())
      items.forEach { item ->
        val obj = item.jsonObject
        val child = directory.child(obj.string("name"))
        files[child.value] = obj.file(child)
      }
      val total = data.long("total")
      if (items.isEmpty() || files.size >= total || items.size < 200) break
      check(page++ < 10_000) { "OpenList pagination exceeded limit" }
    } while (true)
    files.values.toList()
  }

  override suspend fun searchFiles(path: String, query: String): Result<List<NetworkFile>> = result {
    val files = linkedMapOf<String, NetworkFile>()
    var page = 1
    do {
      val data = api("fs/search", buildJsonObject {
        put("parent", fullPath(path)); put("keywords", query); put("scope", 2)
        put("page", page); put("per_page", 200); put("password", "")
      })
      val items = data["content"] as? JsonArray ?: JsonArray(emptyList())
      items.forEach { item ->
        val obj = item.jsonObject
        val raw = obj.string("fullPath").ifBlank { NetworkPath.from(obj.string("parent")).child(obj.string("name")).value }
        val relative = relativePath(raw)
        files[relative.value] = obj.file(relative)
      }
      if (items.isEmpty() || files.size >= data.long("total") || items.size < 200) break
      check(page++ < 10_000) { "OpenList search pagination exceeded limit" }
    } while (true)
    files.values.toList()
  }

  override suspend fun getFileSize(path: String): Result<Long> = result { get(path).long("size") }
  override suspend fun getThumbnailBytes(path: String): Result<ByteArray?> = result {
    repeat(2) { attempt ->
      val data = get(path)
      val raw = sequenceOf("thumb_512", "thumb_1024", "thumb").map { data.string(it) }.firstOrNull { it.isNotBlank() }
        ?: return@result null
      var url = origin.resolve(raw)?.takeIf { it.scheme in setOf("http", "https") } ?: return@result null
      if (raw.startsWith('/') && data.string("sign").isNotBlank() && url.queryParameter("sign") == null) {
        url = url.newBuilder().addQueryParameter("sign", data.string("sign")).build()
      }
      val request = Request.Builder().url(url)
      if (url.host == origin.host && url.port == origin.port && url.scheme == origin.scheme) {
        authorization()?.let { request.header("Authorization", it) }
      }
      val response = withContext(Dispatchers.IO) { runInterruptible { http.newCall(request.build()).execute() } }
      response.use {
        if (it.code in setOf(401, 403) && attempt == 0) return@use
        if (!it.isSuccessful) return@result null
        val bytes = withContext(Dispatchers.IO) { runInterruptible { it.body.byteStream().readNBytes(8 * 1024 * 1024 + 1) } }
        check(bytes.size <= 8 * 1024 * 1024) { "OpenList thumbnail response too large" }
        return@result bytes
      }
    }
    null
  }
  private suspend fun get(path: String) = api("fs/get", buildJsonObject { put("path", fullPath(path)); put("password", "") })

  override suspend fun getFileUri(path: String): Result<Uri> = result {
    val builder = origin.newBuilder().addPathSegment("d")
    NetworkPath.from(fullPath(path)).segments.forEach(builder::addPathSegment)
    Uri.parse(builder.build().toString())
  }

  override suspend fun getFileStream(path: String, offset: Long): Result<InputStream> = result {
    require(offset >= 0)
    var stream: InputStream? = null
    // fs/get is deliberately resolved for each new range, so signed URLs can rotate during playback.
    repeat(2) { attempt ->
      if (stream != null) return@repeat
      val data = get(path)
      val raw = data.string("raw_url")
      check(raw.startsWith("http://") || raw.startsWith("https://")) { "OpenList did not return a playable address" }
      val request = Request.Builder().url(raw).header("Accept-Encoding", "identity")
      (data["header"] as? JsonObject)?.forEach { (name, value) ->
        if (!name.equals("Host", true) && !name.equals("Range", true)) request.header(name, value.jsonPrimitive.content)
      }
      if (offset > 0) request.header("Range", "bytes=$offset-")
      val response = withContext(Dispatchers.IO) { runInterruptible { http.newCall(request.build()).execute() } }
      if (response.code in setOf(401, 403) && attempt == 0) { response.close(); return@repeat }
      if (!response.isSuccessful) { response.close(); error("OpenList stream HTTP ${response.code}") }
      if (offset > 0 && (response.code != 206 || response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull() != offset)) {
        response.close(); error("OpenList upstream did not honor the requested range")
      }
      stream = object : FilterInputStream(response.body.byteStream()) {
        override fun close() { try { super.close() } finally { response.close() } }
      }
    }
    stream ?: error("OpenList address refresh failed")
  }

  private suspend fun authorization(): String? {
    if (connection.isAnonymous) return null
    if (connection.username.equals("bearer", true)) return connection.password
    if (connection.username.startsWith("Bearer ", true)) return connection.username.substringAfter(' ')
    return loginMutex.withLock {
      token ?: tokenStore?.read(connection)?.also { token = it } ?: login()
    }
  }

  private suspend fun login(): String {
    val data = execute("auth/login", buildJsonObject {
      put("username", connection.username); put("password", connection.password)
    }, null)
    if (data.code != 200) throw NetworkAuthenticationException("OpenList login rejected (${data.code})")
    val value = data.data?.string("token")?.takeIf { it.isNotBlank() }
      ?: throw NetworkAuthenticationException("OpenList login returned no token")
    token = value
    tokenStore?.write(connection, value)
    return value
  }

  private suspend fun api(endpoint: String, body: JsonObject): JsonObject {
    val auth = authorization()
    var response = execute(endpoint, body, auth)
    if (response.code in setOf(401, 403) && !connection.isAnonymous && !connection.username.startsWith("Bearer", true)) {
      val replacement = loginMutex.withLock {
        if (token != null && token != auth) token!! else {
          token = null; tokenStore?.remove(connection); login()
        }
      }
      response = execute(endpoint, body, replacement)
    }
    if (response.code in setOf(401, 403)) throw NetworkAuthenticationException("OpenList access rejected (${response.code})")
    check(response.code == 200) { "OpenList API error ${response.code}" }
    return response.data ?: JsonObject(emptyMap())
  }

  private data class ApiResponse(val code: Int, val data: JsonObject?)
  private suspend fun execute(endpoint: String, body: JsonObject, auth: String?): ApiResponse = withContext(Dispatchers.IO) {
    runInterruptible {
      val request = Request.Builder().url(origin.newBuilder().addPathSegments("api/$endpoint").build())
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
      auth?.let { request.header("Authorization", it) }
      http.newCall(request.build()).execute().use { response ->
        if (!response.isSuccessful) return@runInterruptible ApiResponse(response.code, null)
        val bytes = response.body.byteStream().readNBytes(8 * 1024 * 1024 + 1)
        check(bytes.size <= 8 * 1024 * 1024) { "OpenList API response too large" }
        val obj = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        ApiResponse(obj.long("code").toInt(), obj["data"] as? JsonObject)
      }
    }
  }

  private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
  private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
  private fun JsonObject.file(path: NetworkPath): NetworkFile {
    val directory = (get("is_dir") as? JsonPrimitive)?.booleanOrNull == true
    val modified = string("modified").toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
      ?: runCatching { Instant.parse(string("modified")).toEpochMilli() }.getOrDefault(0L)
    return NetworkFile(string("name"), path.value, long("size"), directory, modified,
      if (directory) null else NetworkMimeTypes.forFileName(string("name")),
      width = long("width").coerceIn(0, 32768).toInt(), height = long("height").coerceIn(0, 32768).toInt())
  }
}
