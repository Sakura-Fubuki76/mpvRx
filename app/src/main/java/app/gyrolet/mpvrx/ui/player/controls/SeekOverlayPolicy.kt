package app.gyrolet.mpvrx.ui.player.controls

/** Horizontal seeking owns a minimal overlay even if another callback requests full controls. */
internal fun fullControlsVisible(requested: Boolean, horizontalSeeking: Boolean): Boolean =
  requested && !horizontalSeeking

/** A live scrub is user interaction, not a background cache stall to surface over the thumbnail. */
internal fun bufferingFeedbackEnabled(enabled: Boolean, scrubbing: Boolean, horizontalSeeking: Boolean): Boolean =
  enabled && !scrubbing && !horizontalSeeking

/** Preserve the fork's existing preference and lock rules for the timeline. */
internal fun seekTimelineVisible(
  controlsShown: Boolean,
  horizontalSeeking: Boolean,
  showDuringHorizontalSeeking: Boolean,
  startedWithControlsShown: Boolean,
  seekBarShown: Boolean,
  controlsLocked: Boolean,
  slidersShown: Boolean,
): Boolean =
  (controlsShown ||
    if (horizontalSeeking) {
      showDuringHorizontalSeeking || startedWithControlsShown
    } else {
      seekBarShown
    }) && !controlsLocked && !slidersShown
