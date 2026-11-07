package app.gyrolet.mpvrx.ui.player

import org.junit.Assert.*
import org.junit.Test

class PreparedPlaybackLaunchStoreTest {
  private fun item(id: String) = PlaybackItem(stableId = id, originalUri = "/$id.mp4")

  private fun accept(token: Long): PreparedPlaybackLaunch =
    (PreparedPlaybackLaunchStore.consume(token) as PreparedPlaybackLaunchResult.Accepted).launch

  @Test fun stagingOnlyCopiesQueueDataWithoutCreatingANativeCore() {
    // A local JVM has no Android/libmpv runtime: staging must remain a data-only operation.
    val headers = mutableMapOf("Authorization" to "test-token")
    val items = mutableListOf(item("first").copy(headers = headers), item("second"))
    val token = PreparedPlaybackLaunchStore.stage(items, 1, isExplicitQueue = false, isM3u = true)
    headers["Authorization"] = "changed"
    items.clear()
    val launch = accept(token)
    assertEquals(listOf("first", "second"), launch.items.map { it.stableId })
    assertEquals("test-token", launch.items.first().headers["Authorization"])
    assertEquals(1, launch.currentIndex)
    assertFalse(launch.isExplicitQueue)
    assertTrue(launch.isM3u)
  }

  @Test fun newerLaunchSupersedesOldLaunchWithoutConsumingTheNewOne() {
    val old = PreparedPlaybackLaunchStore.stage(listOf(item("old")), 0)
    val current = PreparedPlaybackLaunchStore.stage(listOf(item("current")), 0)
    assertEquals(PreparedPlaybackLaunchResult.Stale, PreparedPlaybackLaunchStore.consume(old))
    assertEquals("current", accept(current).items.single().stableId)
  }

  @Test fun aPreparedQueueCanOnlyBeConsumedOnce() {
    val token = PreparedPlaybackLaunchStore.stage(listOf(item("once")), 0)
    accept(token)
    assertEquals(PreparedPlaybackLaunchResult.Stale, PreparedPlaybackLaunchStore.consume(token))
  }

  @Test fun outOfRangeSelectionIsClamped() {
    val items = listOf(item("a"), item("b"))
    assertEquals(0, accept(PreparedPlaybackLaunchStore.stage(items, -10)).currentIndex)
    assertEquals(1, accept(PreparedPlaybackLaunchStore.stage(items, 10)).currentIndex)
  }

  @Test fun missingProcessLocalLaunchAllowsTheIntentFallback() {
    assertEquals(PreparedPlaybackLaunchResult.Missing, PreparedPlaybackLaunchStore.consume(0))
    assertEquals(PreparedPlaybackLaunchResult.Missing, PreparedPlaybackLaunchStore.consume(Long.MAX_VALUE))
  }

  @Test(expected = IllegalArgumentException::class) fun emptyQueuesCannotBeStaged() {
    PreparedPlaybackLaunchStore.stage(emptyList(), 0)
  }
}
