package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

class AnimeCreditSelectionTest {
  @Test fun voiceRelationsUseSubjectIdentityAndExcludeGames() {
    val rows = Json.parseToJsonElement("""[
      {"id":36488,"subject_id":150775,"subject_type":2},
      {"id":36488,"subject_id":150775,"subject_type":2},
      {"id":36488,"subject_id":35615,"subject_type":4},
      {"id":12,"subject_id":216792,"subject_type":6},
      {"id":20,"subject_id":-1,"subject_type":2},null,{}
    ]""").jsonArray
    assertEquals(setOf(150775L, 216792L), decodeAnimeCreditSubjects(rows, AnimeCreditKind.VOICE))
  }
  @Test fun characterAndStaffRelationsExcludeBooksAndMusic() {
    val rows = Json.parseToJsonElement("""[
      {"id":111275,"type":1},{"id":150775,"type":2},
      {"id":210095,"type":3},{"id":150775,"type":2}
    ]""").jsonArray
    for (kind in listOf(AnimeCreditKind.CHARACTER, AnimeCreditKind.STAFF))
      assertEquals(setOf(150775L), decodeAnimeCreditSubjects(rows, kind))
  }
  @Test fun creditNamespacesDoNotCollideAndNamesDoNotDetermineIdentity() {
    val subject = AnimeSubject(1, "Example", cast = listOf(AnimeCast(11, "Same name", actors = listOf(AnimePerson(22, "Actor")))),
      staff = listOf(AnimePerson(33, "Staff")))
    assertTrue(animeCreditMatches(subject, AnimeCreditSelection(AnimeCreditKind.CHARACTER, 11, "Other translation")))
    assertTrue(animeCreditMatches(subject, AnimeCreditSelection(AnimeCreditKind.VOICE, 22, "Actor")))
    assertTrue(animeCreditMatches(subject, AnimeCreditSelection(AnimeCreditKind.STAFF, 33, "Staff")))
    assertFalse(animeCreditMatches(subject, AnimeCreditSelection(AnimeCreditKind.STAFF, 22, "Actor")))
    assertFalse(animeCreditMatches(subject, AnimeCreditSelection(AnimeCreditKind.CHARACTER, 99, "Same name")))
  }
}
