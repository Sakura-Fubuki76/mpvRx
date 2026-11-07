package app.gyrolet.mpvrx.ui.player

import org.junit.Assert.*
import org.junit.Test

class ScrubSeekPolicyTest {
  @Test fun firstTargetAlwaysSeeks() {
    assertTrue(shouldIssueScrubSeek(Double.NaN, 0.0))
    assertTrue(shouldIssueScrubSeek(Double.NaN, 100.0))
  }

  @Test fun releaseNearTheLastLiveTargetDoesNotRepeatTheSeek() {
    assertFalse(shouldIssueScrubSeek(100.0, 100.0))
    assertFalse(shouldIssueScrubSeek(100.0, 100.49))
    assertFalse(shouldIssueScrubSeek(100.0, 99.51))
  }

  @Test fun halfSecondStepsWorkInBothDirections() {
    assertTrue(shouldIssueScrubSeek(100.0, 100.5))
    assertTrue(shouldIssueScrubSeek(100.0, 99.5))
  }

  @Test fun invalidTargetsNeverReachTheNativeCommand() {
    assertFalse(shouldIssueScrubSeek(Double.NaN, Double.NaN))
    assertFalse(shouldIssueScrubSeek(0.0, Double.POSITIVE_INFINITY))
    assertFalse(shouldIssueScrubSeek(0.0, Double.NEGATIVE_INFINITY))
  }

  @Test fun continuousDragAndReleaseDoNotDecodeTheSameTargetTwice() {
    var last = Double.NaN
    val issued = mutableListOf<Double>()
    for (target in listOf(10.0, 10.1, 10.49, 10.5, 10.6, 10.6)) {
      if (shouldIssueScrubSeek(last, target)) {
        issued += target
        last = target
      }
    }
    assertEquals(listOf(10.0, 10.5), issued)
  }

  @Test fun clampsToDurationWithoutForcingIntegerOrExactSeeks() {
    assertEquals(0.0, clampScrubTarget(-4.0, 120.0, null, null), 0.0)
    assertEquals(120.0, clampScrubTarget(125.0, 120.0, null, null), 0.0)
    assertEquals(54.75, clampScrubTarget(54.75, 120.0, null, null), 0.0)
    assertEquals(125.0, clampScrubTarget(125.0, 0.0, null, null), 0.0)
  }

  @Test fun respectsReversedAndPartialAbLoops() {
    assertEquals(20.0, clampScrubTarget(10.0, 120.0, 40.0, 20.0), 0.0)
    assertEquals(40.0, clampScrubTarget(60.0, 120.0, 40.0, 20.0), 0.0)
    assertEquals(10.0, clampScrubTarget(10.0, 120.0, 20.0, null), 0.0)
  }
}
