package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AnimePlaybackSnapshotTest {
  private fun watched(id: String) = PlaybackStateEntity(id, 100, 1.0, sid = -1,
    subDelay = 0, subSpeed = 1.0, aid = -1, audioDelay = 0, hasBeenWatched = true)
  private fun library(vararg ids: String) = AnimeLibrarySnapshot(
    playbackIdentities = ids.associateWith { it }, loaded = true)

  @Test fun returningAfterSubscriptionStopsReplaysProgressBeforeRoomResponds() = runBlocking {
    val owner = CoroutineScope(coroutineContext + SupervisorJob())
    try {
      val library = MutableStateFlow(library("episode"))
      var queries = 0
      val resume = CompletableDeferred<Unit>()
      val states = animePlaybackSnapshot(owner, library) { flow {
        queries++
        if (queries > 1) resume.await()
        emit(listOf(watched("episode")))
        awaitCancellation()
      } }
      withTimeout(2_000) { states.first { it.isNotEmpty() } }
      delay(5_100) // The page has been gone longer than WhileSubscribed's timeout.
      assertTrue(states.first().getValue("episode").hasBeenWatched)
      val returningPage = launch { states.collect() }
      withTimeout(2_000) { while (queries < 2) yield() }
      assertTrue(states.value.getValue("episode").hasBeenWatched)
      resume.complete(Unit)
      returningPage.cancelAndJoin()
    } finally { owner.cancel() }
  }

  @Test fun metadataOnlyUpdatesDoNotRestartQueriesAndAuthoritativeDeletionIsApplied() = runBlocking {
    val owner = CoroutineScope(coroutineContext + SupervisorJob())
    try {
      val library = MutableStateFlow(library("episode"))
      val rows = MutableStateFlow(listOf(watched("episode")))
      var queries = 0
      val states = animePlaybackSnapshot(owner, library) { queries++; rows }
      val page = launch { states.collect() }
      withTimeout(2_000) { states.first { it.isNotEmpty() } }
      library.value = library("episode") // A fresh metadata snapshot, same identities.
      repeat(10) { yield() }
      assertEquals(1, queries)
      rows.value = emptyList()
      withTimeout(2_000) { states.first { it.isEmpty() } }
      page.cancelAndJoin()
    } finally { owner.cancel() }
  }

  @Test fun changingIdentitySetRetainsPreviousProgressUntilAllBatchesArrive() = runBlocking {
    val owner = CoroutineScope(coroutineContext + SupervisorJob())
    try {
      val library = MutableStateFlow(library("episode"))
      val gate = CompletableDeferred<Unit>()
      var batches = 0
      val states = animePlaybackSnapshot(owner, library) { ids -> flow {
        batches++
        if (ids.size > 1 || "episode" !in ids) gate.await()
        emit(ids.filter { it == "episode" }.map(::watched))
        awaitCancellation()
      } }
      val page = launch { states.collect() }
      withTimeout(2_000) { states.first { it.isNotEmpty() } }
      library.value = library("episode", *(1..400).map { "new$it" }.toTypedArray())
      withTimeout(2_000) { while (batches < 3) yield() }
      assertTrue(states.value.getValue("episode").hasBeenWatched)
      gate.complete(Unit)
      library.value = library()
      withTimeout(2_000) { states.first { it.isEmpty() } }
      page.cancelAndJoin()
    } finally { owner.cancel() }
  }
}
