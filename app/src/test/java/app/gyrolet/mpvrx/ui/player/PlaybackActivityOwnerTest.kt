package app.gyrolet.mpvrx.ui.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlaybackActivityOwnerTest {
  private var currentToken = 0L

  private fun claim(): Long = PlaybackActivityOwner.claim().also { currentToken = it }

  @After fun releaseOwner() {
    PlaybackActivityOwner.release(currentToken)
  }

  @Test fun onlyTheLatestActivityOwnsPlayback() {
    val previous = claim()
    val current = claim()
    assertFalse(PlaybackActivityOwner.owns(previous))
    assertTrue(PlaybackActivityOwner.owns(current))
    assertTrue(PlaybackActivityOwner.hasOwner.value)
  }

  @Test fun destroyingAnOldActivityDoesNotReleaseTheReplacement() {
    val previous = claim()
    val current = claim()
    PlaybackActivityOwner.release(previous)
    assertTrue(PlaybackActivityOwner.owns(current))
    assertTrue(PlaybackActivityOwner.hasOwner.value)
  }

  @Test fun releasingTheOwnerMakesIdleReapingEligibleAgain() {
    val token = claim()
    PlaybackActivityOwner.release(token)
    assertFalse(PlaybackActivityOwner.owns(token))
    assertFalse(PlaybackActivityOwner.hasOwner.value)
    PlaybackActivityOwner.release(token)
    assertFalse(PlaybackActivityOwner.hasOwner.value)
  }

  @Test fun invalidTokensNeverOwnOrReleaseTheCore() {
    val current = claim()
    for (invalid in listOf(0L, -1L)) {
      assertFalse(PlaybackActivityOwner.owns(invalid))
      PlaybackActivityOwner.release(invalid)
      assertFalse(PlaybackActivityOwner.beginRequest(invalid) { fail("stale action ran") })
      assertEquals("stale", PlaybackActivityOwner.runIfOwner(invalid, "stale") { "active" })
    }
    assertTrue(PlaybackActivityOwner.owns(current))
  }

  @Test fun staleActivityCannotCommitAnotherMediaRequest() {
    val old = claim()
    val current = claim()
    var calls = 0
    assertFalse(PlaybackActivityOwner.beginRequest(old) { calls++ })
    assertTrue(PlaybackActivityOwner.beginRequest(current) { calls++ })
    assertEquals(1, calls)
    assertEquals(42, PlaybackActivityOwner.runIfOwner(current, -1) { 42 })
  }

  @Test fun ownerPredicateDoesNotWaitForTheOwnershipWriteLock() {
    // Core initialization evaluates owns() under nativeLock. Taking the ownership read lock
    // there would invert the order of beginRequest/runIfOwner actions that enter nativeLock.
    val token = claim()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val writer = pool.submit<Boolean> {
        PlaybackActivityOwner.beginRequest(token) {
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
        }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val predicate = pool.submit<Boolean> { PlaybackActivityOwner.owns(token) }
      assertTrue(predicate.get(2, TimeUnit.SECONDS))
      release.countDown()
      assertTrue(writer.get(5, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      pool.shutdownNow()
      pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }
}
