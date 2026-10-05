package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.domain.cloud.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnimeCreditRelationsTest {
  @get:Rule val temporary = TemporaryFolder()
  private val actor = AnimeCreditSelection(AnimeCreditKind.VOICE, 22, "Actor")
  @Test fun persistedRelationsAreReusedAndFailedRefreshKeepsThem() = runBlocking {
    val file = temporary.newFile("relations.json")
    var calls = 0
    val cache = AnimeCreditRelations(file) { path ->
      calls++
      assertEquals("/v0/persons/22/characters", path)
      Json.parseToJsonElement("""[{"subject_id":100,"subject_type":2}]""")
    }
    assertEquals(setOf(100L), cache.resolve(actor))
    val reopened = AnimeCreditRelations(file) { error("Offline") }
    assertEquals(setOf(100L), reopened.resolve(actor))
    try { reopened.resolve(actor, force = true); fail("Should report refresh failure") }
    catch (_: IllegalStateException) { }
    assertEquals(setOf(100L), reopened.cached(actor))
    assertEquals(1, calls)
  }
  @Test fun malformedCacheIsReplacedAndKindsUseSeparateKeys() = runBlocking {
    val file = temporary.newFile("relations.json")
    file.writeText("""{"VOICE:22":{}}""")
    val paths = mutableListOf<String>()
    val cache = AnimeCreditRelations(file) { path ->
      paths += path
      Json.parseToJsonElement(if (path.endsWith("characters")) """[{"subject_id":100,"subject_type":2}]"""
        else """[{"id":200,"type":2}]""")
    }
    assertTrue(cache.cached(actor).isEmpty())
    assertEquals(setOf(100L), cache.resolve(actor))
    assertEquals(setOf(200L), cache.resolve(actor.copy(kind = AnimeCreditKind.STAFF)))
    assertEquals(listOf("/v0/persons/22/characters", "/v0/persons/22/subjects"), paths)
  }
}
