package app.gyrolet.mpvrx.ui.player.controls.components

import org.junit.Assert.*
import org.junit.Test

class SeekbarPositionPolicyTest {
  private fun position(native: Float = 101f, animated: Float = 100f, user: Float = 50f,
    dragging: Boolean = false, external: Boolean = false, preview: Float? = null) =
    resolveSeekbarPosition(native, animated, user, dragging, external, preview)

  @Test fun ordinaryPlaybackTicksKeepTheirAnimation() {
    assertEquals(100f, position(), 0f)
  }

  @Test fun restoringOrSeekingFarAwaySnapsImmediately() {
    assertEquals(110f, position(native = 110f), 0f)
    assertEquals(90f, position(native = 90f), 0f)
  }

  @Test fun nativeHorizontalTargetAndThumbnailStayTogetherWithoutSpringLag() {
    assertEquals(101f, position(external = true, preview = 101f), 0f)
    assertEquals(101f, position(external = true), 0f)
  }

  @Test fun directThumbDragAlwaysUsesTheFingerPosition() {
    assertEquals(50f, position(dragging = true), 0f)
    assertEquals(50f, position(dragging = true, preview = 101f), 0f)
  }

  @Test fun endingTheGestureReturnsToPlaybackPosition() {
    assertEquals(101f, position(external = true, preview = 101f), 0f)
    assertEquals(100f, position(), 0f)
  }

  @Test fun onlyDiscontinuitiesLargerThanTwoSecondsSnap() {
    assertFalse(shouldSnapSeekbarPosition(102f, 100f))
    assertTrue(shouldSnapSeekbarPosition(102.1f, 100f))
    assertTrue(shouldSnapSeekbarPosition(97.9f, 100f))
  }
}
