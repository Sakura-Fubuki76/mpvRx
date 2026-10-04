package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnimeCreditsTest {
  @Test fun nullableImagesAndMultipleActorsArePreserved() {
    val rows = Json.parseToJsonElement("""[{"id":1,"name":"角色","relation":"主角","images":null,"actors":[{"id":2,"name":"演员甲","images":{"medium":"a.jpg"}},{"id":3,"name":"演员乙","images":null}]}]""").jsonArray
    val cast = decodeAnimeCast(rows).single()
    assertEquals("", cast.image)
    assertEquals(listOf("演员甲", "演员乙"), cast.actors.map { it.name })
    assertEquals("a.jpg", cast.actors.first().image)
  }
  @Test fun staffCombinesJobsWithoutDuplicatePeople() {
    val rows = Json.parseToJsonElement("""[{"id":1,"name":"Staff","relation":"导演"},{"id":1,"name":"Staff","relation":"分镜"},{"id":1,"name":"Staff","relation":"导演"}]""").jsonArray
    assertEquals("导演 · 分镜", decodeAnimeStaff(rows).single().role)
  }
  @Test fun olderPersistedSubjectPayloadsRemainReadable() {
    val subject = Json.decodeFromString<AnimeSubject>("""{"id":1,"name":"Anime"}""")
    assertTrue(subject.cast.isEmpty())
    assertEquals(0L, subject.creditsFetchedAt)
    val saved = subject.copy(cast = listOf(AnimeCast(2, "角色")), creditsFetchedAt = 123)
    assertEquals(saved, Json.decodeFromString<AnimeSubject>(Json.encodeToString(AnimeSubject.serializer(), saved)))
  }
  @Test fun explicitSequelAliasesNeverCollapseIntoOriginalTitle() {
    assertEquals(animeNameKey("Tantei Opera Milky Holmes II"), animeNameKey("Tantei Opera Milky Holmes 2"))
    assertNotEquals(animeNameKey("Tantei Opera Milky Holmes"), animeNameKey("Tantei Opera Milky Holmes II"))
    assertEquals(animeNameKey("Anime OAD"), animeNameKey("Anime OADs"))
  }
}
