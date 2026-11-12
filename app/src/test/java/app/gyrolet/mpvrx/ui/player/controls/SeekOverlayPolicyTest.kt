package app.gyrolet.mpvrx.ui.player.controls

import org.junit.Assert.*
import org.junit.Test

class SeekOverlayPolicyTest {
  private fun timeline(horizontal: Boolean = true, enabled: Boolean = true,
    startedVisible: Boolean = false, barShown: Boolean = true, locked: Boolean = false, sliders: Boolean = false) =
    seekTimelineVisible(false, horizontal, enabled, startedVisible, barShown, locked, sliders)

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

  @Test fun portraitSwipeKeepsTheTimelineAndItsPreviewLikeLandscape() {
    // Portrait gestures seek the same way, so they own the same minimal timeline overlay.
    assertTrue(timeline())
    assertTrue(timeline(enabled = false, startedVisible = true))
    assertFalse(timeline(enabled = false))
  }

  @Test fun lockAndSliderRulesStillHideTheTimeline() {
    assertFalse(timeline(locked = true))
    assertFalse(timeline(sliders = true))
  }

  @Test fun clearingTheGestureAndItsTemporaryBarHidesTheTimeline() {
    assertTrue(timeline(horizontal = false, barShown = true))
    assertFalse(timeline(horizontal = false, barShown = false))
  }
}
