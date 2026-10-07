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
  /** Move the bank out of mpv's automatic config/fonts scan, preserving every source file. */
  @Synchronized fun migrateLegacyBank(context: Context) {
    relocateLegacyFontBank(context.filesDir)?.let { count ->
      CloudTrace.event("fonts.bank.migrated", detail = "files=$count")
    }
  }

  private val lock = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  @Serializable private data class SourceFont(val uri: String, val name: String, val size: Long, val modified: Long, val bankName: String = "")
  @Serializable private data class Sources(val roots: List<String>, val checkedAt: Long, val files: List<SourceFont>, val traversalVersion: Int = 0)
  @Serializable private data class Entry(val name: String, val size: Long, val modified: Long, val families: Set<String>, val decodingVersion: Int = 0)

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
      val bank = File(context.filesDir, "font-bank")
      val manifest = File(context.filesDir, "font-names.json")
      val old = runCatching { json.decodeFromString<List<Entry>>(manifest.readText()) }.getOrDefault(emptyList()).filter { it.decodingVersion == 2 }.associateBy { it.name }
      bank.mkdirs()
      val requestedNames = families.filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("sans-serif", "serif", "monospace") }
      val bankFiles = fontBankFiles(bank)
      val missing = requestedNames.filter { family -> bankFiles.none { file ->
        AssFontNames.matches(file.name, family) || (old[file.relativeTo(bank).invariantSeparatorsPath]?.families ?: FontNameReader.names(file)).any { it.equals(family.removePrefix("@"), true) }
      } }.toSet()
      if (missing.isNotEmpty()) importRequestedFonts(context, bank, missing, allowSourceScan)
      val entries = fontBankFiles(bank).map { file ->
        old[file.relativeTo(bank).invariantSeparatorsPath]?.takeIf { it.size == file.length() && it.modified == file.lastModified() }
          ?: Entry(file.relativeTo(bank).invariantSeparatorsPath, file.length(), file.lastModified(), FontNameReader.names(file), decodingVersion = 2)
      }
      if (entries != old.values.toList()) {
        writeFontIndex(context, entries)
      }
      for (family in requestedNames) {
        val found = entries.count { entry -> AssFontNames.matches(File(entry.name).name, family) || entry.families.any { it.equals(family.removePrefix("@"), true) } }
        CloudTrace.event("fonts.family", detail = "family=${family.replace('\n', ' ').replace('\r', ' ').take(96)} matches=$found")
      }
      val active = directory(context, mediaId).apply { mkdirs() }
      if (reset) active.listFiles()?.forEach { it.delete() }
      val requested = families.map { it.trim().removePrefix("@").lowercase(Locale.ROOT) }.toSet()
      val matches = entries.filter { entry -> requested.any { family ->
        AssFontNames.matches(File(entry.name).name, family) || entry.families.any { it.lowercase(Locale.ROOT) == family }
      } }
      for (entry in matches) {
        val source = File(bank, entry.name)
        val target = File(active, fontStorageName(entry.name))
        if (java.nio.file.Files.isSymbolicLink(source.toPath()) && linkReadableFont(source, target)) continue
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
    val fresh = cached != null && cached.traversalVersion == 3 && cached.roots == roots && System.currentTimeMillis() - cached.checkedAt in 0..86400000L
    if (!fresh && !allowSourceScan) return
    val catalog = if (fresh) cached!! else {
      val files = mutableListOf<SourceFont>()
      val seen = hashSetOf<String>()
      var complete = true
      suspend fun visit(tree: Uri, documentId: String) {
        val pending = java.util.ArrayDeque<String>().apply { add(documentId) }
        while (pending.isNotEmpty()) {
          kotlinx.coroutines.currentCoroutineContext().ensureActive()
          val parentId = pending.removeLast()
          if (!seen.add("$tree|$parentId")) continue
          val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
          val columns = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified")
          try {
            val cursor = context.contentResolver.query(children, columns, null, null, null)
            if (cursor == null) { complete = false; continue }
            cursor.use {
              while (it.moveToNext()) {
                val id = it.getString(0)
                val name = it.getString(1) ?: continue
                if (it.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) pending.add(id)
                else if (isFontFile(name)) files += SourceFont(
                  android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, it.getLong(3), it.getLong(4),
                  fontStorageName(id.removePrefix("$documentId/")))
              }
            }
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (error: Exception) {
            complete = false
            CloudTrace.event("fonts.source.failed", detail = "error=${error.javaClass.simpleName}")
          }
        }
      }
      for (root in roots) {
        try {
          val uri = Uri.parse(root)
          val document = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri) ?: continue
          val selected = if (root == advanced.mpvConfStorageUri.get())
            document.listFiles().firstOrNull { it.isDirectory && it.name.equals("fonts", true) } ?: continue
          else document
          visit(uri, android.provider.DocumentsContract.getDocumentId(selected.uri))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { complete = false /* Keep existing bank and retry the incomplete catalog later. */ }
      }
      Sources(roots, System.currentTimeMillis(), files, traversalVersion = 3).also {
        if (complete) {
          val pending = java.nio.file.Files.createTempFile(context.filesDir.toPath(), ".font-sources-", ".tmp")
          try {
            pending.toFile().writeText(json.encodeToString(it))
            java.nio.file.Files.move(pending, catalogFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          } finally { java.nio.file.Files.deleteIfExists(pending) }
        }
        CloudTrace.event("fonts.source.scanned", detail = "directories=${seen.size} files=${files.size} complete=$complete")
      }
    }
    var relinked = 0
    for (font in if (allowSourceScan) catalog.files else emptyList()) {
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      if (font.name.contains('/') || font.name.contains('\\')) continue
      // Convert only aliases already imported by older builds. New, unrequested fonts stay outside the bank.
      for (name in listOf(font.bankName, fontStorageName("${font.uri}/${font.name}")).filter(String::isNotBlank).distinct()) {
        val target = File(bank, name)
        if (target.isFile && !java.nio.file.Files.isSymbolicLink(target.toPath()) &&
            FontDocumentStorage.linkExisting(context, Uri.parse(font.uri), target)) relinked++
      }
    }
    if (relinked > 0) {
      FontDocumentStorage.relinkActive(context)
      CloudTrace.event("fonts.bank.linked", detail = "files=$relinked")
    }
    for (font in catalog.files.filter { font -> names.any { AssFontNames.matches(font.name, it) } }) {
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      if (font.name.contains('/') || font.name.contains('\\')) continue
      val target = File(bank, fontStorageName("${font.uri}/${font.name}"))
      FontDocumentStorage.store(context, Uri.parse(font.uri), target, font.size, font.modified)
    }
  }

  suspend fun prewarmSources(context: Context) = withContext(Dispatchers.IO) {
    // Source traversal never holds the font selection lock and never gates subtitle registration.
    importRequestedFonts(context, File(context.filesDir, "font-bank"), emptySet(), allowSourceScan = true)
    // Name-table parsing belongs to background warm-up after replacing legacy copies with links.
    val bank = File(context.filesDir, "font-bank")
    val old = runCatching { json.decodeFromString<List<Entry>>(File(context.filesDir, "font-names.json").readText()) }
      .getOrDefault(emptyList()).filter { it.decodingVersion == 2 }.associateBy { it.name }
    val entries = fontBankFiles(bank).map { file ->
      val name = file.relativeTo(bank).invariantSeparatorsPath
      old[name]?.takeIf { it.size == file.length() && it.modified == file.lastModified() }
        ?: Entry(name, file.length(), file.lastModified(), FontNameReader.names(file), decodingVersion = 2)
    }
    if (entries != old.values.toList()) writeFontIndex(context, entries)
  }

  private fun writeFontIndex(context: Context, entries: List<Entry>) {
    val pending = java.nio.file.Files.createTempFile(context.filesDir.toPath(), ".font-names-", ".tmp")
    try {
      pending.toFile().writeText(json.encodeToString(entries))
      java.nio.file.Files.move(pending, File(context.filesDir, "font-names.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { java.nio.file.Files.deleteIfExists(pending) }
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
