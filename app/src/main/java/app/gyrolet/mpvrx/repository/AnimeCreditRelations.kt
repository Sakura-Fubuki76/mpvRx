package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.domain.cloud.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Public provider relations are independent of storage paths and matching/indexing jobs. */
internal class AnimeCreditRelations(private val file: File, private val request: suspend (String) -> JsonElement) {
  private val lock = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private fun read(): JsonObject = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
  private fun key(credit: AnimeCreditSelection) = "${credit.kind}:${credit.id}"
  private fun entry(entries: JsonObject, credit: AnimeCreditSelection): JsonObject? = runCatching {
    entries[key(credit)]?.jsonObject?.takeIf { saved ->
      saved["at"]?.jsonPrimitive?.longOrNull != null && saved["subjects"] is JsonArray
    }
  }.getOrNull()
  suspend fun cached(credit: AnimeCreditSelection): Set<Long> = withContext(Dispatchers.IO) {
    lock.withLock { entry(read(), credit)?.get("subjects")?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }?.toSet().orEmpty() }
  }
  suspend fun resolve(credit: AnimeCreditSelection, force: Boolean = false): Set<Long> = withContext(Dispatchers.IO) {
    lock.withLock {
      val entries = read().toMutableMap()
      val saved = entry(JsonObject(entries), credit)
      val now = System.currentTimeMillis()
      if (!force && saved != null && now - (saved["at"]?.jsonPrimitive?.longOrNull ?: 0) < 7 * 24 * 60 * 60_000L) {
        return@withLock saved.getValue("subjects").jsonArray.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.toSet()
      }
      val path = when (credit.kind) {
        AnimeCreditKind.VOICE -> "/v0/persons/${credit.id}/characters"
        AnimeCreditKind.CHARACTER -> "/v0/characters/${credit.id}/subjects"
        AnimeCreditKind.STAFF -> "/v0/persons/${credit.id}/subjects"
      }
      val ids = decodeAnimeCreditSubjects(request(path).jsonArray, credit.kind)
      entries[key(credit)] = buildJsonObject {
        put("at", now)
        put("subjects", JsonArray(ids.sorted().map(::JsonPrimitive)))
      }
      file.parentFile?.mkdirs()
      val temporary = File(file.parentFile, "${file.name}.tmp")
      temporary.writeText(JsonObject(entries).toString())
      java.nio.file.Files.move(temporary.toPath(), file.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
      ids
    }
  }
}
