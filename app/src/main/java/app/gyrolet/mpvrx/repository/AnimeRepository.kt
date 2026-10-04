package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.database.dao.AnimeDao
import app.gyrolet.mpvrx.database.entities.*
import app.gyrolet.mpvrx.domain.cloud.*
import app.gyrolet.mpvrx.domain.network.NetworkFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

class AnimeLibrarySnapshot(val files: List<NetworkFile> = emptyList(), val groups: Map<String, AnimeVideoGroup> = emptyMap(), val playbackIdentities: Map<String, String> = emptyMap())

data class AnimeCatalog(val folders: Map<String, AnimeFolderEntity>, val subjects: Map<Long, AnimeSubject>)

/** A separate queue keeps public metadata requests out of the playback/thumbnail queue. */
class AnimeRepository(private val dao: AnimeDao, client: OkHttpClient, context: android.content.Context) {
  private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
  private val pending = java.util.concurrent.ConcurrentHashMap<Long, List<NetworkFile>>()
  private val datasetFile = java.io.File(context.filesDir, "anime/bangumi-data.json")
  private val searchFile = java.io.File(context.filesDir, "anime/bangumi-search.json")
  private val searchLock = Mutex()
  private var searchCache: MutableMap<String, JsonElement>? = null
  private val titleResolver = AnimeTitleResolver(http, java.io.File(context.filesDir, "anime/anilist-native.json"))
  private val scheduledRevisions = java.util.concurrent.ConcurrentHashMap<Long, Pair<Int, Long>>()
  private val datasetLock = Mutex()
  private var aliases: Map<String, Set<Long>>? = null
  private val requests = Mutex()
  private val associations = Mutex()

  private val decodedSubjects = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, AnimeSubject>>()
  private val libraries = java.util.concurrent.ConcurrentHashMap<Pair<Long, String>, StateFlow<AnimeLibrarySnapshot>>()
  fun observeLibrary(id: Long, path: String, source: StateFlow<List<NetworkFile>>): StateFlow<AnimeLibrarySnapshot> = libraries.getOrPut(id to path) {
    var structureKey: List<Pair<String, String>>? = null
    var structure = emptyList<AnimeVideoGroup>()
    var identities = emptyMap<String, String>()
    source.map { files ->
      val key = files.map { it.path to it.name }
      if (key != structureKey) {
        val started = android.os.SystemClock.elapsedRealtime()
        structure = animeVideoGroups(files).map { group -> group.copy(files = group.files.sortedWith(
          compareBy<NetworkFile> { animeSectionPath(group.directory, it) }
            .thenBy { if (animeSectionPath(group.directory, it).isEmpty() && !isAnimeExtraVideo(it)) parseAnimeFilename(it.name).episode ?: Double.MAX_VALUE else Double.MAX_VALUE }.thenBy { it.name })) }
        identities = files.associate { it.path to app.gyrolet.mpvrx.ui.player.PlaybackIdentity.forNetwork(id, it.path) }
        structureKey = key
        CloudTrace.event("anime.structure", id, path, "videos=${files.size} groups=${structure.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
      }
      val latest = files.associateBy { it.path }
      AnimeLibrarySnapshot(files, structure.associate { group -> group.key to group.copy(files = group.files.mapNotNull { latest[it.path] }) }, identities)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), AnimeLibrarySnapshot())
  }

  private val catalogs = java.util.concurrent.ConcurrentHashMap<Long, StateFlow<AnimeCatalog>>()
  fun observe(id: Long): StateFlow<AnimeCatalog> = catalogs.getOrPut(id) {
    combine(dao.observeFolders(id), dao.observeSubjects()) { folders, subjects ->
    AnimeCatalog(folders.filter { it.manual || it.query.startsWith("directory-v3 | ") }.associateBy { it.path }, subjects.mapNotNull { row ->
      runCatching {
        val decoded = decodedSubjects[row.id]?.takeIf { it.first == row.payload }?.second
          ?: json.decodeFromString<AnimeSubject>(row.payload).also { decodedSubjects[row.id] = row.payload to it }
        row.id to decoded
      }.getOrNull()
    }.toMap())
  }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), AnimeCatalog(emptyMap(), emptyMap()))
  }

  fun stop(id: Long) { pending.remove(id); jobs.remove(id)?.cancel() }

  @Synchronized
  fun schedule(id: Long, files: List<NetworkFile>, force: Boolean = false) {
    val revision = files.filterNot { it.isDirectory }.fold(1) { hash, file -> 31 * hash + listOf(file.path, file.size, file.lastModified).hashCode() }
    val now = System.currentTimeMillis()
    val scheduled = scheduledRevisions[id]
    if (!force && scheduled?.first == revision && now - scheduled.second < 5 * 60_000) return
    if (jobs[id]?.isActive == true) { if (force) stop(id) else { pending[id] = files; return } }
    scheduledRevisions[id] = revision to now
    jobs[id] = scope.launch {
      dao.clearAssignments(id)
      val groups = withContext(Dispatchers.Default) { animeVideoGroups(files) }
      groups.forEach { group ->
        ensureActive()
        val path = group.key
        val baseQuery = "directory-v3 | " + group.queries.joinToString(" | ")
        val query = baseQuery + " | aliases-v2"
        val previous = dao.getFolder(id, path)
        if (previous?.manual == true) {
          previous.subjectId?.let { runCatching { subject(it, forceRefresh = force) } }
          return@forEach
        }
        if (!force && previous?.subjectId != null && previous.query == baseQuery) {
          associations.withLock { dao.bindAutomatically(previous.copy(query = query)) }
          return@forEach
        }
        if (!force && previous != null && previous.query == query && System.currentTimeMillis() - previous.attemptedAt < 24 * 60 * 60_000L) return@forEach
        try {
          val index = aliasIndex()
          val aliasId = index[animeNameKey(group.query)]?.singleOrNull()
          var match = aliasId?.let { subject(it, forceRefresh = force) }
          if (match == null) {
            try {
              val native = titleResolver.nativeName(group.query)
              if (native != null) {
                val nativeId = index[animeNameKey(native)]?.singleOrNull()
                match = nativeId?.let { subject(it, forceRefresh = force) } ?: matchAnimeSubject(native, search(native, force))
              }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CloudTrace.event("anime.alias.failed", id, path, "error=${error.javaClass.simpleName}") }
          }
          if (match == null) {
            for (name in group.queries.take(4)) {
              match = index[animeNameKey(name)]?.singleOrNull()?.let { subject(it, forceRefresh = force) }
              if (match != null) break
              match = matchAnimeSubject(name, search(name, force))
              if (match != null) break
            }
          }
          if (match != null) subject(match.id)
          associations.withLock {
            dao.bindAutomatically(AnimeFolderEntity(id, path, query, match?.id, false, 0, System.currentTimeMillis()))
          }
          CloudTrace.event("anime.match", id, path, "matched=${match != null}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
          associations.withLock { dao.bindAutomatically(AnimeFolderEntity(id, path, query, previous?.takeIf { it.query == query }?.subjectId, false, previous?.episodeOffset ?: 0, System.currentTimeMillis())) }
          CloudTrace.event("anime.failed", id, path, "error=${error.javaClass.simpleName}")
        }
      }
    }.also { job ->
      job.invokeOnCompletion {
        jobs.remove(id, job)
        pending.remove(id)?.let { schedule(id, it) }
      }
    }
  }

  /** Multilingual exact aliases from bangumi-data (CC BY 4.0), cached independently of API results. */
  private suspend fun aliasIndex(): Map<String, Set<Long>> = datasetLock.withLock {
    aliases?.let { return@withLock it }
    if (!datasetFile.exists() || System.currentTimeMillis() - datasetFile.lastModified() > 7 * 24 * 60 * 60_000L) {
      try {
        http.newCall(Request.Builder().url("https://raw.githubusercontent.com/bangumi-data/bangumi-data/master/dist/data.json").build()).execute().use { response ->
          check(response.isSuccessful)
          val bytes = response.body?.bytes() ?: error("Empty dataset")
          json.parseToJsonElement(bytes.decodeToString()).jsonObject["items"]!!.jsonArray
          datasetFile.parentFile?.mkdirs()
          val temp = java.io.File(datasetFile.parentFile, "bangumi-data.tmp")
          temp.writeBytes(bytes)
          java.nio.file.Files.move(temp.toPath(), datasetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { /* Offline matching can still use a previous snapshot. */ }
    }
    val index = mutableMapOf<String, MutableSet<Long>>()
    if (datasetFile.exists()) runCatching {
      json.parseToJsonElement(datasetFile.readText()).jsonObject["items"]!!.jsonArray.forEach { value ->
        val row = value.jsonObject
        val id = row["sites"]?.jsonArray?.firstOrNull { it.jsonObject.string("site") == "bangumi" }?.jsonObject?.string("id")?.toLongOrNull() ?: return@forEach
        val names = listOf(row.string("title")) + row["titleTranslate"]?.jsonObject.orEmpty().values.flatMap { it.jsonArray.map { name -> name.jsonPrimitive.content } }
        names.forEach { name -> val key = animeNameKey(name); if (key.length > 1) index.getOrPut(key) { mutableSetOf() }.add(id) }
      }
    }
    index.mapValues { it.value.toSet() }.also { aliases = it }
  }

  suspend fun search(query: String, force: Boolean = false): List<AnimeSubject> = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext emptyList()
    searchLock.withLock {
      val rows = searchCache ?: runCatching { json.parseToJsonElement(searchFile.readText()).jsonObject.toMutableMap() }
        .getOrDefault(mutableMapOf()).also { searchCache = it }
      val key = animeNameKey(query)
      val now = System.currentTimeMillis()
      rows[key]?.jsonObject?.let { cached ->
        val data = cached["data"]?.jsonArray.orEmpty()
        val ttl = if (data.isEmpty()) 24 * 60 * 60_000L else 7 * 24 * 60 * 60_000L
        if (!force && now - cached.long("updatedAt") < ttl) return@withLock data.map { decodeSubject(it.jsonObject) }
      }
      val body = buildJsonObject {
        put("keyword", query); put("sort", "match")
        putJsonObject("filter") { putJsonArray("type") { add(2) } }
      }.toString()
      val data = request("/v0/search/subjects?limit=12", body)["data"]?.jsonArray ?: JsonArray(emptyList())
      rows[key] = buildJsonObject { put("updatedAt", now); put("data", data) }
      searchFile.parentFile?.mkdirs()
      val temp = java.io.File(searchFile.parentFile, "bangumi-search.tmp")
      temp.writeText(JsonObject(rows).toString())
      java.nio.file.Files.move(temp.toPath(), searchFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      data.map { decodeSubject(it.jsonObject) }
    }
  }

  private val subjectLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
  private val creditAttempts = java.util.concurrent.ConcurrentHashMap<Long, Long>()
  suspend fun subject(id: Long, forceRefresh: Boolean = false): AnimeSubject = withContext(Dispatchers.IO) {
    subjectLocks.getOrPut(id) { Mutex() }.withLock { fetchSubject(id, forceRefresh) }
  }

  /** Only enrich the opened work; successful credits survive restarts in the same Room payload. */
  fun scheduleCredits(id: Long) { scope.launch { ensureCredits(id) } }

  private suspend fun ensureCredits(id: Long) = withContext(Dispatchers.IO) {
    subjectLocks.getOrPut(id) { Mutex() }.withLock {
      val now = System.currentTimeMillis()
      val cached = dao.getSubject(id) ?: return@withLock
      val item = json.decodeFromString<AnimeSubject>(cached.payload)
      if (now - item.creditsFetchedAt < 7 * 24 * 60 * 60_000L || now - (creditAttempts[id] ?: 0) < 5 * 60_000L) return@withLock
      creditAttempts[id] = now
      try {
        val cast = decodeAnimeCast(requestJson("/v0/subjects/$id/characters").jsonArray)
        val staff = decodeAnimeStaff(requestJson("/v0/subjects/$id/persons").jsonArray)
        dao.putSubject(cached.copy(payload = json.encodeToString(AnimeSubject.serializer(), item.copy(cast = cast, staff = staff, creditsFetchedAt = now))))
        CloudTrace.event("anime.credits", 0, id.toString(), "cast=${cast.size} staff=${staff.size}")
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { CloudTrace.event("anime.credits.failed", 0, id.toString(), "error=${error.javaClass.simpleName}") }
    }
  }

  private suspend fun fetchSubject(id: Long, forceRefresh: Boolean): AnimeSubject {
    val cached = dao.getSubject(id)
    if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAt < 7 * 24 * 60 * 60_000L) {
      runCatching { json.decodeFromString<AnimeSubject>(cached.payload) }.getOrNull()?.let { return it }
    }
    return try {
      val item = decodeSubject(request("/v0/subjects/$id"))
      val episodes = mutableListOf<AnimeEpisode>()
      var offset = 0
      do {
        val page = request("/v0/episodes?subject_id=$id&limit=100&offset=$offset")
        val rows = page["data"]?.jsonArray.orEmpty()
        rows.forEach { value ->
          val row = value.jsonObject
          episodes.add(AnimeEpisode(row.long("id"), row["sort"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            row.long("type").toInt(), row.string("name_cn").ifBlank { row.string("name") }))
        }
        offset += rows.size
      } while (rows.isNotEmpty() && offset < page.long("total") && offset < 2000)
      val previous = cached?.let { runCatching { json.decodeFromString<AnimeSubject>(it.payload) }.getOrNull() }
      val result = item.copy(episodes = episodes, cast = previous?.cast.orEmpty(), staff = previous?.staff.orEmpty(), creditsFetchedAt = previous?.creditsFetchedAt ?: 0)
      dao.putSubject(AnimeSubjectEntity(id, json.encodeToString(AnimeSubject.serializer(), result), System.currentTimeMillis()))
      result
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
      cached?.let { runCatching { json.decodeFromString<AnimeSubject>(it.payload) }.getOrNull() } ?: throw error
    }
  }

  suspend fun bind(id: Long, path: String, subjectId: Long, offset: Int) {
    require(subjectId > 0)
    val item = subject(subjectId)
    associations.withLock {
      dao.putFolder(AnimeFolderEntity(id, path, item.title, subjectId, true, offset, System.currentTimeMillis()))
    }
  }

  private suspend fun request(path: String, body: String? = null): JsonObject = requestJson(path, body).jsonObject
  private suspend fun requestJson(path: String, body: String? = null): JsonElement = withContext(Dispatchers.IO) {
    requests.withLock {
      currentCoroutineContext().ensureActive()
      delay(400)
      val request = Request.Builder().url("https://api.bgm.tv$path")
        .header("User-Agent", "Sakura-Fubuki76/mpvRx/2.7.3 (https://github.com/Sakura-Fubuki76/mpvRx)")
        .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }.build()
      http.newCall(request).execute().use { response ->
        if (response.code == 429) {
          delay((response.header("Retry-After")?.toLongOrNull() ?: 30).coerceIn(1, 60) * 1000)
        }
        check(response.isSuccessful) { "Bangumi HTTP ${response.code}" }
        json.parseToJsonElement(response.body?.string() ?: error("Empty response"))
      }
    }
  }

  private fun decodeSubject(row: JsonObject) = AnimeSubject(row.long("id"), row.string("name"), row.string("name_cn"),
    row.string("summary"), row["images"]?.jsonObject?.let { it.string("large").ifBlank { it.string("common") } }.orEmpty(),
    row.string("date"), row["rating"]?.jsonObject?.get("score")?.jsonPrimitive?.doubleOrNull ?: 0.0,
    row["tags"]?.jsonArray.orEmpty().take(5).map { it.jsonObject.string("name") })
  private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
  private fun JsonObject.long(key: String) = get(key)?.jsonPrimitive?.longOrNull ?: 0L
}
