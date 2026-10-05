package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class AnimeCreditKind { VOICE, CHARACTER, STAFF }

@Serializable
data class AnimeCreditSelection(val kind: AnimeCreditKind, val id: Long, val name: String, val image: String = "")

fun animeCreditMatches(subject: AnimeSubject, credit: AnimeCreditSelection): Boolean = when (credit.kind) {
  AnimeCreditKind.VOICE, AnimeCreditKind.STAFF -> subject.cast.any { character -> character.actors.any { it.id == credit.id } } || subject.staff.any { it.id == credit.id }
  AnimeCreditKind.CHARACTER -> subject.cast.any { it.id == credit.id }
}

/** Person/characters describes voice work; person/subjects describes production credits. */
fun decodeAnimeCreditSubjects(rows: JsonArray, kind: AnimeCreditKind): Set<Long> = rows.mapNotNull { element ->
  val row = element as? JsonObject ?: return@mapNotNull null
  val typeKey = if (kind == AnimeCreditKind.VOICE) "subject_type" else "type"
  val idKey = if (kind == AnimeCreditKind.VOICE) "subject_id" else "id"
  if ((row[typeKey] as? JsonPrimitive)?.intOrNull !in setOf(2, 6)) return@mapNotNull null
  (row[idKey] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
}.toSet()

/** Keep the work's main release, including main episodes from merged chapter folders. */
private val relatedExtraClip = Regex("(?i)(?:^|[\\s_\\-\\[\\(])(?:SP|SPECIAL|NCOP|NCED|OP|ED|MENU|PREVIEW|PV|CM|TRAILER|PROMO)(?:[\\s_\\-\\]\\).]|\\d|$)")
private val relatedOvaClip = Regex("(?i)(?:^|[\\s_\\-\\[\\(])(?:OVA|OAD)(?:[\\s_\\-\\]\\).]|\\d|$)")
fun animeRelatedMainVideos(group: AnimeVideoGroup, subject: AnimeSubject): List<app.gyrolet.mpvrx.domain.network.NetworkFile> {
  val standaloneOva = subject.format.uppercase(java.util.Locale.ROOT) in setOf("OVA", "OAD")
  return group.files.filter { file ->
    val direct = animeSectionPath(group.directory, file).isEmpty()
    val main = direct || file.path in group.regularPaths || parseAnimeFilename(file.name).let { it.episode != null && !it.special }
    val ova = relatedOvaClip.containsMatchIn(file.name)
    main && !relatedExtraClip.containsMatchIn(file.name) && ((!isAnimeExtraVideo(file) && !ova) ||
      (direct && standaloneOva && ova))
  }
}
