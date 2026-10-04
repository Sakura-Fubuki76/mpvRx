package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession

/** Defer new background reads during opening, seeking or cache starvation; finish active work. */
internal fun cloudBackgroundAllowed(): Boolean {
  val phase = PlaybackSession.state.value.phase
  if (phase == PlaybackPhase.INITIALIZING || phase == PlaybackPhase.LOADING) return false
  return PlaybackSession.propBoolean["seeking"].value != true &&
    PlaybackSession.propBoolean["paused-for-cache"].value != true
}
