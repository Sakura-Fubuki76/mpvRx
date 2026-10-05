package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test
import app.gyrolet.mpvrx.domain.network.NetworkFile

class AnimeCreditSelectionTest {
  @Test fun relatedVideosExcludeExtraFoldersAndDirectOpeningClips() {
    val main = NetworkFile("Show - 01.mkv", "/show/Show - 01.mkv", 0, false)
    val nestedMain = NetworkFile("Show - 02.mkv", "/show/BD/Show - 02.mkv", 0, false)
    val extras = listOf(NetworkFile("Show - 02.mkv", "/show/SPs/Show - 02.mkv", 0, false),
      NetworkFile("Show - NCOP.mkv", "/show/Show - NCOP.mkv", 0, false),
      NetworkFile("Preview.mkv", "/show/Extra/Preview.mkv", 0, false),
      NetworkFile("Show - OVA.mkv", "/show/Show - OVA.mkv", 0, false),
      NetworkFile("Show - Menu01.mkv", "/show/Show - Menu01.mkv", 0, false))
    assertEquals(listOf(main, nestedMain), animeRelatedMainVideos(AnimeVideoGroup("/show", "/show", "Show", listOf(main, nestedMain) + extras), AnimeSubject(1, "Show")))
  }
  @Test fun mergedChapterMainEpisodesAndStandaloneOvasRemainVisible() {
    val chapter = NetworkFile("Show - 07.mkv", "/show/chapter/Show - 07.mkv", 0, false)
    val group = AnimeVideoGroup("/show", "/show", "Show", listOf(chapter), regularPaths = setOf(chapter.path))
    assertEquals(listOf(chapter), animeRelatedMainVideos(group, AnimeSubject(1, "Show")))
    val ova = NetworkFile("Show - OVA.mkv", "/ova/Show - OVA.mkv", 0, false)
    assertEquals(listOf(ova), animeRelatedMainVideos(AnimeVideoGroup("/ova", "/ova", "Show", listOf(ova)), AnimeSubject(2, "Show", format = "OVA")))
  }
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
    assertTrue(animeCreditMatches(subject, AnimeCreditSelection(AnimeCreditKind.STAFF, 22, "Actor")))
    assertTrue(animeCreditMatches(subject, AnimeCreditSelection(AnimeCreditKind.VOICE, 33, "Staff")))
    assertFalse(animeCreditMatches(subject, AnimeCreditSelection(AnimeCreditKind.CHARACTER, 99, "Same name")))
  }
}
