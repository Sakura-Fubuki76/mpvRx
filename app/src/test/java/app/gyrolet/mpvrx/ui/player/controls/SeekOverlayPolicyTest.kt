package app.gyrolet.mpvrx.ui.player.controls

import org.junit.Assert.*
import org.junit.Test

class SeekOverlayPolicyTest {
  private fun timeline(horizontal: Boolean = true, portrait: Boolean = false, enabled: Boolean = true,
    startedVisible: Boolean = false, barShown: Boolean = true, locked: Boolean = false, sliders: Boolean = false) =
    seekTimelineVisible(false, horizontal, portrait, enabled, startedVisible, barShown, locked, sliders)

  @Test fun horizontalSeekNeverShowsFullControlsOrBufferingFeedback() {
    assertFalse(fullControlsVisible(true, true))
    assertFalse(fullControlsVisible(false, true))
    assertFalse(bufferingFeedbackEnabled(true, false, true))
    assertTrue(timeline())
  }

  @Test fun ordinaryControlsAndCacheStallsStillWorkAfterTheSwipe() {
    assertTrue(fullControlsVisible(true, false))
    assertTrue(bufferingFeedbackEnabled(true, false, false))
    assertFalse(bufferingFeedbackEnabled(true, true, false))
  }

  @Test fun respectsSeekbarPreferenceUnlessTheSwipeStartedWithControlsVisible() {
    assertFalse(timeline(enabled = false))
    assertTrue(timeline(enabled = false, startedVisible = true))
  }

  @Test fun preservesPortraitAndLockRules() {
    assertFalse(timeline(portrait = true))
    assertFalse(timeline(locked = true))
    assertFalse(timeline(sliders = true))
  }

  @Test fun clearingTheGestureAndItsTemporaryBarHidesTheTimeline() {
    assertTrue(timeline(horizontal = false, barShown = true))
    assertFalse(timeline(horizontal = false, barShown = false))
  }
}
