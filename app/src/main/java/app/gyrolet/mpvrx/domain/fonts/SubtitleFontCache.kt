package app.gyrolet.mpvrx.domain.fonts

import android.content.Context
import android.net.Uri
import android.system.Os
import app.gyrolet.mpvrx.domain.cloud.CloudTrace
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Keep the font bank, but expose only fonts requested by this media's ASS styles to libass. */
object SubtitleFontCache {
  private val lock = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private val extensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
  @Serializable private data class SourceFont(val uri: String, val name: String, val size: Long, val modified: Long)
  @Serializable private data class Sources(val roots: List<String>, val checkedAt: Long, val files: List<SourceFont>)
  @Serializable private data class Entry(val name: String, val size: Long, val modified: Long, val families: Set<String>)

  fun directory(context: Context, mediaId: String): File {
    val key = MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray())
      .joinToString("") { "%02x".format(it) }
    return File(context.filesDir, "fonts-active/$key")
  }

  suspend fun select(context: Context, mediaId: String, families: Set<String>, reset: Boolean = false, allowSourceScan: Boolean = false): String =
    withContext(Dispatchers.IO) { lock.withLock {
      val start = System.nanoTime()
      if (families.none { it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("sans-serif", "serif", "monospace") }) {
        val active = directory(context, mediaId).apply { mkdirs() }
        if (reset) active.listFiles()?.forEach { it.delete() }
        CloudTrace.event("fonts.select", detail = "requested=${families.size} selected=0 genericOnly=true elapsedMs=${(System.nanoTime()-start)/1000000}")
        return@withLock active.path
      }
      val bank = File(context.filesDir, "fonts")
      val manifest = File(context.filesDir, "font-names.json")
      val old = runCatching { json.decodeFromString<List<Entry>>(manifest.readText()) }.getOrDefault(emptyList()).associateBy { it.name }
      bank.mkdirs()
      val requestedNames = families.filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("sans-serif", "serif", "monospace") }
      val bankFiles = bank.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in extensions }
      val missing = requestedNames.filter { family -> bankFiles.none { file ->
        AssFontNames.matches(file.name, family) || (old[file.name]?.families ?: FontNameReader.names(file)).any { it.equals(family.removePrefix("@"), true) }
      } }.toSet()
      if (missing.isNotEmpty()) importRequestedFonts(context, bank, missing, allowSourceScan)
      val entries = bank.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in extensions }.map { file ->
        old[file.name]?.takeIf { it.size == file.length() && it.modified == file.lastModified() }
          ?: Entry(file.name, file.length(), file.lastModified(), FontNameReader.names(file))
      }
      if (entries != old.values.toList()) {
        val pending = File(context.filesDir, "font-names.json.tmp")
        pending.writeText(json.encodeToString(entries))
        java.nio.file.Files.move(pending.toPath(), manifest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
      for (family in requestedNames) {
        val found = entries.count { entry -> AssFontNames.matches(entry.name, family) || entry.families.any { it.equals(family.removePrefix("@"), true) } }
        CloudTrace.event("fonts.family", detail = "family=${family.replace('\n', ' ').replace('\r', ' ').take(96)} matches=$found")
      }
      val active = directory(context, mediaId).apply { mkdirs() }
      if (reset) active.listFiles()?.forEach { it.delete() }
      val requested = families.map { it.trim().removePrefix("@").lowercase(Locale.ROOT) }.toSet()
      val matches = entries.filter { entry -> requested.any { family ->
        AssFontNames.matches(entry.name, family) || entry.families.any { it.lowercase(Locale.ROOT) == family }
      } }
      for (entry in matches) {
        val source = File(bank, entry.name)
        val target = File(active, entry.name)
        if (target.exists() && target.length() == entry.size && target.lastModified() == entry.modified) continue
        target.delete()
        runCatching { Os.link(source.path, target.path) }.getOrElse { source.copyTo(target, overwrite = true) }
      }
      CloudTrace.event("fonts.select", detail = "bank=${entries.size} requested=${families.size} selected=${matches.size} elapsedMs=${(System.nanoTime() - start)/1000000}")
      active.path
    } }

  private suspend fun importRequestedFonts(context: Context, bank: File, names: Set<String>, allowSourceScan: Boolean) {
    val subtitles = org.koin.java.KoinJavaComponent.get<app.gyrolet.mpvrx.preferences.SubtitlesPreferences>(app.gyrolet.mpvrx.preferences.SubtitlesPreferences::class.java)
    val advanced = org.koin.java.KoinJavaComponent.get<app.gyrolet.mpvrx.preferences.AdvancedPreferences>(app.gyrolet.mpvrx.preferences.AdvancedPreferences::class.java)
    val roots = listOf(subtitles.fontsFolder.get(), advanced.mpvConfStorageUri.get()).filter { it.isNotBlank() }.distinct()
    if (roots.isEmpty()) return
    val catalogFile = File(context.filesDir, "font-sources.json")
    val cached = runCatching { json.decodeFromString<Sources>(catalogFile.readText()) }.getOrNull()
    val fresh = cached != null && cached.roots == roots && System.currentTimeMillis() - cached.checkedAt in 0..86400000L
    if (!fresh && !allowSourceScan) return
    val catalog = if (fresh) cached!! else {
      val files = mutableListOf<SourceFont>()
      val seen = hashSetOf<String>()
      suspend fun visit(tree: Uri, documentId: String, depth: Int) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (depth > 64 || seen.size >= 5000 || !seen.add(documentId + tree)) return
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified")
        val directories = mutableListOf<String>()
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
          while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            val name = cursor.getString(1) ?: continue
            if (cursor.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) directories += id
            else if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions)
              files += SourceFont(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                name, cursor.getLong(3), cursor.getLong(4))
          }
        }
        for (id in directories) visit(tree, id, depth + 1)
      }
      for (root in roots) {
        try {
          val uri = Uri.parse(root)
          val document = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri) ?: continue
          val selected = if (root == advanced.mpvConfStorageUri.get())
            document.listFiles().firstOrNull { it.isDirectory && it.name.equals("fonts", true) } ?: continue
          else document
          visit(uri, android.provider.DocumentsContract.getDocumentId(selected.uri), 0)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve the previous bank when a permission/provider is unavailable. */ }
      }
      Sources(roots, System.currentTimeMillis(), files).also {
        catalogFile.writeText(json.encodeToString(it))
      }
    }
    for (font in catalog.files.filter { font -> names.any { AssFontNames.matches(font.name, it) } }) {
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      if (font.name.contains('/') || font.name.contains('\\')) continue
      val target = File(bank, font.name)
      if (target.exists() && target.length() == font.size && font.modified > 0 && target.lastModified() == font.modified) continue
      val pending = File(bank, ".${font.name}.tmp")
      try {
        context.contentResolver.openInputStream(Uri.parse(font.uri))?.use { input ->
          pending.outputStream().use { input.copyTo(it) }
        } ?: continue
        java.nio.file.Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        if (font.modified > 0) target.setLastModified(font.modified)
      } finally { pending.delete() }
    }
  }

  suspend fun prewarmSources(context: Context) = withContext(Dispatchers.IO) {
    // Source traversal never holds the font selection lock and never gates subtitle registration.
    importRequestedFonts(context, File(context.filesDir, "fonts"), emptySet(), allowSourceScan = true)
  }

  suspend fun prepareMedia(context: Context, item: PlaybackItem, preferredFamily: String, cachedOnly: Boolean = true, expectedGeneration: Long? = null): String {
    val prepareStart = System.nanoTime()
    CloudTrace.event("fonts.media.begin", detail = "cachedOnly=$cachedOnly parserCheck=${AssFontNames.parse("[V4+ Styles]\nStyle: Default,Arial\n[Events]\n{\\fnVerdana}") == setOf("Arial", "Verdana")}")
    val names = linkedSetOf(preferredFamily)
    val path = item.networkSource?.relativePath ?: item.originalUri
    if (app.gyrolet.mpvrx.domain.cloud.cloudMediaExtension(path) in setOf("mkv", "webm")) {
      withContext(Dispatchers.IO) {
        val streamId = "fonts_${java.util.UUID.randomUUID()}"
        val proxy = app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy.getInstance()
        var registered = false
        try {
          val client = app.gyrolet.mpvrx.network.SharedHttpClient.derive {
            callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
          }
          val extractor = app.gyrolet.mpvrx.domain.cloud.MkvKeyframeExtractor(client)
          val source = item.networkSource
          if (source != null) {
            val network = org.koin.java.KoinJavaComponent.get<app.gyrolet.mpvrx.repository.NetworkRepository>(app.gyrolet.mpvrx.repository.NetworkRepository::class.java)
            val dao = org.koin.java.KoinJavaComponent.get<app.gyrolet.mpvrx.database.dao.CloudMetadataDao>(app.gyrolet.mpvrx.database.dao.CloudMetadataDao::class.java)
            val connection = network.getConnectionById(source.connectionId)
            val entry = dao.getItem(source.connectionId, source.relativePath)
            val key = app.gyrolet.mpvrx.domain.cloud.cloudMediaKey(connection, source.relativePath, entry?.size ?: -1, entry?.lastModified ?: 0)
            app.gyrolet.mpvrx.domain.cloud.MoovIndexCache.ensureLoadedFromDisk(key)
            val cached = app.gyrolet.mpvrx.domain.cloud.MoovIndexCache.get(key)?.parsed?.assFontNames
            CloudTrace.event("fonts.media.index", source.connectionId, detail = "cached=${cached != null} referenced=${cached?.size ?: 0}")
            if (cached != null) names += cached
            else if (connection != null && !cachedOnly) {
              val url = proxy.registerStream(streamId, connection, source.relativePath, entry?.size ?: -1, "video/x-matroska")
              registered = true
              names += extractor.extractAssFontNames(url)
            }
          } else {
            val uri = Uri.parse(item.originalUri)
            val input = when (uri.scheme) {
              "content" -> context.contentResolver.openInputStream(uri)
              "file", null -> File(uri.path ?: item.originalUri).inputStream()
              else -> null
            }
            input?.use { names += extractor.parseAssFontsFromHeader(it.readNBytes(1024 * 1024)) }
          }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { CloudTrace.event("fonts.media.failed", detail = "error=${error.javaClass.simpleName}") }
        finally { if (registered) proxy.unregisterStream(streamId) }
      }
    }
    CloudTrace.event("fonts.media.probed", detail = "referenced=${names.size} elapsedMs=${(System.nanoTime()-prepareStart)/1000000}")
    if (expectedGeneration != null && PlaybackSession.state.value.generation != expectedGeneration) return directory(context, item.stableId).path
    return select(context, item.stableId, names, reset = cachedOnly).also {
      CloudTrace.event("fonts.media.end", detail = "elapsedMs=${(System.nanoTime()-prepareStart)/1000000}")
    }
  }

  suspend fun prepareExternal(context: Context, uri: String, expectedGeneration: Long? = null, fileName: String? = null) {
    val extension = (fileName ?: Uri.parse(uri).lastPathSegment.orEmpty()).substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension !in setOf("ass", "ssa")) return
    if (expectedGeneration != null && PlaybackSession.state.value.generation != expectedGeneration) return
    val item = PlaybackSession.state.value.currentItem ?: PlaybackSession.queue.value.currentItem ?: return
    val text = withContext(Dispatchers.IO) {
      runCatching {
        val parsed = Uri.parse(uri)
        val input = if (parsed.scheme == "content") context.contentResolver.openInputStream(parsed)
          else if (parsed.scheme == "file" || parsed.scheme == null) File(parsed.path ?: uri).inputStream()
          else if (parsed.scheme in setOf("http", "https") && parsed.host in setOf("127.0.0.1", "localhost")) {
            app.gyrolet.mpvrx.network.SharedHttpClient.derive { callTimeout(3, java.util.concurrent.TimeUnit.SECONDS) }
              .newCall(okhttp3.Request.Builder().url(uri).build()).execute().let { response ->
                try { return@runCatching response.body.byteStream().use { String(it.readNBytes(4 * 1024 * 1024), Charsets.UTF_8) } }
                finally { response.close() }
              }
          } else null
        input?.use { String(it.readNBytes(4 * 1024 * 1024), Charsets.UTF_8) }
      }.getOrNull()
    } ?: return
    val names = AssFontNames.parse(text)
    CloudTrace.event("fonts.external", detail = "referenced=${names.size}")
    if (names.isEmpty()) return
    val path = select(context, item.stableId, names)
    if (expectedGeneration == null || PlaybackSession.state.value.generation == expectedGeneration) {
      PlaybackSession.setPropertyString("sub-fonts-dir", path)
      CloudTrace.event("fonts.applied", detail = "files=${File(path).listFiles()?.size ?: 0} assOverride=${PlaybackSession.getPropertyString("sub-ass-override")}")
    }
  }
}
