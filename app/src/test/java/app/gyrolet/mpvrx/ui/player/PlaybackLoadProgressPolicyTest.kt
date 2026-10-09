package app.gyrolet.mpvrx.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackLoadProgressPolicyTest {
  @Test fun movingProbeCanContinuePastOriginalDeadline() {
    assertTrue(canExtendPlaybackLoad(60_000, 100))
    assertTrue(canExtendPlaybackLoad(180_000, 30_000))
  }
  @Test fun MissingOrStalledConnectionKeepsOriginalDeadline() {
    assertFalse(canExtendPlaybackLoad(60_000, null))
    assertFalse(canExtendPlaybackLoad(60_000, 30_001))
    assertFalse(canExtendPlaybackLoad(60_000, -1))
  }
  @Test fun activeReadsCannotExtendForever() {
    assertFalse(canExtendPlaybackLoad(300_000, 0))
  }
}
