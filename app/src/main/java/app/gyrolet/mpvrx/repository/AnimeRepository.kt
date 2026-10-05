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

class AnimeLibrarySnapshot(val files: List<NetworkFile> = emptyList(), val groups: Map<String, AnimeVideoGroup> = emptyMap(), val playbackIdentities: Map<String, String> = emptyMap(), val loaded: Boolean = false, val featuredKey: String? = null)

data class AnimeMatchProgress(val running: Boolean = false, val current: String? = null, val completed: Int = 0, val total: Int = 0)

data class AnimeCatalog(val folders: Map<String, AnimeFolderEntity>, val subjects: Map<Long, AnimeSubject>, val loaded: Boolean = true)

/** A separate queue keeps public metadata requests out of the playback/thumbnail queue. */
class AnimeRepository(private val dao: AnimeDao, private val recentDao: app.gyrolet.mpvrx.database.dao.RecentlyPlayedDao, client: OkHttpClient, context: android.content.Context,
  private val preferences: app.gyrolet.mpvrx.preferences.AdvancedPreferences) {
  private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
  private val artwork = app.gyrolet.mpvrx.domain.cloud.TmdbArtworkClient(http)
  private val artworkAttempts = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, Long>()
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val recentHistory = recentDao.observeRecentlyPlayed(1000).shareIn(scope, SharingStarted.Eagerly, replay = 1)
  private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
  private data class PendingMatch(val files: List<NetworkFile>, val retryUnmatched: Boolean)
  private val pending = java.util.concurrent.ConcurrentHashMap<Long, PendingMatch>()
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
  private val creditRelations = AnimeCreditRelations(java.io.File(context.filesDir, "anime/credit-relations.json")) { requestJson(it) }
  suspend fun cachedCreditSubjects(credit: AnimeCreditSelection): Set<Long> = creditRelations.cached(credit)
  suspend fun creditSubjects(credit: AnimeCreditSelection, force: Boolean = false): Set<Long> = creditRelations.resolve(credit, force)

  private val decodedSubjects = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, AnimeSubject>>()
  private val libraries = java.util.concurrent.ConcurrentHashMap<Pair<Long, String>, StateFlow<AnimeLibrarySnapshot>>()
  fun observeLibrary(id: Long, path: String, source: Flow<List<NetworkFile>>): StateFlow<AnimeLibrarySnapshot> = libraries.getOrPut(id to path) {
    var structureKey: List<Pair<String, String>>? = null
    var structure = emptyList<AnimeVideoGroup>()
    var identities = emptyMap<String, String>()
    combine(source, dao.observeFolders(id), recentHistory) { files, folders, recent ->
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
      val presented = animeMergeSubjectGroups(animeSplitGroups(structure, splitBindings(folders)),
        folders.associate { it.path to it.subjectId }, folders.associate { it.path to it.episodeOffset })
      val recentPaths = recent.mapNotNull { app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri.parse(it.filePath)?.takeIf { reference -> reference.connectionId == id }?.path?.value }
      AnimeLibrarySnapshot(files, presented.associate { group -> group.key to group.copy(files = group.files.mapNotNull { latest[it.path] }) }, identities, loaded = true, featuredKey = animeFeaturedGroup(presented, recentPaths))
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), AnimeLibrarySnapshot())
  }

  private val catalogs = java.util.concurrent.ConcurrentHashMap<Long, StateFlow<AnimeCatalog>>()
  fun observe(id: Long): StateFlow<AnimeCatalog> = catalogs.getOrPut(id) {
    combine(dao.observeFolders(id), dao.observeSubjects()) { folders, subjects ->
    AnimeCatalog(folders.filter { it.manual || it.query.startsWith("directory-v3 | ") || it.parentPath != null }.associateBy { it.path }, subjects.mapNotNull { row ->
      runCatching {
        val decoded = decodedSubjects[row.id]?.takeIf { it.first == row.payload }?.second
          ?: json.decodeFromString<AnimeSubject>(row.payload).also { decodedSubjects[row.id] = row.payload to it }
        row.id to decoded
      }.getOrNull()
    }.toMap())
  }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), AnimeCatalog(emptyMap(), emptyMap(), loaded = false))
  }

  private val warmJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

  /** Read saved data while the storage list is visible; warming never triggers network scraping. */
  @Synchronized
  fun prepareLibrary(id: Long, source: Flow<List<NetworkFile>>) {
    if (libraries[id to "/"]?.value?.loaded == true || warmJobs[id]?.isActive == true) return
    warmJobs[id] = scope.launch {
      val snapshot = observeLibrary(id, "/", source).first { it.loaded }
      observe(id).first { it.loaded }
      CloudTrace.event("anime.cache.warm", id, "/", "videos=${snapshot.files.size} groups=${snapshot.groups.size}")
    }
  }

  private val progress = java.util.concurrent.ConcurrentHashMap<Long, MutableStateFlow<AnimeMatchProgress>>()
  fun observeProgress(id: Long): StateFlow<AnimeMatchProgress> = progress.getOrPut(id) { MutableStateFlow(AnimeMatchProgress()) }
  private fun publishProgress(id: Long, value: AnimeMatchProgress) { progress.getOrPut(id) { MutableStateFlow(AnimeMatchProgress()) }.value = value }

  fun stop(id: Long) {
    pending.remove(id)
    jobs.remove(id)?.cancel()
    scheduledRevisions.remove(id)
    progress[id]?.update { it.copy(running = false, current = null) }
  }

  @Synchronized
  fun schedule(id: Long, files: List<NetworkFile>, retryUnmatched: Boolean = false) {
    val revision = files.filterNot { it.isDirectory }.fold(1) { hash, file -> 31 * hash + listOf(file.path, file.size, file.lastModified).hashCode() }
    val now = System.currentTimeMillis()
    val scheduled = scheduledRevisions[id]
    if (jobs[id]?.isActive == true) {
      if (retryUnmatched) CloudTrace.event("anime.retry.queued", id, "/", "videos=${files.size}")
      pending[id] = PendingMatch(files, retryUnmatched || pending[id]?.retryUnmatched == true)
      return
    }
    if (!retryUnmatched && scheduled?.first == revision && now - scheduled.second < 5 * 60_000) return
    scheduledRevisions[id] = revision to now
    jobs[id] = scope.launch {
      dao.clearAssignments(id)
      val groups = withContext(Dispatchers.Default) { animeVideoGroups(files) }
      val saved = dao.observeFolders(id).first().associateBy { it.path }.toMutableMap()
      val upgrades = mutableListOf<AnimeFolderEntity>()
      for (group in groups) {
        val previous = saved[group.key] ?: continue
        val base = animeMatchQuery(group).removeSuffix(" | aliases-v10")
        if (!previous.manual && previous.subjectId != null && previous.query in listOf(base, base + " | aliases-v2", base + " | aliases-v3", base + " | aliases-v4", base + " | aliases-v5", base + " | aliases-v6", base + " | aliases-v7", base + " | aliases-v8", base + " | aliases-v9")) {
          val updated = previous.copy(query = animeMatchQuery(group))
          upgrades.add(updated)
          saved[group.key] = updated
        }
      }
      if (upgrades.isNotEmpty()) associations.withLock { dao.bindAutomatically(upgrades) }
      val splits = splitBindings(saved.values.toList())
      val work = groups.filter { group ->
        if (animeSplitGroups(listOf(group), splits).firstOrNull()?.key != group.key) return@filter false
        val previous = saved[group.key]
        if (previous?.manual == true && previous.subjectId != null && animePartRegularFiles(group).size in 2..4 && observe(id).value.subjects[previous.subjectId]?.name?.let(::animePartBase) != null) return@filter true
        if (previous?.query?.endsWith(" | split") == true) return@filter true
        animeNeedsMatching(previous?.query, previous?.subjectId, previous?.manual == true,
          previous?.attemptedAt ?: 0, animeMatchQuery(group), System.currentTimeMillis(), retryUnmatched)
      }
      CloudTrace.event("anime.queue", id, "/", "groups=${groups.size} pending=${work.size} retryUnmatched=$retryUnmatched")
      if (work.isEmpty()) { reconcileSourceOffsets(id, groups, saved); publishProgress(id, AnimeMatchProgress()); return@launch }
      publishProgress(id, AnimeMatchProgress(running = true, total = work.size))
      work.forEachIndexed { position, group ->
        publishProgress(id, AnimeMatchProgress(true, group.key, position, work.size))
        ensureActive()
        val path = group.key
        val query = animeMatchQuery(group)
        val previous = saved[path]
        val forceLookup = retryUnmatched && previous?.subjectId == null && previous?.manual != true
        try {
          val index = aliasIndex()
          val aliasId = index[animeNameKey(group.query)]?.singleOrNull()
          var parts = emptyMap<Int, AnimeSubject>()
          var matchedNative: String? = null
          var mappedOffset: Int? = null
          var aliasError: Exception? = null
          var match = previous?.takeIf { it.manual }?.subjectId?.let { subject(it) } ?: aliasId?.let { subject(it, forceRefresh = false) }
          if (previous?.manual == true && match != null) {
            animePartBase(match.name)?.let { base ->
              matchBangumi(base, false)
              val candidates = matchSplitSubjects(base, group)
              if (candidates.values.any { it.id == match?.id }) parts = candidates
            }
          }
          if (match == null) {
            for (name in group.queries.take(4)) {
              match = index[animeNameKey(name)]?.singleOrNull()?.let { subject(it, forceRefresh = false) }
                ?: matchBangumi(name, forceLookup)
              if (match != null) break
              parts = matchSplitSubjects(name, group, forceLookup)
              if (parts.isNotEmpty()) break
              try {
                for (native in titleResolver.nativeQueries(name, forceLookup, animePartRegularFiles(group).size)) {
                  matchedNative = native
                  match = index[animeNameKey(native)]?.singleOrNull()?.let { subject(it, forceRefresh = false) }
                    ?: matchBangumi(native, forceLookup)
                  if (match != null) break
                  parts = matchSplitSubjects(native, group, forceLookup)
                  if (parts.isNotEmpty()) break
                }
              } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { aliasError = error; CloudTrace.event("anime.alias.failed", id, path, "error=${error.javaClass.simpleName}") }
              if (match != null || parts.isNotEmpty()) break
            }
          }
          if (match == null && parts.isEmpty()) {
            for (term in animeEventTerms(group.query)) {
              val event = matchAnimeEvent(group.query, search(term, forceLookup, 6))
              if (event != null) { match = event; break }
            }
          }
          if (match == null && parts.isEmpty() && animeEventTerms(group.query).isEmpty()) {
            for (source in titleResolver.sourceQueries(group.query, forceLookup)) {
              val adapted = matchAdaptation(source, group, forceLookup) ?: continue
              match = adapted.first; mappedOffset = adapted.second; break
            }
          }
          if (match == null && parts.isEmpty() && aliasError != null) throw aliasError
          if (match != null) {
            match = subject(match!!.id)
            matchedNative?.let { native ->
              if (animeCompoundChapters(match!!.name).any { animeIdentityKey(it) == animeIdentityKey(native) }) {
                mappedOffset = animeChapterEpisodeOffset(native, group, match!!)
                if (mappedOffset == null) match = null
              }
            }
          }
          associations.withLock {
            if (parts.isEmpty()) {
              val row = AnimeFolderEntity(id, path, query, match?.id, false, mappedOffset ?: 0, System.currentTimeMillis())
              dao.bindAutomatically(row); saved[path] = row
            }
            else {
              val rows = parts.map { (number, subject) ->
                AnimeFolderEntity(id, "$path#bangumi:${subject.id}", subject.title, subject.id, false, requireNotNull(animePartEpisodeOffset(number, subject)), System.currentTimeMillis(), parentPath = path, partNumber = number)
              } + if (previous?.manual == true) emptyList() else listOf(AnimeFolderEntity(id, path, query + " | split", null, false, 0, System.currentTimeMillis()))
              dao.bindAutomatically(rows)
            }
          }
          CloudTrace.event("anime.match", id, path, "extras=${group.files.size - animePartRegularFiles(group).size} matched=${match != null || parts.isNotEmpty()} parts=${parts.size}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
          associations.withLock { dao.bindAutomatically(AnimeFolderEntity(id, path, query + " | request-failed", previous?.takeIf { it.query.removeSuffix(" | request-failed") == query }?.subjectId, false, previous?.episodeOffset ?: 0, System.currentTimeMillis())) }
          CloudTrace.event("anime.failed", id, path, "error=${error.javaClass.simpleName}")
        }
      }
      reconcileSourceOffsets(id, groups, saved)
      publishProgress(id, AnimeMatchProgress(completed = work.size, total = work.size))
    }.also { job ->
      job.invokeOnCompletion {
        if (jobs.remove(id, job)) {
          progress[id]?.update { it.copy(running = false, current = null) }
          pending.remove(id)?.let { schedule(id, it.files, it.retryUnmatched) }
        }
      }
    }
  }

  private fun splitBindings(folders: List<AnimeFolderEntity>): Map<String, AnimePartBinding> = folders.mapNotNull { row ->
    if (row.subjectId == null || row.parentPath == null || row.partNumber == null) null
    else row.path to AnimePartBinding(row.parentPath, row.partNumber, row.query)
  }.toMap()

  private suspend fun matchSplitSubjects(query: String, group: AnimeVideoGroup, force: Boolean = false): Map<Int, AnimeSubject> {
    val regular = animePartRegularFiles(group)
    if (regular.size !in 2..4) return emptyMap()
    val candidates = animeDiscoveryTerms(query).take(3).flatMap { term -> search(term, force).map { candidateCores[it.id] ?: it } }.distinctBy { it.id }
    val parts = animeNumberedSubjects(query, candidates)
    if (parts.isEmpty()) return emptyMap()
    val byNumber = regular.map { parseAnimeFilename(it.name).takeIf { parsed -> !parsed.special }?.episode }
    if (byNumber.toSet() != parts.keys.map { it.toDouble() }.toSet()) return emptyMap()
    val detailed = parts.mapValues { subject(it.value.id) }
    if (detailed.any { (number, subject) -> animePartEpisodeOffset(number, subject) == null }) return emptyMap()
    return detailed
  }

  private val candidateCores = java.util.concurrent.ConcurrentHashMap<Long, AnimeSubject>()
  private suspend fun matchBangumi(query: String, force: Boolean): AnimeSubject? {
    val candidates = mutableMapOf<Long, AnimeSubject>()
    for (term in animeDiscoveryTerms(query).take(3)) {
      val found = search(term, force)
      matchAnimeSubject(query, found)?.let { return it }
      for (candidate in found.take(6)) {
        val core = if (!force) candidateCores[candidate.id] else null
        val detailed = core ?: dao.getSubject(candidate.id)?.let { row ->
          runCatching { json.decodeFromString<AnimeSubject>(row.payload) }.getOrNull()?.takeIf { it.format.isNotBlank() }
        } ?: decodeSubject(request("/v0/subjects/${candidate.id}")).also { candidateCores[candidate.id] = it }
        candidates[detailed.id] = detailed
      }
      (matchAnimeSubject(query, candidates.values.toList()) ?: matchAnimeChapter(query, candidates.values.toList()))?.let { return it }
    }
    return null
  }

  private val sourceAdaptations = java.util.concurrent.ConcurrentHashMap<Long, List<Long>>()
  private suspend fun matchAdaptation(source: String, group: AnimeVideoGroup, force: Boolean): Pair<AnimeSubject, Int>? {
    val book = matchAnimeSubject(source, search(source, force, 1).filter { it.subjectType == 1 }) ?: return null
    val targets = sourceAdaptations[book.id] ?: requestJson("/v0/subjects/${book.id}/subjects").jsonArray.mapNotNull { value ->
      val row = value.jsonObject
      row.long("id").takeIf { row.long("type") == 2L && row.string("relation") == "动画" }
    }.distinct().also { sourceAdaptations[book.id] = it }
    val target = targets.singleOrNull()?.let { subject(it) } ?: return null
    val offset = animeSourceEpisodeOffset(source, group, target) ?: return null
    return target to offset
  }

  private suspend fun reconcileSourceOffsets(id: Long, groups: List<AnimeVideoGroup>, saved: MutableMap<String, AnimeFolderEntity>) {
    val shared = groups.mapNotNull { saved[it.key]?.subjectId }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    for (group in groups) {
      val row = saved[group.key]?.takeIf { !it.manual && it.subjectId in shared } ?: continue
      try {
        val selected = subject(requireNotNull(row.subjectId))
        val sourceTitles = selected.episodes.mapNotNull { episode -> episode.originalTitle.substringBefore(" 第", "").takeIf { it.isNotBlank() } }.distinct()
        if (sourceTitles.size < 2) continue
        for (source in titleResolver.sourceQueries(group.query)) {
          if (sourceTitles.none { animeNameKey(it) == animeNameKey(source) }) continue
          val offset = animeSourceEpisodeOffset(source, group, selected) ?: continue
          if (offset != row.episodeOffset) {
            val updated = row.copy(episodeOffset = offset)
            associations.withLock { dao.bindAutomatically(updated) }; saved[group.key] = updated
            CloudTrace.event("anime.source.offset", id, group.key, "subject=${selected.id} offset=$offset")
          }
          break
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { CloudTrace.event("anime.mapping.failed", id, group.key, "error=${error.javaClass.simpleName}") }
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

  suspend fun search(query: String, force: Boolean = false, subjectType: Int = 2): List<AnimeSubject> = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext emptyList()
    searchLock.withLock {
      val rows = searchCache ?: runCatching { json.parseToJsonElement(searchFile.readText()).jsonObject.toMutableMap() }
        .getOrDefault(mutableMapOf()).also { searchCache = it }
      val key = if (subjectType == 2) animeNameKey(query) else "$subjectType|${animeNameKey(query)}"
      val now = System.currentTimeMillis()
      rows[key]?.jsonObject?.let { cached ->
        val data = cached["data"]?.jsonArray.orEmpty()
        val ttl = if (data.isEmpty()) 24 * 60 * 60_000L else 7 * 24 * 60 * 60_000L
        if (!force && now - cached.long("updatedAt") < ttl) return@withLock data.map { decodeSubject(it.jsonObject) }
      }
      val body = buildJsonObject {
        put("keyword", query); put("sort", "match")
        putJsonObject("filter") { putJsonArray("type") { add(subjectType) } }
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

  fun scheduleArtwork(id: Long) {
    val credential = preferences.tmdbArtworkToken.get().trim().ifBlank { preferences.tmdbArtworkApiKey.get().trim() }
    if (credential.isBlank()) return
    scope.launch {
      val lock = subjectLocks.getOrPut(id) { Mutex() }
      val item = lock.withLock {
        val cached = dao.getSubject(id) ?: return@withLock null
        val item = json.decodeFromString<AnimeSubject>(cached.payload)
        val now = System.currentTimeMillis()
        val ttl = if (item.titleLogo.isNotBlank()) 30L * 24 * 60 * 60_000 else 24 * 60 * 60_000L
        if (item.artworkSchemaVersion == 1 && now - item.artworkFetchedAt < ttl) return@withLock null
        val attempt = id to credential.hashCode()
        if (now - (artworkAttempts[attempt] ?: 0) < 5 * 60_000L) return@withLock null
        artworkAttempts[attempt] = now
        item
      } ?: return@launch
      try {
        val result = artwork.fetch(item, credential)
        lock.withLock {
          val cached = dao.getSubject(id) ?: return@withLock
          val latest = json.decodeFromString<AnimeSubject>(cached.payload)
          val enriched = latest.copy(titleLogo = result?.logo.orEmpty(),
            tmdbId = result?.id ?: 0, artworkFetchedAt = System.currentTimeMillis(),
            artworkCover = result?.cover.orEmpty(), artworkBackdrop = result?.backdrop.orEmpty(), artworkSchemaVersion = 1)
          dao.putSubject(cached.copy(payload = json.encodeToString(AnimeSubject.serializer(), enriched)))
          CloudTrace.event("anime.artwork", 0, id.toString(), "tmdb=${enriched.tmdbId} logo=${enriched.titleLogo.isNotBlank()}")
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { CloudTrace.event("anime.artwork.failed", 0, id.toString(), "error=${error.javaClass.simpleName}") }
    }
  }

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
      runCatching { json.decodeFromString<AnimeSubject>(cached.payload) }.getOrNull()?.takeIf { it.episodeSchemaVersion >= 1 }?.let { return it }
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
            row.long("type").toInt(), row.string("name_cn").ifBlank { row.string("name") }, row.string("name")))
        }
        offset += rows.size
      } while (rows.isNotEmpty() && offset < page.long("total") && offset < 2000)
      val previous = cached?.let { runCatching { json.decodeFromString<AnimeSubject>(it.payload) }.getOrNull() }
      val result = item.copy(episodes = episodes, cast = previous?.cast.orEmpty(), staff = previous?.staff.orEmpty(), creditsFetchedAt = previous?.creditsFetchedAt ?: 0, episodeSchemaVersion = 1,
        titleLogo = previous?.titleLogo.orEmpty(), tmdbId = previous?.tmdbId ?: 0, artworkFetchedAt = previous?.artworkFetchedAt ?: 0,
        artworkCover = previous?.artworkCover.orEmpty(), artworkBackdrop = previous?.artworkBackdrop.orEmpty(),
        artworkSchemaVersion = previous?.artworkSchemaVersion ?: 0)
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
      val previous = dao.getFolder(id, path)
      dao.putFolder(AnimeFolderEntity(id, path, item.title, subjectId, true, offset, System.currentTimeMillis(), parentPath = previous?.parentPath, partNumber = previous?.partNumber))
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
    row["tags"]?.jsonArray.orEmpty().take(5).map { it.jsonObject.string("name") },
    aliases = row["infobox"]?.jsonArray.orEmpty().filter { it.jsonObject.string("key") == "别名" }.flatMap { field ->
      when (val value = field.jsonObject["value"]) {
        is JsonArray -> value.mapNotNull { it.jsonObject["v"]?.jsonPrimitive?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        else -> emptyList()
      }
    }, format = row.string("platform"), subjectType = row.long("type").toInt().takeIf { it > 0 } ?: 2)
  private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
  private fun JsonObject.long(key: String) = get(key)?.jsonPrimitive?.longOrNull ?: 0L
}
