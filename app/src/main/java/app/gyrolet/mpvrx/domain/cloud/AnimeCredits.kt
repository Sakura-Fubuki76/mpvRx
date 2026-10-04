package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.*

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.id() = (get("id") as? JsonPrimitive)?.longOrNull ?: 0
private fun JsonObject.image(): String {
  val images = get("images") as? JsonObject ?: return ""
  return images.text("medium").ifBlank { images.text("large") }.ifBlank { images.text("small") }
}
private fun person(row: JsonObject) = AnimePerson(row.id(), row.text("name"), row.image(), row.text("relation"))

fun decodeAnimeCast(rows: JsonArray): List<AnimeCast> = rows.mapNotNull { value ->
  val row = value as? JsonObject ?: return@mapNotNull null
  AnimeCast(row.id(), row.text("name"), row.image(), row.text("relation"),
    (row["actors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::person) }.distinctBy { it.id })
}.distinctBy { it.id }

fun decodeAnimeStaff(rows: JsonArray): List<AnimePerson> = rows.mapNotNull { (it as? JsonObject)?.let(::person) }
  .groupBy { it.id }.values.map { roles -> roles.first().copy(role = roles.map { it.role }.filter { it.isNotBlank() }.distinct().joinToString(" · ")) }
