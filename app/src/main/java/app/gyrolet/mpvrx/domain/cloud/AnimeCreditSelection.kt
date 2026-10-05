package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class AnimeCreditKind { VOICE, CHARACTER, STAFF }

@Serializable
data class AnimeCreditSelection(val kind: AnimeCreditKind, val id: Long, val name: String, val image: String = "")

fun animeCreditMatches(subject: AnimeSubject, credit: AnimeCreditSelection): Boolean = when (credit.kind) {
  AnimeCreditKind.VOICE -> subject.cast.any { character -> character.actors.any { it.id == credit.id } }
  AnimeCreditKind.CHARACTER -> subject.cast.any { it.id == credit.id }
  AnimeCreditKind.STAFF -> subject.staff.any { it.id == credit.id }
}

/** Person/characters describes voice work; person/subjects describes production credits. */
fun decodeAnimeCreditSubjects(rows: JsonArray, kind: AnimeCreditKind): Set<Long> = rows.mapNotNull { element ->
  val row = element as? JsonObject ?: return@mapNotNull null
  val typeKey = if (kind == AnimeCreditKind.VOICE) "subject_type" else "type"
  val idKey = if (kind == AnimeCreditKind.VOICE) "subject_id" else "id"
  if ((row[typeKey] as? JsonPrimitive)?.intOrNull !in setOf(2, 6)) return@mapNotNull null
  (row[idKey] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
}.toSet()
